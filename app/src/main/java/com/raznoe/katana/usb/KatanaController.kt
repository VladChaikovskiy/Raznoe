package com.raznoe.katana.usb

import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.KatanaSysEx
import com.raznoe.katana.protocol.ParamKind
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * High-level operations on the amp, layered over a [UsbMidiConnection].
 *
 * All outbound traffic goes through a single-thread queue that paces messages
 * (writes settle ~4 ms, reads ~30 ms) so we neither flood the amp nor block the
 * UI thread on USB I/O — both are documented requirements of the protocol.
 */
class KatanaController(private val connection: UsbMidiConnection) {

    var onTraffic: ((dir: String, sysex: ByteArray) -> Unit)? = null
    var onIncoming: ((KatanaSysEx.Incoming) -> Unit)? = null
    var onIdentity: ((IntArray) -> Unit)? = null
    var onInfo: ((String) -> Unit)? = null
    var onSelectors: ((IntArray) -> Unit)? = null

    @Volatile private var headerLearned = false

    /**
     * Live cache of the 5 Gen 3 FX-BOX selector bytes (COLOR block, 20 00 04 00),
     * which decide the physical slot each banked effect occupies. Updated from
     * inbound data; used to resolve banked effect-param addresses.
     */
    private val gen3Selectors = IntArray(KatanaParams.GEN3_SELECTOR_COUNT)

    private val sender = Executors.newSingleThreadExecutor { r ->
        Thread(r, "katana-sender").apply { isDaemon = true }
    }

    /** Reader callback: classify inbound frames (identity reply, DT1 data). */
    fun handleInbound(sysex: ByteArray) {
        onTraffic?.invoke("RX", sysex)
        // Primary: decode the dialect (MkII / Gen3 / GO) from the Identity Reply,
        // exactly like the official app. This sets the right header prefix.
        if (KatanaSysEx.adoptFromIdentity(sysex)) {
            val first = !headerLearned
            headerLearned = true
            onInfo?.invoke("✓ ${KatanaSysEx.generation} — заголовок ${KatanaSysEx.headerHex()}")
            if (first) {
                enqueue(KatanaSysEx.editorMode(true), settleMs = 60)
                readAll()
            }
        } else if (KatanaSysEx.adoptHeaderFrom(sysex)) {
            headerLearned = true
            onInfo?.invoke("✓ Заголовок из ответа: ${KatanaSysEx.headerHex()}")
        }
        KatanaSysEx.parseIdentityReply(sysex)?.let { onIdentity?.invoke(it) }
        KatanaSysEx.parse(sysex)?.let { inc ->
            captureSelectors(inc)
            onIncoming?.invoke(inc)
        }
    }

    /** If a frame carries the COLOR block (20 00 04 0x), cache the selector bytes. */
    private fun captureSelectors(inc: KatanaSysEx.Incoming) {
        val a = inc.address
        if (a.size == 4 && a[0] == 0x20 && a[1] == 0x00 && a[2] == 0x04) {
            val start = a[3]
            var changed = false
            for (i in inc.data.indices) {
                val slot = start + i
                if (slot in gen3Selectors.indices) {
                    gen3Selectors[slot] = inc.data[i] and 0x7F; changed = true
                }
            }
            if (changed) onSelectors?.invoke(gen3Selectors.copyOf())
        }
    }

    /**
     * The wire address to use for [param] right now. Banked Gen 3 effect params
     * resolve through the live selector cache; everything else is static.
     */
    fun resolveAddress(param: KatanaParam): IntArray {
        val gen3 = KatanaSysEx.generation == KatanaSysEx.Gen.GEN3
        val slots = param.gen3Slots
        if (gen3 && slots != null && param.gen3Sel in gen3Selectors.indices) {
            val slot = gen3Selectors[param.gen3Sel].coerceIn(0, slots.size - 1)
            return KatanaSysEx.gen3AddrFromBase(slots[slot], param.gen3Index)
        }
        return param.addressFor(gen3)
    }

    /** Connect: identity request (→ dialect), announce, editor mode, read tone. */
    fun begin() {
        headerLearned = false
        KatanaSysEx.resetProfile()
        enqueue(KatanaSysEx.identityRequest(), settleMs = 60)
        val hs = KatanaSysEx.announceHandshake()
        enqueue(hs, settleMs = 20)
        enqueue(hs, settleMs = 20)
        enqueue(KatanaSysEx.editorMode(true), settleMs = 100)
        readAll()
    }

    /**
     * If the default MkII profile gets no reply, sweep every model id (0..0x7F)
     * with a tiny read; the amp's reply (if any) carries its real prefix, which
     * [handleInbound] adopts. Sent silently so it doesn't flood the log.
     */
    private fun probeModelIds() {
        // Try both the classic device id (00 00 00 00) and the modern one (10 …,
        // as used by recent BOSS gear like Katana:GO), sweeping the model byte.
        val devPrefixes = listOf(
            intArrayOf(0x00, 0x00, 0x00, 0x00),
            intArrayOf(0x10, 0x00, 0x00, 0x00),
        )
        for (dev in devPrefixes) for (id in 0..0x7F) {
            if (sender.isShutdown) return
            runCatching {
                sender.submit {
                    if (headerLearned) return@submit
                    KatanaSysEx.setPrefix(dev + intArrayOf(id))
                    sendSilent(KatanaSysEx.editorMode(true))
                    sendSilent(KatanaSysEx.buildQuery(intArrayOf(0x00, 0x01, 0x00, 0x00), 1))
                    sleep(8)
                }
            }
        }
        runCatching {
            sender.submit {
                if (!headerLearned) {
                    KatanaSysEx.resetProfile()
                    onInfo?.invoke(
                        "⚠ Диалект Gen 3 не определён автоперебором. У Gen 3 команды устроены " +
                            "иначе, чем у MkII, и их формат пока не публичный. Нужен захват трафика " +
                            "BOSS Tone Studio↔Gen3 (USB) — тогда впишу точный протокол.",
                    )
                }
            }
        }
    }

    private fun encode(param: KatanaParam, value: Int): IntArray {
        // ENUM wire values can have gaps (e.g. Chorus == 29 while max index is
        // smaller), so only clamp continuous/toggle params.
        val v = if (param.kind == ParamKind.ENUM) value and 0x7F
        else value.coerceIn(param.min, param.max)
        return if (param.word) intArrayOf((v shr 7) and 0x7F, v and 0x7F) else intArrayOf(v and 0x7F)
    }

    /** Interactive single-knob write: robust ×3 across banked slots. */
    fun setParam(param: KatanaParam, value: Int) {
        val data = encode(param, value)
        val gen3 = KatanaSysEx.generation == KatanaSysEx.Gen.GEN3
        val slots = param.gen3Slots
        if (gen3 && slots != null) {
            // Banked effect param: which physical slot is active depends on the
            // FX-BOX selector, which we may not have read reliably. Writing every
            // candidate slot is what the app does (N() with the MK3 flag) and is
            // harmless — inactive slots aren't in the signal path — so the active
            // one always receives the value.
            for (base in slots) enqueueParam(KatanaSysEx.gen3AddrFromBase(base, param.gen3Index), data)
        } else {
            enqueueParam(resolveAddress(param), data)
        }
    }

    /**
     * Batch write for loading a whole preset: one message per param to the
     * ACTIVE slot only (via the selector cache read on connect). Goes through
     * the same coalescing queue, so it can't flood the amp either.
     */
    fun applyParam(param: KatanaParam, value: Int) {
        enqueueParam(resolveAddress(param), encode(param, value))
    }

    /** Select Panel/CH1..CH4 via the documented SysEx address. */
    fun selectChannel(dataByte: Int) {
        enqueue(KatanaSysEx.buildSet(KatanaParams.CURRENT_PRESET_ADDR, dataByte), settleMs = 20)
        readAll() // reflect the recalled tone in the UI
    }

    /** Alternative channel select via MIDI Program Change. */
    fun selectProgram(program: Int) {
        enqueueRaw(UsbMidiPacketizer.encodeProgramChange(0, program), settleMs = 20) {
            onTraffic?.invoke("TX", byteArrayOf((0xC0).toByte(), (program and 0x7F).toByte()))
        }
    }

    fun readAll() {
        val ranges = if (KatanaSysEx.generation == KatanaSysEx.Gen.GEN3)
            KatanaParams.GEN3_READ_RANGES else KatanaParams.READ_RANGES
        for (r in ranges) {
            enqueue(KatanaSysEx.buildQuery(r.address, r.size), settleMs = 30)
        }
    }

    fun readBlock(address: IntArray, size: Int) {
        enqueue(KatanaSysEx.buildQuery(address, size), settleMs = 30)
    }

    /** Send a fully-formed raw SysEx frame (from the console). */
    fun sendSysEx(sysex: ByteArray) = enqueue(sysex, settleMs = 4)

    /** Politely leave editor mode and stop the sender. */
    fun shutdown() {
        runCatching {
            sender.submit {
                sendNow(KatanaSysEx.editorMode(false))
            }
            sender.shutdown()
            sender.awaitTermination(500, TimeUnit.MILLISECONDS)
        }
    }

    // --- coalescing parameter queue --------------------------------------
    // A knob drag fires setParam dozens of times per second; without coalescing
    // the single-thread queue grows faster than it drains (and banked params
    // triple it), backing up until the app chokes and "stops working". We keep
    // only the LATEST pending value per wire address — a 0→100 sweep collapses
    // to a steady trickle, the queue never grows past the number of distinct
    // addresses (~40), and the amp still lands on the final value.
    private val pending = LinkedHashMap<String, ByteArray>()
    private var drainScheduled = false

    private fun enqueueParam(address: IntArray, data: IntArray) {
        if (sender.isShutdown) return
        val key = address.joinToString(",") { (it and 0xFF).toString() }
        val frame = KatanaSysEx.buildSet(address, data)
        val start: Boolean
        synchronized(pending) {
            pending[key] = frame
            start = !drainScheduled
            if (start) drainScheduled = true
        }
        if (start) runCatching { sender.submit { drainParams() } }
    }

    private fun drainParams() {
        while (!sender.isShutdown) {
            val frame: ByteArray = synchronized(pending) {
                val k = pending.keys.firstOrNull()
                if (k == null) { drainScheduled = false; return }
                pending.remove(k)!!
            }
            sendNow(frame)
            sleep(PARAM_SETTLE_MS)
        }
        synchronized(pending) { drainScheduled = false }
    }

    // --- internals --------------------------------------------------------
    private fun enqueue(sysex: ByteArray, settleMs: Long) {
        if (sender.isShutdown) return
        runCatching {
            sender.submit {
                sendNow(sysex)
                sleep(settleMs)
            }
        }
    }

    private fun enqueueRaw(packets: ByteArray, settleMs: Long, log: () -> Unit) {
        if (sender.isShutdown) return
        runCatching {
            sender.submit {
                log()
                connection.sendPackets(packets)
                sleep(settleMs)
            }
        }
    }

    private fun sendNow(sysex: ByteArray) {
        val ok = connection.sendPackets(UsbMidiPacketizer.encodeSysEx(sysex))
        onTraffic?.invoke(if (ok) "TX" else "TX-FAIL", sysex)
    }

    /** Send without logging — used by the model-id sweep to avoid log spam. */
    private fun sendSilent(sysex: ByteArray) {
        connection.sendPackets(UsbMidiPacketizer.encodeSysEx(sysex))
    }

    private fun sleep(ms: Long) = runCatching { if (ms > 0) Thread.sleep(ms) }

    private companion object {
        // Pace between coalesced parameter writes. Gentle enough that a full
        // preset (~38 distinct addresses) never overruns the amp's MIDI buffer,
        // fast enough that a knob feels responsive.
        const val PARAM_SETTLE_MS = 10L
    }
}

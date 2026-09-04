package com.raznoe.katana.usb

import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.KatanaSysEx
import com.raznoe.katana.protocol.ParamKind
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * True once the amp has actually reported the whole COLOR block, i.e. we
     * know which physical slot each banked effect occupies. Until then a banked
     * write has to go to every candidate slot, or it silently lands on the
     * wrong one and the parameter appears to do nothing.
     */
    @Volatile var selectorsKnown = false
        private set

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
            if (changed) {
                if (start == 0 && inc.data.size >= KatanaParams.GEN3_SELECTOR_COUNT) {
                    selectorsKnown = true
                }
                onSelectors?.invoke(gen3Selectors.copyOf())
            }
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

    private fun encode(param: KatanaParam, value: Int): IntArray {
        // ENUM wire values can have gaps (e.g. Chorus == 29 while max index is
        // smaller), so only clamp continuous/toggle params.
        val v = if (param.kind == ParamKind.ENUM) value and 0x7F
        else value.coerceIn(param.min, param.max)
        return if (param.word) intArrayOf((v shr 7) and 0x7F, v and 0x7F) else intArrayOf(v and 0x7F)
    }

    /**
     * Every wire address a write for [param] has to reach.
     *
     * A banked Gen 3 effect parameter lives in whichever physical slot the
     * effect currently occupies. Once the amp has told us the FX-BOX selectors
     * ([selectorsKnown]) we write only the active slot; before that we write
     * all three candidates — inactive slots are not in the signal path, so it
     * is harmless, and it is the difference between the write landing and the
     * parameter appearing to be ignored.
     */
    private fun addressesFor(param: KatanaParam): List<IntArray> {
        val gen3 = KatanaSysEx.generation == KatanaSysEx.Gen.GEN3
        val slots = param.gen3Slots
        if (gen3 && slots != null) {
            if (selectorsKnown) return listOf(resolveAddress(param))
            return slots.map { KatanaSysEx.gen3AddrFromBase(it, param.gen3Index) }
        }
        return listOf(param.addressFor(gen3))
    }

    /** Interactive single-knob write. */
    fun setParam(param: KatanaParam, value: Int) {
        val data = encode(param, value)
        for (addr in addressesFor(param)) enqueueParam(addr, data)
        sendToggleCc(param, value)
    }

    /**
     * Back a block's on/off up with its documented Control Change.
     *
     * BOOST/MOD is a single DSP knob with two ranges, as is DELAY/FX, so the
     * SysEx toggle only bites when the matching range is the active one — the
     * spec says as much and points at the CC instead. Sending the CC after the
     * address write means the switch lands whichever range the amp is on.
     * BOSS gear reads a CC under 64 as off and 64 or over as on.
     */
    private fun sendToggleCc(param: KatanaParam, value: Int) {
        if (param.kind != ParamKind.TOGGLE) return
        val cc = KatanaParams.TOGGLE_CC[param.id] ?: return
        val ccValue = if (value != 0) 127 else 0
        enqueueRaw(
            UsbMidiPacketizer.encodeControlChange(0, cc, ccValue),
            settleMs = 8,
        ) {
            onTraffic?.invoke("TX", byteArrayOf(0xB0.toByte(), cc.toByte(), ccValue.toByte()))
        }
    }

    /**
     * Load a whole patch as one atomic, ordered batch.
     *
     * This does NOT go through the coalescing knob queue: a preset is a set of
     * values that must all land, in order, and coalescing by address meant a
     * queued knob sweep could overwrite parameters the preset was still
     * sending. Any pending knob writes are dropped, and a newer preset load
     * supersedes one still in flight (tapping two presets quickly used to
     * interleave them into a tone that was neither).
     *
     * @param onDone called on the sender thread with (framesSent, framesTotal);
     *   framesSent < framesTotal means the load was superseded or torn down.
     */
    fun applyPreset(
        entries: List<Pair<KatanaParam, Int>>,
        onDone: ((sent: Int, total: Int) -> Unit)? = null,
    ) {
        val epoch = presetEpoch.incrementAndGet()
        synchronized(pending) { pending.clear() }
        val frames = ArrayList<ByteArray>(entries.size * 2)
        for ((param, value) in entries) {
            val data = encode(param, value)
            for (addr in addressesFor(param)) frames.add(KatanaSysEx.buildSet(addr, data))
        }
        if (sender.isShutdown) { onDone?.invoke(0, frames.size); return }
        runCatching {
            sender.submit {
                var sent = 0
                for (f in frames) {
                    if (sender.isShutdown || presetEpoch.get() != epoch) break
                    sendNow(f)
                    sent++
                    sleep(PRESET_SETTLE_MS)
                }
                onDone?.invoke(sent, frames.size)
            }
        }.onFailure { onDone?.invoke(0, frames.size) }
    }

    /**
     * Select Panel/CH1..CH4.
     *
     * The documented payload for the recall address is TWO bytes, `00 <ch>`;
     * we were sending one, so the amp took our channel number as the first
     * half of a value it never got the rest of. The spec also notes that a
     * Program Change is "probably much simpler", so we send both — same
     * destination either way, and the PC works even if the address is wrong
     * for this generation.
     */
    fun selectChannel(dataByte: Int) {
        val ch = dataByte and 0x7F
        enqueue(
            KatanaSysEx.buildSet(KatanaParams.CURRENT_PRESET_ADDR, intArrayOf(0x00, ch)),
            settleMs = 20,
        )
        selectProgram(ch)
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

    /**
     * Write a block of bytes straight back to [address].
     *
     * Used to replay a raw tone captured from the amp. Goes through the sender
     * queue with the preset pace so a whole capture cannot overrun the amp's
     * MIDI buffer.
     */
    fun writeBlock(address: IntArray, data: IntArray) {
        if (address.size != 4 || data.isEmpty()) return
        enqueue(KatanaSysEx.buildSet(address, data), settleMs = PRESET_SETTLE_MS)
    }

    fun readBlock(address: IntArray, size: Int) {
        enqueue(KatanaSysEx.buildQuery(address, size), settleMs = 30)
    }

    /** Send a fully-formed raw SysEx frame (from the console). */
    fun sendSysEx(sysex: ByteArray) = enqueue(sysex, settleMs = 4)

    /**
     * Politely leave editor mode and stop the sender. Capped short so callers
     * (disconnect on the main thread) never freeze the UI; a still-running drain
     * is force-stopped with shutdownNow.
     */
    fun shutdown() {
        runCatching { synchronized(pending) { pending.clear() } }
        runCatching {
            sender.submit { runCatching { sendNow(KatanaSysEx.editorMode(false)) } }
            sender.shutdown()
            if (!sender.awaitTermination(150, TimeUnit.MILLISECONDS)) sender.shutdownNow()
        }.onFailure { runCatching { sender.shutdownNow() } }
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

    /** Id of the newest preset load; an older one still in flight aborts. */
    private val presetEpoch = AtomicInteger(0)

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
        if (start) {
            // If submit fails (executor shut down mid-call), clear the latch so a
            // future enqueue can start a fresh drainer instead of deadlocking.
            runCatching { sender.submit { drainParams() } }
                .onFailure { synchronized(pending) { drainScheduled = false } }
        }
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

    private fun sleep(ms: Long) = runCatching { if (ms > 0) Thread.sleep(ms) }

    private companion object {
        // Pace between coalesced parameter writes. Gentle enough that a full
        // preset (~38 distinct addresses) never overruns the amp's MIDI buffer,
        // fast enough that a knob feels responsive.
        const val PARAM_SETTLE_MS = 10L

        // A preset is a long burst of writes rather than a knob being dragged,
        // so it gets a slightly gentler pace — the amp has time to settle each
        // block and nothing is dropped halfway through.
        const val PRESET_SETTLE_MS = 12L
    }
}

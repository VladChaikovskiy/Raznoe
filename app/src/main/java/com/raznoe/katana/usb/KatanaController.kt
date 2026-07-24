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

    private val sender = Executors.newSingleThreadExecutor { r ->
        Thread(r, "katana-sender").apply { isDaemon = true }
    }

    /** Reader callback: classify inbound frames (DT1 data vs identity reply). */
    fun handleInbound(sysex: ByteArray) {
        onTraffic?.invoke("RX", sysex)
        KatanaSysEx.parseIdentityReply(sysex)?.let { onIdentity?.invoke(it) }
        KatanaSysEx.parse(sysex)?.let { onIncoming?.invoke(it) }
    }

    /** Connect handshake: announce ×2, enter editor mode, then read the tone. */
    fun begin() {
        enqueue(KatanaSysEx.identityRequest(), settleMs = 30)
        val hs = KatanaSysEx.announceHandshake()
        enqueue(hs, settleMs = 20)
        enqueue(hs, settleMs = 20)
        enqueue(KatanaSysEx.editorMode(true), settleMs = 100)
        readAll()
    }

    fun setParam(param: KatanaParam, value: Int) {
        // ENUM wire values can have gaps (e.g. Chorus == 29 while max index is
        // smaller), so only clamp continuous/toggle params.
        val v = if (param.kind == ParamKind.ENUM) value and 0x7F
        else value.coerceIn(param.min, param.max)
        val data = if (param.word) {
            intArrayOf((v shr 7) and 0x7F, v and 0x7F)
        } else {
            intArrayOf(v and 0x7F)
        }
        enqueue(KatanaSysEx.buildSet(param.address, data), settleMs = 4)
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
        for (r in KatanaParams.READ_RANGES) {
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
}

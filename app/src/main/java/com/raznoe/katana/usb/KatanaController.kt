package com.raznoe.katana.usb

import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.KatanaSysEx

/**
 * High-level operations on the amp, layered over a [UsbMidiConnection].
 * Turns "set gain to 60" into the right SysEx + USB-MIDI packets, and turns
 * inbound bytes back into parsed parameter updates.
 */
class KatanaController(private val connection: UsbMidiConnection) {

    /** Raw traffic tap for the on-screen log. dir is "TX" or "RX". */
    var onTraffic: ((dir: String, sysex: ByteArray) -> Unit)? = null

    /** Parsed inbound DT1 messages (address + data). */
    var onIncoming: ((KatanaSysEx.Incoming) -> Unit)? = null

    /** Called by the ViewModel for every inbound SysEx frame from the reader. */
    fun handleInbound(sysex: ByteArray) {
        onTraffic?.invoke("RX", sysex)
        KatanaSysEx.parse(sysex)?.let { onIncoming?.invoke(it) }
    }

    fun setParam(param: KatanaParam, value: Int): Boolean {
        val clamped = value.coerceIn(param.min, param.max)
        val msg = KatanaSysEx.buildSet(param.address, clamped)
        return sendSysEx(msg)
    }

    /** Ask the amp for the live value(s) of one parameter. */
    fun queryParam(param: KatanaParam, size: Int = 1): Boolean {
        val msg = KatanaSysEx.buildQuery(param.address, size)
        return sendSysEx(msg)
    }

    /** Read the temp-patch block; answers arrive as inbound DT1 messages. */
    fun readTempPatch(): Boolean {
        val msg = KatanaSysEx.buildQuery(
            KatanaParams.TEMP_PATCH_BASE,
            KatanaParams.TEMP_PATCH_READ_SIZE,
        )
        return sendSysEx(msg)
    }

    /** Read an arbitrary block (used by the reverse-engineering / diff screen). */
    fun readBlock(address: IntArray, size: Int): Boolean =
        sendSysEx(KatanaSysEx.buildQuery(address, size))

    /** Switch channel/preset via Program Change (channel 0 by convention). */
    fun selectProgram(program: Int): Boolean {
        val packets = UsbMidiPacketizer.encodeProgramChange(0, program)
        onTraffic?.invoke("TX", byteArrayOf((0xC0).toByte(), (program and 0x7F).toByte()))
        return connection.sendPackets(packets)
    }

    fun sendControlChange(controller: Int, value: Int): Boolean {
        val packets = UsbMidiPacketizer.encodeControlChange(0, controller, value)
        return connection.sendPackets(packets)
    }

    /** Send a fully-formed raw SysEx frame (from the console). */
    fun sendSysEx(sysex: ByteArray): Boolean {
        onTraffic?.invoke("TX", sysex)
        val packets = UsbMidiPacketizer.encodeSysEx(sysex)
        return connection.sendPackets(packets)
    }
}

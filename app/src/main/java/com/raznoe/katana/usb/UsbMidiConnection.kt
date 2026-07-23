package com.raznoe.katana.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Low-level USB connection to a Roland/BOSS device presenting a MIDI-carrying
 * bulk interface (the Katana). Handles interface/endpoint discovery, sending
 * raw MIDI, and a background read loop that reassembles inbound SysEx.
 *
 * This uses the Android USB Host API directly rather than android.media.midi,
 * because BOSS devices are not always enumerated as standard MIDI-class devices
 * on every Android build — claiming the bulk interface ourselves is robust.
 */
class UsbMidiConnection(
    private val manager: UsbManager,
    val device: UsbDevice,
) {
    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var endpointOut: UsbEndpoint? = null
    private var endpointIn: UsbEndpoint? = null

    @Volatile private var running = false
    private var readerThread: Thread? = null
    private lateinit var reassembler: UsbMidiPacketizer.SysExReassembler

    val isOpen: Boolean get() = connection != null

    /**
     * Open the device and start the reader loop.
     * @param onSysEx invoked (on the reader thread) for each inbound SysEx frame.
     * @return null on success, or a human-readable error string.
     */
    fun open(onSysEx: (ByteArray) -> Unit): String? {
        val (usbInterface, outEp, inEp) = findMidiInterface()
            ?: return "No bulk IN/OUT interface found on this device"

        val conn = manager.openDevice(device)
            ?: return "openDevice failed (permission not granted?)"

        if (!conn.claimInterface(usbInterface, true)) {
            conn.close()
            return "claimInterface failed (device busy / no permission)"
        }

        connection = conn
        iface = usbInterface
        endpointOut = outEp
        endpointIn = inEp
        reassembler = UsbMidiPacketizer.SysExReassembler(onSysEx)

        startReader()
        return null
    }

    /** Send an already USB-MIDI-packetized buffer. */
    fun sendPackets(packets: ByteArray): Boolean {
        val conn = connection ?: return false
        val ep = endpointOut ?: return false
        val max = ep.maxPacketSize.coerceAtLeast(4)
        var offset = 0
        while (offset < packets.size) {
            val len = minOf(max, packets.size - offset)
            val chunk = if (offset == 0 && len == packets.size) packets
            else packets.copyOfRange(offset, offset + len)
            val sent = conn.bulkTransfer(ep, chunk, len, WRITE_TIMEOUT_MS)
            if (sent < 0) {
                Log.w(TAG, "bulkTransfer(out) failed at offset $offset")
                return false
            }
            offset += len
        }
        return true
    }

    fun close() {
        running = false
        readerThread?.interrupt()
        readerThread = null
        val conn = connection
        val i = iface
        if (conn != null && i != null) {
            runCatching { conn.releaseInterface(i) }
            runCatching { conn.close() }
        }
        connection = null
        iface = null
        endpointOut = null
        endpointIn = null
    }

    private fun startReader() {
        running = true
        val ep = endpointIn ?: return
        val conn = connection ?: return
        val bufSize = ep.maxPacketSize.coerceAtLeast(64)
        readerThread = Thread({
            val buffer = ByteArray(bufSize)
            while (running) {
                val read = conn.bulkTransfer(ep, buffer, buffer.size, READ_TIMEOUT_MS)
                if (read > 0) {
                    reassembler.push(buffer, read)
                } else if (read < 0) {
                    // timeout is expected and returns a negative value; just loop
                    if (!running) break
                }
            }
        }, "katana-usb-reader").also { it.isDaemon = true; it.start() }
    }

    /**
     * Pick an interface that carries MIDI. Preference order:
     *   1. USB Audio class (0x01) / MIDIStreaming subclass (0x03)
     *   2. any interface exposing both a bulk IN and a bulk OUT endpoint
     */
    private fun findMidiInterface(): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        var fallback: Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? = null
        for (n in 0 until device.interfaceCount) {
            val i = device.getInterface(n)
            var bulkOut: UsbEndpoint? = null
            var bulkIn: UsbEndpoint? = null
            for (e in 0 until i.endpointCount) {
                val ep = i.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut = ep
                else bulkIn = ep
            }
            if (bulkOut != null && bulkIn != null) {
                val triple = Triple(i, bulkOut, bulkIn)
                val isMidi = i.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    i.interfaceSubclass == MIDI_STREAMING_SUBCLASS
                if (isMidi) return triple
                if (fallback == null) fallback = triple
            }
        }
        return fallback
    }

    companion object {
        private const val TAG = "KatanaUsb"
        private const val MIDI_STREAMING_SUBCLASS = 0x03
        private const val WRITE_TIMEOUT_MS = 250
        private const val READ_TIMEOUT_MS = 100
    }
}

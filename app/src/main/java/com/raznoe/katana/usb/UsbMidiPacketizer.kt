package com.raznoe.katana.usb

/**
 * Converts between raw MIDI byte streams and USB-MIDI 1.0 (USB Audio Class)
 * 32-bit event packets, which is what travels over the Katana's bulk pipes.
 *
 * Each packet is 4 bytes:  [ (cable<<4) | CIN,  midi0, midi1, midi2 ]
 *
 * Code Index Numbers (CIN) used here:
 *   0x4  SysEx start / continue      (3 data bytes)
 *   0x5  SysEx end, 1 byte / 1-byte system common
 *   0x6  SysEx end, 2 bytes
 *   0x7  SysEx end, 3 bytes
 *   0xC  Program Change              (2 data bytes)
 *   0xB  Control Change              (3 data bytes)
 *
 * Cable number is always 0 for the Katana.
 */
object UsbMidiPacketizer {

    private const val CABLE = 0

    /** Encode a complete SysEx message (F0 … F7) into USB-MIDI packets. */
    fun encodeSysEx(sysex: ByteArray): ByteArray {
        val out = ArrayList<Byte>(sysex.size / 3 * 4 + 4)
        var i = 0
        val n = sysex.size
        while (i < n) {
            val remaining = n - i
            when {
                remaining > 3 -> {
                    out.addPacket(0x4, sysex[i], sysex[i + 1], sysex[i + 2])
                    i += 3
                }
                remaining == 3 -> {
                    out.addPacket(0x7, sysex[i], sysex[i + 1], sysex[i + 2]); i += 3
                }
                remaining == 2 -> {
                    out.addPacket(0x6, sysex[i], sysex[i + 1], 0); i += 2
                }
                else -> {
                    out.addPacket(0x5, sysex[i], 0, 0); i += 1
                }
            }
        }
        return out.toByteArray()
    }

    /** Encode a Program Change on [channel] (0-15). */
    fun encodeProgramChange(channel: Int, program: Int): ByteArray {
        val list = ArrayList<Byte>(4)
        list.addPacket(0xC, (0xC0 or (channel and 0x0F)).toByte(), (program and 0x7F).toByte(), 0)
        return list.toByteArray()
    }

    /** Encode a Control Change on [channel] (0-15). */
    fun encodeControlChange(channel: Int, controller: Int, value: Int): ByteArray {
        val list = ArrayList<Byte>(4)
        list.addPacket(
            0xB,
            (0xB0 or (channel and 0x0F)).toByte(),
            (controller and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
        return list.toByteArray()
    }

    private fun ArrayList<Byte>.addPacket(cin: Int, b0: Byte, b1: Byte, b2: Byte) {
        add((((CABLE shl 4) or cin) and 0xFF).toByte())
        add(b0); add(b1); add(b2)
    }

    /**
     * Reassembles inbound USB-MIDI packets into complete SysEx frames.
     * Non-SysEx traffic is ignored. Call [push] with each raw bulk-read buffer;
     * [onSysEx] fires once per complete F0…F7 message.
     */
    class SysExReassembler(private val onSysEx: (ByteArray) -> Unit) {
        private val current = ArrayList<Byte>(256)
        private val MAX_SYSEX = 8192

        fun push(buffer: ByteArray, length: Int) {
            var i = 0
            while (i + 4 <= length) {
                val cin = buffer[i].toInt() and 0x0F
                val d0 = buffer[i + 1]
                val d1 = buffer[i + 2]
                val d2 = buffer[i + 3]
                when (cin) {
                    0x4 -> { current.add(d0); current.add(d1); current.add(d2) }
                    0x5 -> { current.add(d0); flush() }
                    0x6 -> { current.add(d0); current.add(d1); flush() }
                    0x7 -> { current.add(d0); current.add(d1); current.add(d2); flush() }
                    // Other CINs (channel voice etc.) are not needed here.
                    else -> { /* ignore */ }
                }
                // A corrupt/glitchy stream (a SysEx start with no end byte) would
                // otherwise grow `current` without bound. Katana frames are small;
                // drop anything absurdly long.
                if (current.size > MAX_SYSEX) current.clear()
                i += 4
            }
        }

        private fun flush() {
            if (current.isNotEmpty()) {
                onSysEx(current.toByteArray())
                current.clear()
            }
        }

        fun reset() = current.clear()
    }
}

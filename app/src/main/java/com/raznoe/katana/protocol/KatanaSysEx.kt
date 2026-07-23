package com.raznoe.katana.protocol

/**
 * Builds and parses BOSS Katana MIDI System-Exclusive messages.
 *
 * The Katana speaks the Roland "DT1 / RQ1" address-mapped SysEx dialect,
 * reverse-engineered by the community (see snhirsch/katana-midi-bridge).
 *
 * Frame layout:
 *
 *   F0 41 00 00 00 00 33 <cmd> <A0 A1 A2 A3> <data...> <sum> F7
 *   │  │  └──────┬─────┘ │     └─────┬─────┘ └───┬────┘ └─┬─┘ │
 *   │  │      device id  │        address       payload   │  end
 *   │  Roland          model 0x33 (Katana)                Roland checksum
 *   SysEx start        cmd = 0x12 (DT1/set) or 0x11 (RQ1/query)
 *
 * The Roland checksum is computed over the address bytes plus the payload
 * (query "size" counts as payload for RQ1): sum them, take mod 128, and the
 * checksum is (128 - sum) & 0x7F.
 *
 * NOTE ON GEN 3: the framing (header, commands, checksum) is identical across
 * Katana generations. What differs between Gen1/Gen2/Gen3 is the *address map*
 * (see [KatanaParams]). Keep the transport generic and treat addresses as data.
 */
object KatanaSysEx {

    const val SYSEX_START = 0xF0
    const val SYSEX_END = 0xF7

    /** F0 41 00 00 00 00 33 — everything up to and including the model id. */
    val HEADER = intArrayOf(0xF0, 0x41, 0x00, 0x00, 0x00, 0x00, 0x33)

    const val CMD_DT1 = 0x12 // "Data Set 1"  — write parameter(s)
    const val CMD_RQ1 = 0x11 // "Request 1"   — read parameter(s)

    /** Roland checksum over [bytes] (address + payload). */
    fun checksum(bytes: IntArray): Int {
        var sum = 0
        for (b in bytes) sum = (sum + (b and 0x7F)) and 0x7F
        return (128 - sum) and 0x7F
    }

    /**
     * Build a DT1 (write) message.
     * @param address 4-byte parameter address.
     * @param data one or more 7-bit data bytes to write at [address].
     */
    fun buildSet(address: IntArray, data: IntArray): ByteArray {
        require(address.size == 4) { "Katana address must be 4 bytes" }
        val body = address + data
        return frame(CMD_DT1, body)
    }

    /** Convenience: write a single byte value. */
    fun buildSet(address: IntArray, value: Int): ByteArray =
        buildSet(address, intArrayOf(value and 0x7F))

    /**
     * Build an RQ1 (read) message requesting [size] bytes starting at [address].
     * The amp answers with a DT1 message carrying the data (see [parse]).
     */
    fun buildQuery(address: IntArray, size: Int): ByteArray {
        require(address.size == 4) { "Katana address must be 4 bytes" }
        // size is expressed as a 4-byte big-endian 7-bit value
        val sizeBytes = intArrayOf(
            (size shr 21) and 0x7F,
            (size shr 14) and 0x7F,
            (size shr 7) and 0x7F,
            size and 0x7F,
        )
        return frame(CMD_RQ1, address + sizeBytes)
    }

    private fun frame(cmd: Int, body: IntArray): ByteArray {
        val sum = checksum(body)
        val ints = HEADER + intArrayOf(cmd) + body + intArrayOf(sum, SYSEX_END)
        return ByteArray(ints.size) { (ints[it] and 0xFF).toByte() }
    }

    /** A parsed inbound DT1 message: the amp reporting data at [address]. */
    data class Incoming(val address: IntArray, val data: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Incoming && address.contentEquals(other.address) &&
                data.contentEquals(other.data)

        override fun hashCode(): Int =
            address.contentHashCode() * 31 + data.contentHashCode()
    }

    /**
     * Parse a raw SysEx frame the amp sent back. Returns null if it is not a
     * well-formed Katana DT1 message. The checksum is validated.
     */
    fun parse(raw: ByteArray): Incoming? {
        val b = IntArray(raw.size) { raw[it].toInt() and 0xFF }
        if (b.size < HEADER.size + 1 + 4 + 1 + 1) return null
        if (b.first() != SYSEX_START || b.last() != SYSEX_END) return null
        for (i in HEADER.indices) if (b[i] != HEADER[i]) return null
        if (b[HEADER.size] != CMD_DT1) return null

        val addrStart = HEADER.size + 1
        val address = b.copyOfRange(addrStart, addrStart + 4)
        // data runs from after the address up to (but not including) checksum+F7
        val data = b.copyOfRange(addrStart + 4, b.size - 2)
        val expected = checksum(address + data)
        if (expected != b[b.size - 2]) return null
        return Incoming(address, data)
    }

    /** Hex helper for logs / the raw console. */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** Parse a "F0 41 .. F7" hex string (spaces/commas/0x optional) to bytes. */
    fun fromHex(text: String): ByteArray {
        val tokens = text
            .replace("0x", " ", ignoreCase = true)
            .replace(",", " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        return ByteArray(tokens.size) { tokens[it].toInt(16).toByte() }
    }
}

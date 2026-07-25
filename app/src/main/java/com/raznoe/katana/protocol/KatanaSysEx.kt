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
    const val ROLAND_ID = 0x41

    /** Amp generations, distinguished by their Roland header prefix + address map. */
    enum class Gen { MKII, GEN3, GO }

    /**
     * The bytes that follow the Roland manufacturer id (0x41) in the header:
     * device id + model id. Decoded from the Katana Librarian app:
     *   MkII : <dev> 00 00 00 33   (5 bytes)
     *   Gen3 : <dev> 01 05 07      (4 bytes)
     *   GO   : <dev> 01 05 0D      (4 bytes)
     * Learned at runtime from the Identity Reply (see [adoptFromIdentity]).
     */
    @Volatile private var prefixTail = intArrayOf(0x00, 0x00, 0x00, 0x00, 0x33)

    @Volatile var generation: Gen = Gen.MKII
        private set

    val modelId: Int get() = prefixTail.last()

    fun setModelId(id: Int) { prefixTail = intArrayOf(0x00, 0x00, 0x00, 0x00, id and 0x7F) }

    /** Force the full prefix bytes (probing). */
    fun setPrefix(tail: IntArray) { if (tail.isNotEmpty()) prefixTail = tail.copyOf() }

    fun resetProfile() {
        prefixTail = intArrayOf(0x00, 0x00, 0x00, 0x00, 0x33); generation = Gen.MKII
    }

    /** Full header: F0 41 <device id .. model id>. */
    fun header(): IntArray = intArrayOf(0xF0, ROLAND_ID) + prefixTail

    fun headerHex(): String = header().joinToString(" ") { "%02X".format(it and 0xFF) }

    /** Index of the command byte given the current prefix length. */
    private fun cmdIndex(): Int = 2 + prefixTail.size

    /**
     * Decode the Roland header prefix from a Universal Identity Reply
     * (F0 7E <dev> 06 02 41 <family> ...). This is exactly how the official
     * Katana Librarian selects the dialect. Returns true if a Katana was
     * recognised and the prefix/generation was set.
     */
    fun adoptFromIdentity(raw: ByteArray): Boolean {
        val b = IntArray(raw.size) { raw[it].toInt() and 0xFF }
        if (b.size < 11) return false
        if (b[1] != 0x7E || b[3] != 0x06 || b[4] != 0x02 || b[5] != ROLAND_ID) return false
        val dev = b[2]
        when (b[6]) {
            0x33 -> { prefixTail = intArrayOf(dev, 0x00, 0x00, 0x00, 0x33); generation = Gen.MKII }
            0x07 -> { prefixTail = intArrayOf(dev, 0x01, 0x05, 0x07); generation = Gen.GEN3 }
            0x0D -> { prefixTail = intArrayOf(dev, 0x01, 0x05, 0x0D); generation = Gen.GO }
            else -> return false
        }
        return true
    }

    /**
     * Adopt the full Roland prefix from an inbound DT1 frame (fallback learner).
     * Uses the current prefix length to locate the command byte.
     */
    fun adoptHeaderFrom(raw: ByteArray): Boolean {
        val ci = cmdIndex()
        if (raw.size < ci + 2) return false
        val b = IntArray(raw.size) { raw[it].toInt() and 0xFF }
        if (b[0] != SYSEX_START || b[1] != ROLAND_ID) return false
        if (b[ci] != CMD_DT1) return false
        val tail = b.copyOfRange(2, ci)
        if (tail.contentEquals(prefixTail)) return false
        prefixTail = tail
        return true
    }

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
        // Size is 4 big-endian bytes, exactly as the official app encodes it
        // (verified against Katana Librarian's s1(): i6>>>24, >>>16, >>>8, i6).
        val sizeBytes = intArrayOf(
            (size shr 24) and 0x7F,
            (size shr 16) and 0x7F,
            (size shr 8) and 0x7F,
            size and 0x7F,
        )
        return frame(CMD_RQ1, address + sizeBytes)
    }

    /**
     * EXPERIMENTAL "new-gen" amp SET, in the Katana:GO / Gen-3-era dialect
     * (from Pyrrhu5/boss-katana-go): F0 41 10 01 05 0D 12 20 00 <knob> <v> <v>
     * <checksum> F7. knob: gain=0x00, volume=0x01, bass=0x03, middle=0x04,
     * treble=0x05, presence=0x0A, amp=0x0C. Used only by the Console test.
     */
    fun buildNewGenAmpSet(knob: Int, value: Int): ByteArray {
        val data = intArrayOf(0x20, 0x00, knob and 0x7F, value and 0x7F, value and 0x7F)
        val sum = checksum(data)
        val ints = intArrayOf(0xF0, 0x41, 0x10, 0x01, 0x05, 0x0D, 0x12) + data +
            intArrayOf(sum, SYSEX_END)
        return ByteArray(ints.size) { (ints[it] and 0xFF).toByte() }
    }

    private fun frame(cmd: Int, body: IntArray): ByteArray {
        val sum = checksum(body)
        val ints = header() + intArrayOf(cmd) + body + intArrayOf(sum, SYSEX_END)
        return ByteArray(ints.size) { (ints[it] and 0xFF).toByte() }
    }

    // --- Handshake / editor mode ---------------------------------------------

    /** DT1 write to the editor-mode address (7F 00 00 01). */
    val EDIT_MODE_ADDR = intArrayOf(0x7F, 0x00, 0x00, 0x01)

    fun editorMode(on: Boolean): ByteArray = buildSet(EDIT_MODE_ADDR, if (on) 1 else 0)

    /**
     * Universal MIDI Identity Request. The amp replies with an Identity Reply
     * whose Roland family/model bytes we use to auto-select the device profile
     * (Gen1/MkII vs Gen 3). See [parseIdentityReply].
     */
    fun identityRequest(): ByteArray =
        byteArrayOf(0xF0.toByte(), 0x7E, 0x7F, 0x06, 0x01, 0xF7.toByte())

    /**
     * The "controller announce" handshake that Boss Tone Studio / the MS3
     * controllers send right after enumeration (an identity-reply-shaped frame).
     * Sending it — twice, since the first is often dropped — before the
     * editor-mode write makes the amp start streaming.
     */
    fun announceHandshake(): ByteArray = byteArrayOf(
        0xF0.toByte(), 0x7E, 0x00, 0x06, 0x02, 0x41, 0x33,
        0x03, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0xF7.toByte(),
    )

    /**
     * If [raw] is a Universal Identity Reply (F0 7E <dev> 06 02 <manufacturer>
     * ...), return the manufacturer + family/model bytes for logging/profile
     * selection. Returns null otherwise.
     */
    fun parseIdentityReply(raw: ByteArray): IntArray? {
        val b = IntArray(raw.size) { raw[it].toInt() and 0xFF }
        if (b.size < 6) return null
        if (b[0] != 0xF0 || b[1] != 0x7E) return null
        // b[2] = device id, then 06 02 = identity reply
        if (b.getOrNull(3) != 0x06 || b.getOrNull(4) != 0x02) return null
        return b.copyOfRange(5, b.size - 1) // manufacturer + family/model/version
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
        // F0 41 <prefix> 12 <addr4> <data..> <sum> F7. The command byte position
        // depends on the current prefix length (MkII=5, Gen3/GO=4).
        val ci = cmdIndex()
        val addrStart = ci + 1
        val b = IntArray(raw.size) { raw[it].toInt() and 0xFF }
        if (b.size < addrStart + 4 + 1 + 1) return null
        if (b.first() != SYSEX_START || b.last() != SYSEX_END) return null
        if (b[1] != ROLAND_ID) return null
        if (b[ci] != CMD_DT1) return null
        val address = b.copyOfRange(addrStart, addrStart + 4)
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

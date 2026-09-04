package com.raznoe.katana.model

import kotlinx.serialization.Serializable

/**
 * A tone captured from the amp as raw bytes, keyed by the address they came
 * from — not as named parameters.
 *
 * This exists because the Gen 3 address map is only partly confirmed. A named
 * preset can only ever be as right as our guess about which address means
 * "Bass"; a raw capture does not need that guess at all. We read whatever the
 * amp reports, store the bytes, and write those same bytes back to those same
 * addresses. Recall is then faithful by construction, however wrong our labels
 * for the individual bytes may be.
 *
 * So the way to get a Librarian tone into this app is: set it up in Librarian
 * (or on the amp itself), press "снять тон", and it comes back identically.
 */
@Serializable
data class RawPatch(
    val name: String,
    /** Address ("20 00 06 00") -> the bytes the amp reported there. */
    val blocks: Map<String, List<Int>>,
    /** Free-text note, e.g. where the tone came from. */
    val note: String = "",
) {
    val byteCount: Int get() = blocks.values.sumOf { it.size }

    companion object {
        /**
         * Keep only the amp's live edit area (20 00 xx xx).
         *
         * The read sequence also touches system and patch-metadata blocks —
         * identity, the 0x2010.. sections, 0x7F00.. — and writing those back is
         * at best pointless and at worst a way to confuse the amp. The live
         * area is what carries the tone, and it is the only thing we replay.
         */
        fun liveAreaOnly(blocks: Map<String, List<Int>>): Map<String, List<Int>> =
            blocks.filterKeys { key ->
                val parts = key.trim().split(Regex("\\s+"))
                parts.size == 4 &&
                    runCatching {
                        parts[0].toInt(16) == 0x20 && parts[1].toInt(16) == 0x00
                    }.getOrDefault(false)
            }

        /**
         * Write order: lowest address first.
         *
         * The FX-BOX selector block (20 00 04 xx) decides which physical slot
         * each effect occupies, so it has to land before the slot contents it
         * refers to — and sorting by address puts 04 ahead of 06 and the rest
         * for free.
         */
        fun writeOrder(blocks: Map<String, List<Int>>): List<Pair<String, List<Int>>> =
            blocks.entries
                .filter { it.value.isNotEmpty() }
                .sortedBy { it.key }
                .map { it.key to it.value }
    }
}

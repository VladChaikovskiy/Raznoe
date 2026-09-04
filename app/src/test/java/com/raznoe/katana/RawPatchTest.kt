package com.raznoe.katana

import com.raznoe.katana.model.RawPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A captured tone is replayed byte for byte, so what gets captured and the
 * order it is written back in are the whole correctness story.
 */
class RawPatchTest {

    private val read = mapOf(
        "20 00 06 00" to listOf(1, 60, 80, 50, 50, 50, 50),  // amp block
        "20 00 04 00" to listOf(0, 0, 0, 0, 0),              // FX-BOX selectors
        "20 00 58 00" to listOf(1, 30, 45),                  // noise suppressor
        "7F 00 00 00" to listOf(1),                          // system probe
        "20 10 00 00" to listOf(9, 9, 9),                    // patch metadata
        "00 00 00 00" to listOf(0, 0, 0, 0),                 // identity area
        "10 00 24 00" to listOf(3),
    )

    /** Only the live edit area is worth replaying; the rest is not ours. */
    @Test fun liveAreaOnly_keepsTheAmpAreaAndDropsSystemBlocks() {
        val live = RawPatch.liveAreaOnly(read)
        assertEquals(setOf("20 00 06 00", "20 00 04 00", "20 00 58 00"), live.keys)
    }

    @Test fun liveAreaOnly_ignoresMalformedKeys() {
        val live = RawPatch.liveAreaOnly(
            mapOf("20 00 06" to listOf(1), "zz zz zz zz" to listOf(1), "20 00 06 00" to listOf(1)),
        )
        assertEquals(setOf("20 00 06 00"), live.keys)
    }

    /**
     * The FX-BOX selector block decides which slot each effect occupies, so it
     * has to be written before the slot contents it points at. Sorting by
     * address gets that for free: 04 comes before 06 and the rest.
     */
    @Test fun writeOrder_putsTheSelectorBlockFirst() {
        val order = RawPatch.writeOrder(RawPatch.liveAreaOnly(read)).map { it.first }
        assertEquals("20 00 04 00", order.first())
        assertEquals(listOf("20 00 04 00", "20 00 06 00", "20 00 58 00"), order)
    }

    @Test fun writeOrder_skipsEmptyBlocks() {
        val order = RawPatch.writeOrder(
            mapOf("20 00 06 00" to emptyList(), "20 00 04 00" to listOf(1)),
        )
        assertEquals(1, order.size)
        assertEquals("20 00 04 00", order.first().first)
    }

    @Test fun byteCount_countsEveryCapturedByte() {
        val patch = RawPatch("Тон", RawPatch.liveAreaOnly(read))
        assertEquals(7 + 5 + 3, patch.byteCount)
        assertTrue(patch.blocks.isNotEmpty())
    }
}

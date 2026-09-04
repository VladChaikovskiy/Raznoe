package com.raznoe.katana

import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.ParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KatanaParams.sanitize] is the gate every value passes through before it
 * reaches the amp, and [KatanaParams.neutral] is what fills in a parameter a
 * patch does not mention. Both have to be right or a preset lands wrong.
 */
class ParamSanitiseTest {

    @Test fun enumValues_snapToTheNearestImplementedCode() {
        // Delay implements 0, 6, 7, 8, 9 — there is no type 10.
        assertEquals(9, KatanaParams.sanitize(KatanaParams.DELAY_TYPE, 10))
        assertEquals(0, KatanaParams.sanitize(KatanaParams.DELAY_TYPE, 0))
        assertEquals(8, KatanaParams.sanitize(KatanaParams.DELAY_TYPE, 8))
        // Booster skips 7.
        assertTrue(KatanaParams.sanitize(KatanaParams.BOOST_TYPE, 7) in listOf(6, 8))
        // Way out of range still comes back as something real.
        assertTrue(KatanaParams.isValid(KatanaParams.MOD_TYPE, KatanaParams.sanitize(KatanaParams.MOD_TYPE, 99)))
    }

    @Test fun continuousValues_areClampedToTheirRange() {
        assertEquals(100, KatanaParams.sanitize(KatanaParams.GAIN, 500))
        assertEquals(0, KatanaParams.sanitize(KatanaParams.GAIN, -20))
        assertEquals(2000, KatanaParams.sanitize(KatanaParams.DELAY_TIME, 9999))
        assertEquals(1, KatanaParams.sanitize(KatanaParams.DELAY_TIME, 0))
    }

    @Test fun toggles_areZeroOrOne() {
        assertEquals(1, KatanaParams.sanitize(KatanaParams.BOOST_SW, 7))
        assertEquals(0, KatanaParams.sanitize(KatanaParams.BOOST_SW, 0))
    }

    /** Every parameter needs a usable neutral, and it must itself be legal. */
    @Test fun neutral_isDefinedAndLegalForEveryParam() {
        var bad: String? = null
        for (p in KatanaParams.ALL) {
            val n = KatanaParams.neutral(p)
            if (!KatanaParams.isValid(p, n)) bad = "${p.id}=$n"
            if (KatanaParams.NEUTRAL[p.id] == null) bad = "${p.id} нет в NEUTRAL"
        }
        assertNull(bad)
    }

    /** The neutral tone must not be silent: dry signal through, level up. */
    @Test fun neutral_leavesTheGuitarAudible() {
        assertEquals(100, KatanaParams.NEUTRAL["delay_direct"])
        assertEquals(100, KatanaParams.NEUTRAL["reverb_direct"])
        assertTrue((KatanaParams.NEUTRAL["volume"] ?: 0) >= 50)
    }

    /**
     * Neutral has to mean FLAT. A default high cut quietly dulls every preset
     * that does not specify one, which is how the clean tones lost their top.
     */
    @Test fun neutral_filtersAreFlat() {
        assertEquals(14, KatanaParams.NEUTRAL["delay_hc"])  // Flat
        assertEquals(14, KatanaParams.NEUTRAL["reverb_hc"]) // Flat
        assertEquals(0, KatanaParams.NEUTRAL["reverb_lc"])  // Flat
    }

    /** Including the gate: it belongs on distorted tones, not by default. */
    @Test fun neutral_leavesEveryBlockOff() {
        var bad: String? = null
        for (p in KatanaParams.ALL) {
            if (p.kind != ParamKind.TOGGLE) continue
            if (KatanaParams.neutral(p) != 0) bad = p.id
        }
        assertNull("block on by default", bad)
    }
}

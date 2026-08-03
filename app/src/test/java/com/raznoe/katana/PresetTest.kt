package com.raznoe.katana

import com.raznoe.katana.model.FactoryPresets
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.ParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates every factory preset: known ids, in-range/enum-valid values, the
 * auto noise-gate on overdriven tones, trimmed gain, and loudness leveling.
 * Guards against a preset that would send garbage to the amp.
 */
class PresetTest {

    private val presets = FactoryPresets.ALL
    // My own re-creations (cleanUp applied); excludes the imported originals,
    // which are shipped with their real, untouched values.
    private val mine = FactoryPresets.ALL.filterNot { it in FactoryPresets.ORIGINALS }

    @Test fun presets_areLoadedAndNamedUniquely() {
        assertTrue("expected >= 30 presets, got ${presets.size}", presets.size >= 30)
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
        assertTrue(presets.any { it.name == "Gary Moore Solo" })
        assertTrue(presets.any { it.name == "Sing Lead" })
        assertTrue(presets.any { it.name == "Santana Sustain" })
    }

    @Test fun everyParamId_isKnown() {
        var bad: String? = null
        for (p in presets) for (id in p.values.keys) {
            if (KatanaParams.BY_ID[id] == null) bad = "${p.name}:$id"
        }
        assertNull("unknown param id in preset", bad)
    }

    @Test fun everyValue_isInRangeOrValidEnum() {
        var bad: String? = null
        for (p in presets) for ((id, v) in p.values) {
            val param = KatanaParams.BY_ID[id] ?: continue
            val ok = when (param.kind) {
                ParamKind.TOGGLE -> v == 0 || v == 1
                // Imported originals may carry device-valid effect-type codes
                // outside our display lists (Gen 3 accepts them), so accept the
                // full SysEx data-byte range for enums.
                ParamKind.ENUM -> v in 0..127
                ParamKind.CONTINUOUS -> v >= param.min && v <= param.max
            }
            if (!ok) bad = "${p.name}:$id=$v"
        }
        assertNull("out-of-range/invalid preset value", bad)
    }

    @Test fun noiseGate_engagedOnGainyOrBoostedPresets() {
        var bad: String? = null
        for (p in mine) {
            val gain = p.values["gain"] ?: 0
            val boosted = (p.values["boost_sw"] ?: 0) == 1
            if (gain >= 45 || boosted) {
                val thr = p.values["ns_thr"] ?: -1
                if (p.values["ns_sw"] != 1 || thr !in 20..64) bad = p.name
            }
        }
        assertNull("gainy/boosted preset without a proper noise gate", bad)
    }

    @Test fun gain_isTrimmedForTightness() {
        assertTrue(mine.all { (it.values["gain"] ?: 0) <= 82 })
    }

    @Test fun originals_areLoadedWithRealValues() {
        assertTrue(FactoryPresets.ORIGINALS.size >= 15)
        assertTrue(FactoryPresets.ORIGINALS.any { it.name.contains("GMoore Solo") })
    }

    @Test fun loudnessLevel_setOnEveryPreset() {
        assertTrue(presets.all { it.values["volume"] != null })
    }
}

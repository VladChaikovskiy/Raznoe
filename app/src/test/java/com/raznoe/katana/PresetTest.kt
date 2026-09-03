package com.raznoe.katana

import com.raznoe.katana.model.FactoryPresets
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.ParamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates every factory preset. These are the guarantees the amp relies on:
 * a preset is complete (so nothing is inherited from the previous tone), every
 * value is a code the amp implements, and the tone is gated and level.
 */
class PresetTest {

    private val presets = FactoryPresets.ALL

    @Test fun presets_areLoadedAndNamedUniquely() {
        assertTrue("expected >= 30 presets, got ${presets.size}", presets.size >= 30)
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
        assertTrue(presets.any { it.name == "Gary Moore Solo" })
        assertTrue(presets.any { it.name == "Sing Lead" })
        assertTrue(presets.any { it.name == "Santana Sustain" })
    }

    /**
     * The big one. Loading a preset used to send only the parameters it
     * mentioned, so the rest of the tone was whatever the previous preset left
     * — presets "didn't load" and leftover effects hummed underneath.
     */
    @Test fun everyPreset_coversEveryParameter() {
        val ids = KatanaParams.ALL.map { it.id }.toSet()
        var bad: String? = null
        for (p in presets) {
            val missing = ids - p.values.keys
            if (missing.isNotEmpty()) bad = "${p.name} не задаёт $missing"
        }
        assertNull(bad)
    }

    @Test fun everyParamId_isKnown() {
        var bad: String? = null
        for (p in presets) for (id in p.values.keys) {
            if (KatanaParams.BY_ID[id] == null) bad = "${p.name}:$id"
        }
        assertNull("unknown param id in preset", bad)
    }

    /** Enum lists have gaps; an unimplemented code leaves a block undefined. */
    @Test fun everyValue_isValidForItsParam() {
        var bad: String? = null
        for (p in presets) for ((id, v) in p.values) {
            val param = KatanaParams.BY_ID[id] ?: continue
            if (!KatanaParams.isValid(param, v)) bad = "${p.name}:$id=$v"
        }
        assertNull("out-of-range/invalid preset value", bad)
    }

    @Test fun noiseGate_engagedOnEveryPreset() {
        var bad: String? = null
        for (p in presets) {
            val thr = p.values["ns_thr"] ?: -1
            val rel = p.values["ns_rel"] ?: -1
            if (p.values["ns_sw"] != 1 || thr !in 22..62 || rel !in 35..70) {
                bad = "${p.name} (sw=${p.values["ns_sw"]} thr=$thr rel=$rel)"
            }
        }
        assertNull("preset without a proper noise gate", bad)
    }

    /** A hotter tone needs a firmer gate; the threshold has to track gain. */
    @Test fun gateThreshold_tracksGain() {
        val clean = presets.minByOrNull { it.values["gain"] ?: 0 }!!
        val hot = presets.maxByOrNull { it.values["gain"] ?: 0 }!!
        assertTrue(
            "clean=${clean.values["ns_thr"]} hot=${hot.values["ns_thr"]}",
            (hot.values["ns_thr"] ?: 0) >= (clean.values["ns_thr"] ?: 0),
        )
    }

    @Test fun gain_isCappedOnEveryPreset() {
        val worst = presets.maxByOrNull { it.values["gain"] ?: 0 }!!
        assertTrue("${worst.name} gain=${worst.values["gain"]}", (worst.values["gain"] ?: 0) <= 82)
    }

    /** Direct Mix at anything but full drops the dry guitar out of the mix. */
    @Test fun dryPath_isNeverMutedByDelayOrReverb() {
        var bad: String? = null
        for (p in presets) {
            if (p.values["delay_sw"] == 1 && p.values["delay_direct"] != 100) bad = "${p.name} delay"
            if (p.values["reverb_sw"] == 1 && p.values["reverb_direct"] != 100) bad = "${p.name} reverb"
        }
        assertNull("preset mutes the dry signal", bad)
    }

    /**
     * Only a block's type and on/off are implemented for Gen 3, so switching on
     * a type whose parameters we cannot set leaves the amp using leftovers —
     * the source of the stray background noise.
     */
    @Test fun modFx_onlyEnabledForTypesWeCanConfigure() {
        val safe = setOf(3, 4, 6, 9, 19, 20, 21, 22, 23, 26, 29, 31)
        var bad: String? = null
        for (p in presets) {
            if (p.values["mod_sw"] == 1 && p.values["mod_type"] !in safe) {
                bad = "${p.name} mod_type=${p.values["mod_type"]}"
            }
            if (p.values["fx_sw"] == 1 && p.values["fx_type"] !in safe) {
                bad = "${p.name} fx_type=${p.values["fx_type"]}"
            }
        }
        assertNull("Mod/FX enabled with an unsupported type", bad)
    }

    @Test fun loudnessLevel_setAndAudibleOnEveryPreset() {
        var bad: String? = null
        for (p in presets) {
            val v = p.values["volume"] ?: -1
            if (v !in 55..92) bad = "${p.name} volume=$v"
        }
        assertNull("preset with an unusable amp Level", bad)
    }

    @Test fun originals_areLoadedWithRealValues() {
        assertTrue(FactoryPresets.ORIGINALS.size >= 15)
        assertTrue(FactoryPresets.ORIGINALS.any { it.name.contains("GMoore Solo") })
        // The imported demos keep their voicing — EQ and effect choices are the
        // original ones, only the noise floor and loudness are normalised.
        val gm = FactoryPresets.ORIGINALS.first { it.name.contains("GMoore Solo") }
        assertEquals(4, gm.values["amp_type"])
        assertEquals(75, gm.values["middle"])
    }

    /**
     * Switches must be written after the values they gate, or a block comes in
     * with the previous preset's Level and barks.
     */
    @Test fun loadOrder_sendsSwitchesLast() {
        val order = FactoryPresets.loadOrder(presets.first().values)
        assertEquals(KatanaParams.ALL.size, order.size)
        val firstToggle = order.indexOfFirst { it.first.kind == ParamKind.TOGGLE }
        val lastKnob = order.indexOfLast { it.first.kind != ParamKind.TOGGLE }
        assertTrue("toggles start at $firstToggle, knobs end at $lastKnob", firstToggle > lastKnob)
    }

    @Test fun loadOrder_skipsParametersAPatchDoesNotHave() {
        val order = FactoryPresets.loadOrder(mapOf("gain" to 50, "unknown_id" to 1))
        assertEquals(1, order.size)
        assertEquals("gain", order.first().first.id)
    }
}

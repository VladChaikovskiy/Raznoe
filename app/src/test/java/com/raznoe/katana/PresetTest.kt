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
 * value is a code the amp implements, and an imported patch keeps the voicing
 * Librarian gave it.
 */
class PresetTest {

    private val presets = FactoryPresets.ALL

    /** My own re-creations; the imported demos keep Librarian's voicing. */
    private val mine = FactoryPresets.ALL.filterNot { it in FactoryPresets.ORIGINALS }

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

    /** Distortion is what hisses, so a Lead/Brown tone always ends up gated. */
    @Test fun distortedPresets_areGated() {
        var bad: String? = null
        for (p in presets) {
            val type = p.values["amp_type"] ?: 1
            if (type != 4 && type != 5) continue // Lead / Brown
            if (p.values["ns_sw"] != 1) bad = "${p.name}: шумодав выключен"
        }
        assertNull(bad)
    }

    /**
     * The gate WE add is light. A threshold in the 45-60 band chokes the decay
     * of every note, which is what made the presets sound muffled; an imported
     * patch may still carry a higher figure of its own, and that stays.
     */
    @Test fun theGateWeAdd_isLight() {
        var bad: String? = null
        for (p in mine) {
            val thr = p.values["ns_thr"] ?: 0
            if (thr > 45) bad = "${p.name}: порог $thr душит ноты"
        }
        assertNull(bad)
    }

    /**
     * The complaint that started this: a Clean channel is quiet already, and a
     * gate on it swallows the note instead of cleaning anything up. Our own
     * clean tones are left ungated unless they ask for it.
     */
    @Test fun myCleanPresets_areNotGatedHarder() {
        var bad: String? = null
        for (p in mine) {
            val type = p.values["amp_type"] ?: 1
            if (type != 0 && type != 1) continue // Acoustic / Clean
            val boostDrive = if (p.values["boost_sw"] == 1) p.values["boost_drive"] ?: 0 else 0
            if (boostDrive >= 30) continue // a hard-driven booster does hiss
            val thr = p.values["ns_thr"] ?: 0
            if (thr > 35) bad = "${p.name}: чистый тон с порогом $thr"
        }
        assertNull(bad)
    }

    /** An imported threshold is never lowered either — it is part of the patch. */
    @Test fun importedGateSettings_areNotLowered() {
        val jazz = FactoryPresets.ORIGINALS.first { it.name.contains("S-H Jazz") }
        assertEquals(45, jazz.values["ns_thr"])
        val soft = FactoryPresets.ORIGINALS.first { it.name.contains("Soft Lead") }
        assertEquals(49, soft.values["ns_thr"])
    }

    /** Two clean demos ship with the gate off in Librarian; it stays off. */
    @Test fun cleanOriginals_keepLibrariansOwnGateSetting() {
        val clean = FactoryPresets.ORIGINALS.first { it.name.contains("Katana Clean") }
        assertEquals(0, clean.values["ns_sw"])
        val demo = FactoryPresets.ORIGINALS.first { it.name.contains("Katana Demo 1") }
        assertEquals(0, demo.values["ns_sw"])
        val gmoore = FactoryPresets.ORIGINALS.first { it.name.contains("GMoore Clean") }
        assertEquals(1, gmoore.values["ns_sw"])
        assertEquals(35, gmoore.values["ns_thr"]) // Librarian's value, not ours
    }

    /**
     * An imported patch IS the tone; reshaping it is what made these sound
     * unlike Librarian. Gain, EQ and filters come through untouched, however
     * extreme they look.
     */
    @Test fun importedPresets_keepLibrariansValues() {
        val demo2 = FactoryPresets.ORIGINALS.first { it.name.contains("Katana Demo 2") }
        assertEquals(93, demo2.values["gain"])   // was capped to 82
        val fusion = FactoryPresets.ORIGINALS.first { it.name.contains("Fusion Lead") }
        assertEquals(100, fusion.values["presence"]) // was capped to 85
        val acdc = FactoryPresets.ORIGINALS.first { it.name.contains("ACDC") }
        assertEquals(100, acdc.values["boost_level"]) // was capped to 88
        assertEquals(80, acdc.values["treble"])
        val demo3 = FactoryPresets.ORIGINALS.first { it.name.contains("Katana Demo 3") }
        assertEquals(93, demo3.values["treble"]) // was capped to 88
    }

    /** Filters stay where the patch put them — a default high cut dulls everything. */
    @Test fun neutralFilters_areFlat() {
        val mine = presets.first { it.name == "Ambient Wash" }
        assertEquals(14, mine.values["reverb_hc"]) // Flat
        assertEquals(0, mine.values["reverb_lc"])  // Flat
        assertEquals(14, mine.values["delay_hc"])  // Flat
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

    /**
     * Every preset has to come back audible. An imported one keeps Librarian's
     * Level unless it is so low the patch recalls as silence, which reads as
     * "the preset didn't load".
     */
    @Test fun everyPreset_recallsAudible() {
        var bad: String? = null
        for (p in presets) {
            val v = p.values["volume"] ?: -1
            if (v < 55) bad = "${p.name} volume=$v"
        }
        assertNull("preset with an unusable amp Level", bad)
    }

    /** A raised Level is stated on screen rather than applied silently. */
    @Test fun aRaisedLevel_isDisclosedInTheNote() {
        val quiet = FactoryPresets.ORIGINALS.first { it.name.contains("Sweet Strat") }
        assertTrue("нет пометки в «${quiet.note}»", quiet.note.contains("Level поднят"))
        val untouched = FactoryPresets.ORIGINALS.first { it.name.contains("Pink Floyd") }
        assertTrue(!untouched.note.contains("Level поднят"))
    }

    /** A loud original Level is Librarian's choice and is left alone. */
    @Test fun aLoudOriginalLevel_isNotTrimmed() {
        val floyd = FactoryPresets.ORIGINALS.first { it.name.contains("Pink Floyd") }
        assertEquals(100, floyd.values["volume"])
    }

    @Test fun originals_areLoadedWithRealValues() {
        assertTrue(FactoryPresets.ORIGINALS.size >= 15)
        assertTrue(FactoryPresets.ORIGINALS.any { it.name.contains("GMoore Solo") })
        // The imported demos keep their voicing — EQ and effect choices are the
        // original ones, only the noise floor and loudness are normalised.
        val gm = FactoryPresets.ORIGINALS.first { it.name.contains("GMoore Solo") }
        // Lead, which is wire code 3 — it read 4 while the list carried a
        // sixth, non-existent "Pushed" entry at index 2.
        assertEquals(3, gm.values["amp_type"])
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

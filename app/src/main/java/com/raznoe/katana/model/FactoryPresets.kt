package com.raznoe.katana.model

import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.ParamKind

/**
 * Built-in presets, two groups:
 *  • ORIGINALS (★) — REAL demo patches decoded from the Katana Librarian's
 *    bundled .kat files (BOSS demos + JuCaNeRy "JNs" set). Exact gain/EQ/level/
 *    effect values. These are the genuine tones, not re-creations.
 *  • My own re-creations — starting-point tones I voiced (noise-gated, loudness
 *    leveled). Marked "Моя версия" in their note.
 *
 * Values reference [KatanaParams] ids and use the community MkII wire values.
 * Amp type codes: 0 Acoustic, 1 Clean, 2 Crunch, 3 Lead, 4 Brown — the five the
 * amp implements. The list used to carry a sixth, "Pushed", at index 2, so every
 * preset from Crunch upward selected the wrong character; the values here were
 * remapped when that was found.
 *
 * EVERY preset here goes through [finish], which guarantees three things that
 * the earlier hand-written maps did not:
 *
 *  1. **Completeness.** A preset carries a value for every parameter in
 *     [KatanaParams.ALL]. Loading one used to send only the parameters it
 *     happened to mention, so the rest of the tone was inherited from whatever
 *     was loaded before — presets "didn't really load", and stray effects from
 *     the previous patch kept making noise underneath.
 *  2. **Validity.** Every value is a code the amp implements (see
 *     [KatanaParams.sanitize]); enum lists have gaps and an unimplemented code
 *     leaves a block in an undefined state.
 *  3. **Only the corrections that make a patch work** (see [polish]): full dry
 *     signal through delay/reverb, Mod/FX left off unless their type is one we
 *     can configure, and a light gate on genuinely distorted tones.
 *
 * What it deliberately does NOT do is reshape a tone. Ceilings on gain,
 * presence and treble, forced high cuts in the wet path, and a gate on every
 * preset all used to live here, and between them they turned the clean presets
 * into a duller, choked version of themselves — nothing like the Librarian
 * original. An imported patch now keeps Librarian's own gain, EQ, levels and
 * filters; the only thing ever changed is a Level so low it recalls as silence.
 */
object FactoryPresets {

    /**
     * Mod/FX types that are safe to switch ON.
     *
     * Only a block's TYPE and on/off are implemented for Gen 3 — its per-type
     * parameters are not in our address map yet. Enabling a block whose type we
     * cannot configure means the amp applies whatever the previous patch left
     * in those parameters, and types like Wave Synth / Ring Mod / Humanizer /
     * Slicer / Slow Gear then generate exactly the "посторонний фон" this list
     * exists to prevent. Types here are either time-domain effects that sound
     * musical at any setting, or utility blocks that are quiet by nature.
     */
    private val SAFE_MOD_FX = setOf(
        3,  // Comp
        4,  // Limiter
        6,  // Graphic EQ
        9,  // Guitar Sim
        19, // Phaser
        20, // Flanger
        21, // Tremolo
        22, // Rotary
        23, // Uni-V
        26, // Vibrato
        29, // Chorus
        31, // AC Guitar Sim
    )

    // ---- Finishing pipeline ---------------------------------------------

    /**
     * Expand [raw] to the full parameter set and make every value legal.
     *
     * @param imported true for a patch decoded from the Katana Librarian. Its
     *   gain, EQ, levels and filters are then left exactly as Librarian has
     *   them — that IS the tone, and reshaping it is what made the clean
     *   presets come out dull and unlike the original.
     */
    private fun finish(
        name: String,
        note: String,
        raw: Map<String, Int>,
        imported: Boolean,
    ): Patch {
        val v = LinkedHashMap<String, Int>()
        // 1) Full, deterministic baseline — nothing is left to the previous patch.
        for (p in KatanaParams.ALL) v[p.id] = KatanaParams.neutral(p)
        // 2) The preset's own values on top (unknown ids are dropped, not sent).
        for ((id, value) in raw) if (KatanaParams.BY_ID[id] != null) v[id] = value
        // 3) Legal values, then the few corrections that are about the patch
        //    working at all rather than about how it is voiced.
        sanitize(v)
        polish(v)
        var extraNote = ""
        v["volume"] = if (imported) {
            // Only ever RAISE an unusably quiet Level. A dozen of the decoded
            // demos sit at 19-32, which recalls as near-silence and reads as
            // "the preset didn't load"; a Level is also the one thing here
            // that is about being heard rather than about the voicing. Never
            // lower one — that is Librarian's value — and say so on screen
            // when we did raise it, so the difference is not a secret.
            val original = v["volume"] ?: MIN_LEVEL
            if (original < MIN_LEVEL) extraNote = " · Level поднят $original→$MIN_LEVEL"
            original.coerceAtLeast(MIN_LEVEL)
        } else {
            normalizedLevel(v)
        }
        sanitize(v)
        return Patch(name = name, values = v, note = note + extraNote)
    }

    private fun sanitize(v: LinkedHashMap<String, Int>) {
        for ((id, value) in v.entries.toList()) {
            val p: KatanaParam = KatanaParams.BY_ID[id] ?: continue
            v[id] = KatanaParams.sanitize(p, value)
        }
    }

    /**
     * The corrections that are about a patch WORKING, not about how it sounds.
     *
     * Everything here has a failure it prevents. Nothing here reshapes a tone:
     * ceilings on gain/presence/treble and forced high cuts in the wet path
     * used to live in this function, and between them they turned every clean
     * preset into a duller, darker version of itself — nothing like Librarian.
     */
    private fun polish(v: LinkedHashMap<String, Int>) {
        // Mod/FX: only switch on a block we can actually configure. For Gen 3
        // only a block's type and on/off are implemented, so an enabled type we
        // cannot set up runs on the previous patch's parameters.
        if ((v["mod_type"] ?: -1) !in SAFE_MOD_FX) v["mod_sw"] = 0
        if ((v["fx_type"] ?: -1) !in SAFE_MOD_FX) v["fx_sw"] = 0

        // Direct Mix below full drops the dry guitar out of the mix, which
        // recalls as a patch that is all effect and no guitar.
        if ((v["delay_sw"] ?: 0) == 1) v["delay_direct"] = 100
        if ((v["reverb_sw"] ?: 0) == 1) v["reverb_direct"] = 100

        gate(v)
    }

    /**
     * Engage the Noise Suppressor only on a tone that is actually distorted,
     * and then lightly.
     *
     * The previous rule gated every preset with a threshold scaled to the amp's
     * Gain knob. That was wrong twice over: a Clean channel at Gain 80 is still
     * clean and quiet, and a threshold in the 45-60 band chokes the decay of
     * every note — so clean presets came out muffled, and the quietest of them
     * sounded like they had not loaded at all. Distortion is what hisses, so
     * distortion is what decides: amp character first, booster drive second.
     *
     * A clean patch is left exactly as it came, gate and all. If a demo patch
     * shipped with the Noise Suppressor off, it stays off — that is Librarian's
     * setting, and the amp is not noisy on a clean channel.
     */
    private fun gate(v: LinkedHashMap<String, Int>) {
        val boosted = (v["boost_sw"] ?: 0) == 1
        val drive = if (boosted) v["boost_drive"] ?: 0 else 0
        val heat = when (v["amp_type"] ?: AMP_CLEAN) {
            AMP_LEAD, AMP_BROWN -> 2
            AMP_CRUNCH -> 1
            else -> 0 // Acoustic, Clean — nothing to gate
        } + if (drive >= HARD_DRIVE) 1 else 0

        if (heat <= 0) return // clean stays untouched

        v["ns_sw"] = 1
        val want = when (heat) {
            1 -> 20
            2 -> 27
            else -> 34
        }
        // Only ever raise it: a patch that already gates harder keeps its own.
        if ((v["ns_thr"] ?: 0) < want) v["ns_thr"] = want
        // Release under ~30 chatters on a decaying note; over ~70 swallows it.
        v["ns_rel"] = (v["ns_rel"] ?: 45).coerceIn(30, 70)
    }

    /** Approximate equal-loudness amp Level from gain + boost drive/level. */
    private fun normalizedLevel(v: Map<String, Int>): Int {
        val gain = v["gain"] ?: 50
        val boostOn = (v["boost_sw"] ?: 0) == 1
        val boostLvl = if (boostOn) v["boost_level"] ?: 0 else 0
        val boostDrive = if (boostOn) v["boost_drive"] ?: 0 else 0
        val lvl = 96 - (gain * 0.28).toInt() - (boostLvl * 0.12).toInt() - (boostDrive * 0.10).toInt()
        return lvl.coerceIn(MIN_LEVEL, MAX_LEVEL)
    }

    private class Builder {
        val v = LinkedHashMap<String, Int>()

        fun amp(type: Int, gain: Int, bass: Int, mid: Int, treble: Int, pres: Int, vol: Int = 80) {
            v["amp_type"] = type; v["gain"] = gain; v["volume"] = vol
            v["bass"] = bass; v["middle"] = mid; v["treble"] = treble; v["presence"] = pres
        }

        fun boost(type: Int, drive: Int, level: Int = 70, bottom: Int = 50, tone: Int = 50) {
            v["boost_sw"] = 1; v["boost_type"] = type; v["boost_drive"] = drive
            v["boost_level"] = level; v["boost_bottom"] = bottom; v["boost_tone"] = tone
        }

        fun mod(type: Int) { v["mod_sw"] = 1; v["mod_type"] = type }
        fun fx(type: Int) { v["fx_sw"] = 1; v["fx_type"] = type }

        fun delay(type: Int, time: Int, fb: Int, level: Int = 45) {
            v["delay_sw"] = 1; v["delay_type"] = type; v["delay_time"] = time
            v["delay_fb"] = fb; v["delay_level"] = level
        }

        fun reverb(type: Int, level: Int = 40, time: Int = 50) {
            v["reverb_sw"] = 1; v["reverb_type"] = type
            v["reverb_level"] = level; v["reverb_time"] = time
        }

        fun ns(thr: Int = 40) { v["ns_sw"] = 1; v["ns_thr"] = thr }
    }

    private fun preset(name: String, note: String, build: Builder.() -> Unit): Patch {
        val b = Builder(); b.build()
        return finish(name, note, b.v, imported = false)
    }

    private const val N = "Моя версия (не оригинал JNs)"

    // The amp's own wire codes for its five characters, per the SysEx spec.
    private const val AMP_ACOUSTIC = 0
    private const val AMP_CLEAN = 1
    private const val AMP_CRUNCH = 2
    private const val AMP_LEAD = 3
    private const val AMP_BROWN = 4

    /** Booster drive from which the pedal itself starts adding hiss. */
    private const val HARD_DRIVE = 30

    /** A Level under this recalls as near-silence; only used to raise one. */
    private const val MIN_LEVEL = 55
    private const val MAX_LEVEL = 92

    /** Parameters that are safe to send only after the tone is set up. */
    private val LOAD_ORDER: List<KatanaParam> =
        KatanaParams.ALL.filter { it.kind != ParamKind.TOGGLE } +
            KatanaParams.ALL.filter { it.kind == ParamKind.TOGGLE }

    /**
     * The order to write a patch in: every knob and type first, then the on/off
     * switches. Bringing a block in only once its parameters are set avoids the
     * burst of noise you get when, say, Delay switches on while its Level is
     * still at the previous patch's value.
     */
    fun loadOrder(values: Map<String, Int>): List<Pair<KatanaParam, Int>> =
        LOAD_ORDER.mapNotNull { p -> values[p.id]?.let { p to KatanaParams.sanitize(p, it) } }

    // ---- Real factory/demo patches decoded from the Katana Librarian's
    //      bundled .kat files (BOSS demo set + JuCaNeRy "JNs" demos). These are
    //      the ORIGINAL values (gain/EQ/levels/effects), not re-creations. Amp
    //      TYPE is mapped from the MkII code to the nearest Gen 3 amp model.
    private fun orig(name: String, values: Map<String, Int>): Patch =
        finish(name, "Оригинал (демо BOSS / JuCaNeRy)", values, imported = true)

    val ORIGINALS: List<Patch> = listOf(
        orig("★ GMoore Solo", mapOf(
            "amp_type" to 3,
            "gain" to 76,
            "volume" to 60,
            "bass" to 60,
            "middle" to 75,
            "treble" to 35,
            "presence" to 45,
            "boost_sw" to 1,
            "boost_type" to 16,
            "boost_drive" to 45,
            "boost_bottom" to 55,
            "boost_tone" to 46,
            "boost_solo" to 0,
            "boost_solo_lvl" to 51,
            "boost_level" to 52,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 29,
            "fx_sw" to 1,
            "fx_type" to 3,
            "delay_sw" to 0,
            "delay_type" to 0,
            "delay_time" to 439,
            "delay_fb" to 24,
            "delay_hc" to 12,
            "delay_level" to 93,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 125,
            "reverb_lc" to 14,
            "reverb_hc" to 9,
            "reverb_level" to 67,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 35,
            "ns_rel" to 40,
        )),
        orig("★ GMoore Clean", mapOf(
            "amp_type" to 1,
            "gain" to 80,
            "volume" to 80,
            "bass" to 46,
            "middle" to 56,
            "treble" to 70,
            "presence" to 45,
            "boost_sw" to 0,
            "boost_type" to 12,
            "boost_drive" to 100,
            "boost_bottom" to 55,
            "boost_tone" to 46,
            "boost_solo" to 0,
            "boost_solo_lvl" to 51,
            "boost_level" to 52,
            "boost_direct" to 0,
            "mod_sw" to 1,
            "mod_type" to 29,
            "fx_sw" to 0,
            "fx_type" to 3,
            "delay_sw" to 1,
            "delay_type" to 0,
            "delay_time" to 439,
            "delay_fb" to 31,
            "delay_hc" to 12,
            "delay_level" to 51,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 125,
            "reverb_lc" to 14,
            "reverb_hc" to 9,
            "reverb_level" to 67,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 35,
            "ns_rel" to 40,
        )),
        orig("★ ACDC", mapOf(
            "amp_type" to 2,
            "gain" to 65,
            "volume" to 21,
            "bass" to 30,
            "middle" to 85,
            "treble" to 80,
            "presence" to 76,
            "boost_sw" to 1,
            "boost_type" to 1,
            "boost_drive" to 5,
            "boost_bottom" to 51,
            "boost_tone" to 60,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 100,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 29,
            "fx_sw" to 0,
            "fx_type" to 21,
            "delay_sw" to 0,
            "delay_type" to 8,
            "delay_time" to 439,
            "delay_fb" to 35,
            "delay_hc" to 10,
            "delay_level" to 0,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 8,
            "reverb_level" to 0,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 52,
            "ns_rel" to 50,
        )),
        orig("★ Pink Floyd", mapOf(
            "amp_type" to 2,
            "gain" to 20,
            "volume" to 100,
            "bass" to 30,
            "middle" to 60,
            "treble" to 20,
            "presence" to 35,
            "boost_sw" to 0,
            "boost_type" to 12,
            "boost_drive" to 100,
            "boost_bottom" to 73,
            "boost_tone" to 50,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 75,
            "boost_direct" to 0,
            "mod_sw" to 1,
            "mod_type" to 3,
            "fx_sw" to 1,
            "fx_type" to 6,
            "delay_sw" to 0,
            "delay_type" to 0,
            "delay_time" to 542,
            "delay_fb" to 23,
            "delay_hc" to 12,
            "delay_level" to 96,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 6,
            "reverb_time" to 59,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 8,
            "reverb_level" to 40,
            "reverb_direct" to 85,
            "ns_sw" to 1,
            "ns_thr" to 15,
            "ns_rel" to 50,
        )),
        orig("★ Fusion Lead", mapOf(
            "amp_type" to 3,
            "gain" to 80,
            "volume" to 47,
            "bass" to 40,
            "middle" to 60,
            "treble" to 24,
            "presence" to 100,
            "boost_sw" to 1,
            "boost_type" to 11,
            "boost_drive" to 1,
            "boost_bottom" to 60,
            "boost_tone" to 39,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 74,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 38,
            "fx_sw" to 0,
            "fx_type" to 29,
            "delay_sw" to 1,
            "delay_type" to 0,
            "delay_time" to 542,
            "delay_fb" to 32,
            "delay_hc" to 12,
            "delay_level" to 48,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 8,
            "reverb_level" to 54,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 30,
            "ns_rel" to 0,
        )),
        orig("★ FusionCrunch", mapOf(
            "amp_type" to 2,
            "gain" to 50,
            "volume" to 61,
            "bass" to 40,
            "middle" to 57,
            "treble" to 28,
            "presence" to 100,
            "boost_sw" to 1,
            "boost_type" to 10,
            "boost_drive" to 4,
            "boost_bottom" to 50,
            "boost_tone" to 39,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 100,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 38,
            "fx_sw" to 0,
            "fx_type" to 29,
            "delay_sw" to 1,
            "delay_type" to 0,
            "delay_time" to 542,
            "delay_fb" to 32,
            "delay_hc" to 12,
            "delay_level" to 48,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 8,
            "reverb_level" to 54,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 25,
            "ns_rel" to 0,
        )),
        orig("★ Green Day", mapOf(
            "amp_type" to 4,
            "gain" to 68,
            "volume" to 71,
            "bass" to 44,
            "middle" to 27,
            "treble" to 31,
            "presence" to 73,
            "boost_sw" to 0,
            "boost_type" to 11,
            "boost_drive" to 69,
            "boost_bottom" to 73,
            "boost_tone" to 40,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 51,
            "boost_direct" to 0,
            "mod_sw" to 1,
            "mod_type" to 6,
            "fx_sw" to 0,
            "fx_type" to 9,
            "delay_sw" to 0,
            "delay_type" to 8,
            "delay_time" to 418,
            "delay_fb" to 35,
            "delay_hc" to 10,
            "delay_level" to 0,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 1,
            "reverb_time" to 18,
            "reverb_pre" to 10,
            "reverb_lc" to 2,
            "reverb_hc" to 11,
            "reverb_level" to 28,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 42,
            "ns_rel" to 0,
        )),
        orig("★ Hardwire", mapOf(
            "amp_type" to 4,
            "gain" to 55,
            "volume" to 24,
            "bass" to 65,
            "middle" to 20,
            "treble" to 44,
            "presence" to 75,
            "boost_sw" to 1,
            "boost_type" to 12,
            "boost_drive" to 1,
            "boost_bottom" to 55,
            "boost_tone" to 50,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 75,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 6,
            "fx_sw" to 1,
            "fx_type" to 6,
            "delay_sw" to 0,
            "delay_type" to 8,
            "delay_time" to 382,
            "delay_fb" to 22,
            "delay_hc" to 10,
            "delay_level" to 99,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 1,
            "reverb_time" to 17,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 6,
            "reverb_level" to 49,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 45,
            "ns_rel" to 0,
        )),
        orig("★ Metal Rhythm", mapOf(
            "amp_type" to 4,
            "gain" to 71,
            "volume" to 46,
            "bass" to 65,
            "middle" to 27,
            "treble" to 40,
            "presence" to 92,
            "boost_sw" to 1,
            "boost_type" to 12,
            "boost_drive" to 0,
            "boost_bottom" to 52,
            "boost_tone" to 48,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 77,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 19,
            "fx_sw" to 0,
            "fx_type" to 6,
            "delay_sw" to 0,
            "delay_type" to 8,
            "delay_time" to 412,
            "delay_fb" to 35,
            "delay_hc" to 10,
            "delay_level" to 0,
            "delay_direct" to 100,
            "reverb_sw" to 0,
            "reverb_type" to 4,
            "reverb_time" to 34,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 7,
            "reverb_level" to 0,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 30,
            "ns_rel" to 0,
        )),
        orig("★ Metal Solo", mapOf(
            "amp_type" to 4,
            "gain" to 71,
            "volume" to 54,
            "bass" to 51,
            "middle" to 69,
            "treble" to 35,
            "presence" to 82,
            "boost_sw" to 1,
            "boost_type" to 12,
            "boost_drive" to 0,
            "boost_bottom" to 52,
            "boost_tone" to 48,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 77,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 19,
            "fx_sw" to 0,
            "fx_type" to 6,
            "delay_sw" to 1,
            "delay_type" to 8,
            "delay_time" to 412,
            "delay_fb" to 31,
            "delay_hc" to 10,
            "delay_level" to 51,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 34,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 7,
            "reverb_level" to 51,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 30,
            "ns_rel" to 0,
        )),
        orig("★ Octa Fuzz", mapOf(
            "amp_type" to 2,
            "gain" to 35,
            "volume" to 29,
            "bass" to 60,
            "middle" to 40,
            "treble" to 71,
            "presence" to 75,
            "boost_sw" to 1,
            "boost_type" to 9,
            "boost_drive" to 41,
            "boost_bottom" to 82,
            "boost_tone" to 26,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 100,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 15,
            "fx_sw" to 0,
            "fx_type" to 19,
            "delay_sw" to 0,
            "delay_type" to 9,
            "delay_time" to 230,
            "delay_fb" to 35,
            "delay_hc" to 11,
            "delay_level" to 0,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 3,
            "reverb_time" to 40,
            "reverb_pre" to 10,
            "reverb_lc" to 2,
            "reverb_hc" to 11,
            "reverb_level" to 38,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 40,
            "ns_rel" to 0,
        )),
        orig("★ S-H Jazz", mapOf(
            "amp_type" to 1,
            "gain" to 58,
            "volume" to 60,
            "bass" to 54,
            "middle" to 40,
            "treble" to 50,
            "presence" to 50,
            "boost_sw" to 0,
            "boost_type" to 12,
            "boost_drive" to 0,
            "boost_bottom" to 50,
            "boost_tone" to 50,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 69,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 38,
            "fx_sw" to 1,
            "fx_type" to 9,
            "delay_sw" to 0,
            "delay_type" to 8,
            "delay_time" to 400,
            "delay_fb" to 22,
            "delay_hc" to 10,
            "delay_level" to 100,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 24,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 10,
            "reverb_level" to 60,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 45,
            "ns_rel" to 0,
        )),
        orig("★ Soft Lead", mapOf(
            "amp_type" to 4,
            "gain" to 20,
            "volume" to 32,
            "bass" to 42,
            "middle" to 42,
            "treble" to 60,
            "presence" to 75,
            "boost_sw" to 1,
            "boost_type" to 12,
            "boost_drive" to 0,
            "boost_bottom" to 55,
            "boost_tone" to 40,
            "boost_solo" to 0,
            "boost_solo_lvl" to 51,
            "boost_level" to 50,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 29,
            "fx_sw" to 1,
            "fx_type" to 6,
            "delay_sw" to 0,
            "delay_type" to 0,
            "delay_time" to 439,
            "delay_fb" to 23,
            "delay_hc" to 12,
            "delay_level" to 95,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 0,
            "reverb_lc" to 14,
            "reverb_hc" to 8,
            "reverb_level" to 51,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 49,
            "ns_rel" to 0,
        )),
        orig("★ Sweet Strat", mapOf(
            "amp_type" to 2,
            "gain" to 74,
            "volume" to 19,
            "bass" to 13,
            "middle" to 37,
            "treble" to 30,
            "presence" to 75,
            "boost_sw" to 1,
            "boost_type" to 1,
            "boost_drive" to 50,
            "boost_bottom" to 39,
            "boost_tone" to 48,
            "boost_solo" to 0,
            "boost_solo_lvl" to 51,
            "boost_level" to 50,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 29,
            "fx_sw" to 0,
            "fx_type" to 22,
            "delay_sw" to 1,
            "delay_type" to 8,
            "delay_time" to 439,
            "delay_fb" to 27,
            "delay_hc" to 8,
            "delay_level" to 36,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 0,
            "reverb_lc" to 14,
            "reverb_hc" to 8,
            "reverb_level" to 64,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 30,
            "ns_rel" to 0,
        )),
        orig("★ Tele Edge", mapOf(
            "amp_type" to 2,
            "gain" to 27,
            "volume" to 30,
            "bass" to 49,
            "middle" to 35,
            "treble" to 21,
            "presence" to 63,
            "boost_sw" to 1,
            "boost_type" to 1,
            "boost_drive" to 0,
            "boost_bottom" to 50,
            "boost_tone" to 49,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 100,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 38,
            "fx_sw" to 0,
            "fx_type" to 0,
            "delay_sw" to 1,
            "delay_type" to 8,
            "delay_time" to 620,
            "delay_fb" to 34,
            "delay_hc" to 10,
            "delay_level" to 33,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 10,
            "reverb_level" to 62,
            "reverb_direct" to 100,
            "ns_sw" to 1,
            "ns_thr" to 32,
            "ns_rel" to 0,
        )),
        orig("★ Katana Clean", mapOf(
            "amp_type" to 1,
            "gain" to 60,
            "volume" to 50,
            "bass" to 50,
            "middle" to 50,
            "treble" to 50,
            "presence" to 50,
            "boost_sw" to 0,
            "boost_type" to 10,
            "boost_drive" to 50,
            "boost_bottom" to 60,
            "boost_tone" to 50,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 40,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 29,
            "fx_sw" to 0,
            "fx_type" to 21,
            "delay_sw" to 0,
            "delay_type" to 0,
            "delay_time" to 400,
            "delay_fb" to 22,
            "delay_hc" to 10,
            "delay_level" to 50,
            "delay_direct" to 100,
            "reverb_sw" to 0,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 8,
            "reverb_level" to 35,
            "reverb_direct" to 100,
            "ns_sw" to 0,
            "ns_thr" to 5,
            "ns_rel" to 50,
        )),
        orig("★ Katana Demo 1", mapOf(
            "amp_type" to 1,
            "gain" to 47,
            "volume" to 80,
            "bass" to 67,
            "middle" to 48,
            "treble" to 63,
            "presence" to 45,
            "boost_sw" to 0,
            "boost_type" to 14,
            "boost_drive" to 60,
            "boost_bottom" to 70,
            "boost_tone" to 50,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 50,
            "boost_direct" to 0,
            "mod_sw" to 1,
            "mod_type" to 29,
            "fx_sw" to 0,
            "fx_type" to 0,
            "delay_sw" to 1,
            "delay_type" to 8,
            "delay_time" to 370,
            "delay_fb" to 22,
            "delay_hc" to 10,
            "delay_level" to 82,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 10,
            "reverb_lc" to 12,
            "reverb_hc" to 10,
            "reverb_level" to 57,
            "reverb_direct" to 100,
            "ns_sw" to 0,
            "ns_thr" to 5,
            "ns_rel" to 50,
        )),
        orig("★ Katana Demo 2", mapOf(
            "amp_type" to 4,
            "gain" to 93,
            "volume" to 24,
            "bass" to 68,
            "middle" to 53,
            "treble" to 54,
            "presence" to 20,
            "boost_sw" to 0,
            "boost_type" to 14,
            "boost_drive" to 10,
            "boost_bottom" to 70,
            "boost_tone" to 50,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 65,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 19,
            "fx_sw" to 0,
            "fx_type" to 0,
            "delay_sw" to 1,
            "delay_type" to 8,
            "delay_time" to 567,
            "delay_fb" to 22,
            "delay_hc" to 10,
            "delay_level" to 52,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 3,
            "reverb_time" to 29,
            "reverb_pre" to 10,
            "reverb_lc" to 12,
            "reverb_hc" to 10,
            "reverb_level" to 57,
            "reverb_direct" to 100,
            "ns_sw" to 0,
            "ns_thr" to 5,
            "ns_rel" to 50,
        )),
        orig("★ Katana Demo 3", mapOf(
            "amp_type" to 4,
            "gain" to 40,
            "volume" to 20,
            "bass" to 74,
            "middle" to 32,
            "treble" to 93,
            "presence" to 50,
            "boost_sw" to 1,
            "boost_type" to 1,
            "boost_drive" to 46,
            "boost_bottom" to 60,
            "boost_tone" to 50,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 65,
            "boost_direct" to 0,
            "mod_sw" to 0,
            "mod_type" to 36,
            "fx_sw" to 1,
            "fx_type" to 35,
            "delay_sw" to 0,
            "delay_type" to 10,
            "delay_time" to 400,
            "delay_fb" to 22,
            "delay_hc" to 12,
            "delay_level" to 99,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 8,
            "reverb_level" to 50,
            "reverb_direct" to 100,
            "ns_sw" to 0,
            "ns_thr" to 5,
            "ns_rel" to 50,
        )),
        orig("★ Katana Demo 4", mapOf(
            "amp_type" to 4,
            "gain" to 51,
            "volume" to 21,
            "bass" to 91,
            "middle" to 60,
            "treble" to 76,
            "presence" to 54,
            "boost_sw" to 0,
            "boost_type" to 1,
            "boost_drive" to 100,
            "boost_bottom" to 60,
            "boost_tone" to 50,
            "boost_solo" to 0,
            "boost_solo_lvl" to 50,
            "boost_level" to 65,
            "boost_direct" to 0,
            "mod_sw" to 1,
            "mod_type" to 36,
            "fx_sw" to 0,
            "fx_type" to 35,
            "delay_sw" to 0,
            "delay_type" to 10,
            "delay_time" to 400,
            "delay_fb" to 22,
            "delay_hc" to 12,
            "delay_level" to 99,
            "delay_direct" to 100,
            "reverb_sw" to 1,
            "reverb_type" to 4,
            "reverb_time" to 29,
            "reverb_pre" to 10,
            "reverb_lc" to 14,
            "reverb_hc" to 8,
            "reverb_level" to 60,
            "reverb_direct" to 100,
            "ns_sw" to 0,
            "ns_thr" to 5,
            "ns_rel" to 50,
        )),
    )

    val ALL: List<Patch> = ORIGINALS + listOf(
        preset("AC/DC Crunch", "$N · плотный Marshall-кранч, минимум эффектов") {
            amp(2, 62, 52, 68, 58, 55); reverb(1, level = 22, time = 35)
        },
        preset("Fusion Lead", "$N · певучий лид с дилеем и холлом") {
            amp(3, 78, 45, 60, 55, 52); boost(12, 40, 60)
            delay(0, 440, 35); reverb(3, 35)
        },
        preset("Fusion Crunch", "$N · упругий кранч для риффов и аккордов") {
            amp(2, 55, 50, 58, 55, 48); delay(0, 380, 22, 28); reverb(1, 22)
        },
        preset("Gary Moore Clean", "$N · тёплый чистый с холлом и хорусом") {
            amp(1, 30, 55, 55, 55, 45); mod(29); reverb(3, level = 45, time = 60)
        },
        preset("Gary Moore Solo", "$N · жирный поющий лид") {
            amp(3, 82, 50, 70, 55, 50); boost(12, 45, 65)
            delay(7, 400, 40, 40); reverb(3, 38)
        },
        preset("Green Day Punk", "$N · яркий панк-кранч с бустом") {
            amp(2, 72, 55, 60, 62, 60); boost(3, 50, 60); ns(35)
        },
        preset("Hardwire Metal", "$N · плотный современный хай-гейн") {
            amp(4, 85, 60, 40, 60, 55); ns(42); reverb(1, 15)
        },
        preset("Metal Rhythm", "$N · тугой ритм со скупыми серединами") {
            amp(4, 88, 62, 35, 58, 55); ns(45)
        },
        preset("Metal Solo", "$N · хай-гейн лид с дилеем") {
            amp(4, 88, 55, 55, 62, 58); boost(0, 40, 60)
            delay(0, 400, 30, 35); reverb(3, 30); ns(40)
        },
        preset("Octave Fuzz", "$N · фузз с октавой сверху") {
            amp(2, 55, 55, 55, 55, 50); boost(19, 70, 55); fx(14); reverb(1, 20)
        },
        preset("Pink Floyd Lead", "$N · gilmour-style: овердрайв + большой дилей") {
            amp(3, 70, 50, 60, 52, 48); boost(10, 45, 60)
            delay(0, 440, 38, 45); reverb(3, 40)
        },
        preset("S-H Jazz", "$N · чистый джаз в духе JC-120 с хорусом") {
            amp(1, 20, 55, 50, 45, 40); mod(29); reverb(1, 30)
        },
        preset("Soft Lead", "$N · мягкий лид средней перегрузки") {
            amp(3, 60, 48, 58, 52, 48); delay(0, 380, 30, 40); reverb(3, 40)
        },
        preset("Sweet Strat", "$N · чистый спанк со стратокастера, хорус") {
            amp(1, 35, 50, 52, 58, 50); mod(29); reverb(5, 30)
        },
        preset("Blues Drive", "$N · тёплый овердрайв для блюза") {
            amp(2, 55, 52, 62, 55, 50); boost(10, 45, 60); reverb(1, 25)
        },
        preset("Funk Clean", "$N · чистый фанк, компрессор + лёгкий хорус") {
            amp(1, 28, 50, 55, 60, 48); fx(3); mod(29); reverb(1, 18)
        },
        preset("Ambient Wash", "$N · чистый эмбиент, длинный дилей + холл") {
            amp(1, 30, 52, 50, 55, 45); delay(0, 600, 45, 50); reverb(3, level = 55, time = 80)
        },
        preset("Djent Tight", "$N · тугой современный хай-гейн, ноуз-гейт") {
            amp(4, 90, 60, 42, 62, 52); ns(45)
        },
        preset("Country Twang", "$N · яркий кантри-твэнг, спринг-ревер") {
            amp(1, 30, 48, 55, 65, 55); boost(2, 30, 55); reverb(5, 28)
        },
        preset("Doom Sludge", "$N · низкий тяжёлый фузз") {
            amp(4, 85, 70, 45, 45, 45); boost(20, 65, 55); ns(40)
        },
        preset("Shred Lead", "$N · скоростной лид с дилеем и холлом") {
            amp(4, 88, 52, 60, 60, 55); boost(12, 45, 62)
            delay(0, 380, 30, 38); reverb(3, 35); ns(38)
        },
        preset("Surf Twang", "$N · сёрф: спринг-ревер по максимуму, тремоло") {
            amp(1, 25, 52, 52, 60, 52); mod(21); reverb(5, level = 60, time = 55)
        },
        preset("Nu-Metal Scoop", "$N · выскобленные серединки, буст, гейт") {
            amp(4, 86, 65, 30, 62, 55); boost(3, 55, 60); ns(45)
        },
        preset("Worship Pad", "$N · воздушный чистый лид, дилей+ревер") {
            amp(1, 40, 50, 55, 55, 48); boost(1, 25, 55)
            delay(0, 500, 40, 45); reverb(3, level = 55, time = 75)
        },
        // --- Singing / blues leads (в духе Gary Moore Solo) ---------------
        preset("Sing Lead", "$N · поющий лид с сустейном, длинный дилей + холл") {
            amp(3, 80, 48, 72, 55, 52); boost(12, 48, 68, tone = 55)
            delay(7, 440, 42, 42); reverb(3, level = 42, time = 55)
        },
        preset("Moore Blues", "$N · тёплый блюз-лид, поёт на низкой громкости") {
            amp(3, 72, 52, 70, 52, 48); boost(10, 45, 62)
            delay(7, 380, 35, 34); reverb(3, level = 34, time = 45)
        },
        preset("Still Got Blues", "$N · мягкий скрипичный лид, много холла") {
            amp(3, 76, 50, 74, 50, 46); boost(12, 42, 64, tone = 48)
            delay(0, 420, 30, 36); reverb(3, level = 45, time = 60)
        },
        preset("SRV Texas", "$N · техасский овердрайв, tube screamer, пружина") {
            amp(2, 60, 55, 68, 60, 52); boost(12, 55, 60); reverb(5, level = 26, time = 40)
        },
        preset("Santana Sustain", "$N · бесконечный сустейн, длинный дилей + холл") {
            amp(3, 84, 50, 76, 52, 50); boost(0, 40, 66)
            delay(7, 480, 45, 44); reverb(3, level = 48, time = 65)
        },
        preset("Slow Hand Lead", "$N · вудман-тон, средне-жирный лид") {
            amp(3, 70, 48, 72, 50, 46); boost(11, 40, 60); reverb(3, level = 32, time = 45)
        },
        preset("Gilmour Big Lead", "$N · большой лид: овердрайв + длинный дилей + холл") {
            amp(3, 74, 52, 66, 54, 50); boost(10, 46, 62)
            delay(0, 470, 40, 46); reverb(3, level = 44, time = 62)
        },
        preset("Slash Rock Lead", "$N · рок-лид, крепкий кранч с серединой") {
            amp(4, 80, 55, 62, 58, 52); boost(3, 50, 60)
            delay(0, 400, 28, 32); reverb(1, level = 24, time = 35)
        },
        preset("Power Ballad", "$N · 80-е: хорус + большой дилей + холл") {
            amp(3, 68, 50, 64, 56, 50); mod(29); boost(1, 30, 58)
            delay(0, 450, 38, 44); reverb(3, level = 48, time = 65)
        },
        preset("Carlos Warm", "$N · тёплый поющий лид с мидбустом") {
            amp(3, 78, 54, 74, 50, 48); boost(0, 45, 66, tone = 45)
            delay(7, 430, 40, 40); reverb(3, level = 40, time = 55)
        },
    )
}

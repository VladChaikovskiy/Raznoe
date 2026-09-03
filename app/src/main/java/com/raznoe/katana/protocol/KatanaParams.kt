package com.raznoe.katana.protocol

/**
 * The Katana parameter map — the "device profile".
 *
 * Addresses below are the community-confirmed **Katana Gen1 / MkII (model id
 * 0x33)** map, cross-verified across snhirsch/katana-midi-bridge and the
 * katana-dev projects. The SysEx framing/checksum ([KatanaSysEx]) is identical
 * on every generation; the ADDRESS MAP is what changes between generations.
 *
 * ⚠️ GEN 3: the app targets a Gen 3 amp, whose address map is still being
 * reverse-engineered by the community and is NOT yet public. We therefore ship
 * the confirmed MkII map as the default profile. Values that are known to be
 * uncertain on Gen 3 (amp panel offsets, amp-type list) have verified = false
 * and show a "(?)" in the UI. Use the Console tab's block-read + diff to
 * confirm/adjust any address on real Gen 3 hardware — then edit this one file.
 */

enum class ParamKind { CONTINUOUS, TOGGLE, ENUM }

data class KatanaParam(
    val id: String,
    val label: String,
    val category: String,
    val address: IntArray,
    val kind: ParamKind,
    val min: Int = 0,
    val max: Int = 100,
    val default: Int = 0,
    /** ENUM display labels. */
    val options: List<String> = emptyList(),
    /** ENUM wire values, parallel to [options]. Empty => value == index. */
    val optionValues: List<Int> = emptyList(),
    /** true => value is a 2-byte (hi7,lo7) word, e.g. delay time in ms. */
    val word: Boolean = false,
    /** false => address not yet confirmed for Gen 3 (shows "(?)"). */
    val verified: Boolean = false,
    /** Gen 3 fixed wire address (decoded from Katana Librarian), if known. */
    val addrGen3: IntArray? = null,
    /**
     * Gen 3 "banked" effect params: the section base depends on which physical
     * slot the effect currently occupies, chosen at runtime by an FX-BOX
     * selector byte. [gen3Slots] holds the candidate section bases (slot 0/1/2),
     * [gen3Index] the param offset in the section, and [gen3Sel] the selector's
     * offset within the COLOR block (0=FX1A booster, 3=FX2A delay, 4=FX3 reverb).
     * The actual address = KatanaSysEx.gen3AddrFromBase(gen3Slots[selValue], gen3Index).
     */
    val gen3Slots: IntArray? = null,
    val gen3Index: Int = 0,
    val gen3Sel: Int = -1,
) {
    override fun equals(other: Any?) = other is KatanaParam && other.id == id
    override fun hashCode() = id.hashCode()

    /**
     * Fixed wire address for a generation. NOTE: Gen 3 "banked" params
     * ([gen3Slots] != null) are resolved by [KatanaController] against the live
     * selector cache, not here — this returns the MkII address for them so the
     * static path stays well-defined.
     */
    fun addressFor(gen3: Boolean): IntArray = if (gen3 && addrGen3 != null) addrGen3 else address

    /** Map a wire value to its option index (for ENUM). */
    fun indexOfValue(value: Int): Int {
        if (optionValues.isEmpty()) return value.coerceIn(0, options.lastIndex.coerceAtLeast(0))
        val i = optionValues.indexOf(value)
        return if (i >= 0) i else 0
    }

    /** Map an option index to its wire value (for ENUM). */
    fun valueOfIndex(index: Int): Int =
        if (optionValues.isEmpty()) index else optionValues.getOrElse(index) { 0 }
}

object KatanaParams {

    // ---- Enum / type lists (label -> wire value) -------------------------

    // Gen 3 amp characters (front-panel). Address/order provisional for Gen 3.
    val AMP_TYPES = listOf(
        "Acoustic", "Clean", "Pushed", "Crunch", "Lead", "Brown",
    )

    // Booster / OD-DS types (60 00 00 31). Note: wire value 7 is unused.
    private val BOOSTER = listOf(
        "Mid Boost" to 0, "Clean Boost" to 1, "Treble Boost" to 2, "Crunch OD" to 3,
        "Natural OD" to 4, "Warm OD" to 5, "Fat DS" to 6, "Metal DS" to 8,
        "Oct Fuzz" to 9, "Blues Drive" to 10, "Overdrive" to 11, "T-Scream" to 12,
        "Turbo OD" to 13, "Distortion" to 14, "Rat" to 15, "Guv DS" to 16,
        "DST+" to 17, "Metal Zone" to 18, "'60s Fuzz" to 19, "Muff Fuzz" to 20,
    )

    // Shared MOD / FX type list (60 00 01 41 and 60 00 03 4D). Values have gaps.
    private val MOD_FX = listOf(
        "T-Wah" to 0, "Auto Wah" to 1, "Pedal Wah" to 2, "Comp" to 3, "Limiter" to 4,
        "Graphic EQ" to 6, "Parametric EQ" to 7, "Guitar Sim" to 9, "Slow Gear" to 10,
        "Wave Synth" to 12, "Octave" to 14, "Pitch Shifter" to 15, "Harmonist" to 16,
        "AC Processor" to 18, "Phaser" to 19, "Flanger" to 20, "Tremolo" to 21,
        "Rotary" to 22, "Uni-V" to 23, "Slicer" to 25, "Vibrato" to 26,
        "Ring Mod" to 27, "Humanizer" to 28, "Chorus" to 29, "AC Guitar Sim" to 31,
    )

    private val DELAY = listOf(
        "Digital" to 0, "Reverse" to 6, "Analog" to 7, "Tape Echo" to 8, "Modulate" to 9,
    )

    private val REVERB = listOf(
        "Room" to 1, "Hall" to 3, "Plate" to 4, "Spring" to 5, "Modulate" to 6,
    )

    // Sequential filter enums (value == index).
    private val HIGH_CUT = listOf(
        "630Hz", "800Hz", "1kHz", "1.25kHz", "1.6kHz", "2kHz", "2.5kHz", "3.15kHz",
        "4kHz", "5kHz", "6.3kHz", "8kHz", "10kHz", "12.5kHz", "Flat",
    )
    private val LOW_CUT = listOf(
        "Flat", "20Hz", "25Hz", "31.5Hz", "40Hz", "50Hz", "63Hz", "80Hz", "100Hz",
        "125Hz", "160Hz", "200Hz", "250Hz", "315Hz", "400Hz", "500Hz", "630Hz", "800Hz",
    )

    // ---- Gen 3 banked-effect slot bases + selector offsets ---------------
    // Section bases from the Katana Librarian `m` enum; selector offsets are the
    // byte position inside the COLOR block (20 00 04 00, size 5). An effect's
    // real Gen 3 address depends on which physical slot it currently occupies.
    private val BOOSTER_SLOTS = intArrayOf(2560, 3072, 3584)   // BOOSTER(1/2/3)
    private val FX1_SLOTS = intArrayOf(4096, 4608, 5120)       // FX(1/2/3)  — Mod box
    private val FX2_SLOTS = intArrayOf(5632, 6144, 6656)       // FX(4/5/6)  — FX box
    private val DELAY_SLOTS = intArrayOf(10240, 10752, 11264)  // DELAY(1/2/3)
    private val REVERB_SLOTS = intArrayOf(13312, 13824, 14336) // REVERB(1/2/3)
    const val SEL_FX1A = 0   // booster selector byte offset in COLOR block
    const val SEL_FX1B = 1   // Mod (FX1) box selector
    const val SEL_FX2B = 2   // FX (FX2) box selector
    const val SEL_FX2A = 3   // delay selector
    const val SEL_FX3 = 4    // reverb selector
    const val GEN3_SELECTOR_COUNT = 5

    // ---- Sections --------------------------------------------------------
    private const val AMP = "Усилитель"
    private const val BOOST = "Booster"
    private const val MOD = "Mod"
    private const val FX = "FX"
    private const val DLY = "Delay"
    private const val REV = "Reverb"
    private const val NS = "Noise Suppressor"

    // ---- Amp panel. MkII offsets provisional; Gen 3 addresses decoded from
    //      the Katana Librarian app (section AMP, base 0x2000 06 xx). --------
    val AMP_TYPE = enum("amp_type", "Тип усилителя", AMP, a(0x00, 0x00, 0x04, 0x21),
        AMP_TYPES.mapIndexed { i, s -> s to i }, g3 = a(0x20, 0x00, 0x06, 0x07))
    val GAIN = cont("gain", "Gain", AMP, a(0x00, 0x00, 0x04, 0x22), g3 = a(0x20, 0x00, 0x06, 0x00))
    val VOLUME = cont("volume", "Volume", AMP, a(0x00, 0x00, 0x04, 0x23), g3 = a(0x20, 0x00, 0x06, 0x01))
    val BASS = cont("bass", "Bass", AMP, a(0x00, 0x00, 0x04, 0x24), g3 = a(0x20, 0x00, 0x06, 0x02))
    val MIDDLE = cont("middle", "Middle", AMP, a(0x00, 0x00, 0x04, 0x25), g3 = a(0x20, 0x00, 0x06, 0x03))
    val TREBLE = cont("treble", "Treble", AMP, a(0x00, 0x00, 0x04, 0x26), g3 = a(0x20, 0x00, 0x06, 0x04))
    val PRESENCE = cont("presence", "Presence", AMP, a(0x00, 0x00, 0x04, 0x27), g3 = a(0x20, 0x00, 0x06, 0x05))

    // ---- Booster (60 00 00 30); Gen 3 on/off in section SW ---------------
    val BOOST_SW = toggle("boost_sw", "Booster", BOOST, a(0x60, 0x00, 0x00, 0x30),
        g3 = a(0x20, 0x00, 0x08, 0x00))
    val BOOST_TYPE = enum("boost_type", "Тип", BOOST, a(0x60, 0x00, 0x00, 0x31), BOOSTER,
        slots = BOOSTER_SLOTS, gi = 0, sel = SEL_FX1A)
    val BOOST_DRIVE = cont("boost_drive", "Drive", BOOST, a(0x60, 0x00, 0x00, 0x32),
        slots = BOOSTER_SLOTS, gi = 1, sel = SEL_FX1A)
    val BOOST_BOTTOM = cont("boost_bottom", "Bottom", BOOST, a(0x60, 0x00, 0x00, 0x33),
        slots = BOOSTER_SLOTS, gi = 2, sel = SEL_FX1A)
    val BOOST_TONE = cont("boost_tone", "Tone", BOOST, a(0x60, 0x00, 0x00, 0x34),
        slots = BOOSTER_SLOTS, gi = 3, sel = SEL_FX1A)
    val BOOST_SOLO = toggle("boost_solo", "Solo", BOOST, a(0x60, 0x00, 0x00, 0x35),
        slots = BOOSTER_SLOTS, gi = 4, sel = SEL_FX1A)
    val BOOST_SOLO_LVL = cont("boost_solo_lvl", "Solo Level", BOOST, a(0x60, 0x00, 0x00, 0x36),
        slots = BOOSTER_SLOTS, gi = 5, sel = SEL_FX1A)
    val BOOST_LEVEL = cont("boost_level", "Effect Level", BOOST, a(0x60, 0x00, 0x00, 0x37),
        slots = BOOSTER_SLOTS, gi = 6, sel = SEL_FX1A)
    val BOOST_DIRECT = cont("boost_direct", "Direct Mix", BOOST, a(0x60, 0x00, 0x00, 0x38),
        slots = BOOSTER_SLOTS, gi = 7, sel = SEL_FX1A)

    // ---- Mod (60 00 01 40) — type + on/off (per-type params via Console) --
    val MOD_SW = toggle("mod_sw", "Mod", MOD, a(0x60, 0x00, 0x01, 0x40),
        g3 = a(0x20, 0x00, 0x08, 0x01))
    val MOD_TYPE = enum("mod_type", "Тип", MOD, a(0x60, 0x00, 0x01, 0x41), MOD_FX,
        slots = FX1_SLOTS, gi = 0, sel = SEL_FX1B, verified = false)

    // ---- FX (60 00 03 4C) ------------------------------------------------
    val FX_SW = toggle("fx_sw", "FX", FX, a(0x60, 0x00, 0x03, 0x4C),
        g3 = a(0x20, 0x00, 0x08, 0x02))
    val FX_TYPE = enum("fx_type", "Тип", FX, a(0x60, 0x00, 0x03, 0x4D), MOD_FX,
        slots = FX2_SLOTS, gi = 0, sel = SEL_FX2B, verified = false)

    // ---- Delay (60 00 05 60) --------------------------------------------
    val DELAY_SW = toggle("delay_sw", "Delay", DLY, a(0x60, 0x00, 0x05, 0x60),
        g3 = a(0x20, 0x00, 0x08, 0x03))
    val DELAY_TYPE = enum("delay_type", "Тип", DLY, a(0x60, 0x00, 0x05, 0x61), DELAY,
        slots = DELAY_SLOTS, gi = 0, sel = SEL_FX2A)
    val DELAY_TIME = KatanaParam(
        "delay_time", "Time (ms)", DLY, a(0x60, 0x00, 0x05, 0x62), ParamKind.CONTINUOUS,
        min = 1, max = 2000, default = 400, word = true, verified = true,
        gen3Slots = DELAY_SLOTS, gen3Index = 1, gen3Sel = SEL_FX2A,
    )
    val DELAY_FEEDBACK = cont("delay_fb", "Feedback", DLY, a(0x60, 0x00, 0x05, 0x64),
        slots = DELAY_SLOTS, gi = 5, sel = SEL_FX2A)
    val DELAY_HIGHCUT = enum("delay_hc", "High Cut", DLY, a(0x60, 0x00, 0x05, 0x65),
        HIGH_CUT.mapIndexed { i, s -> s to i }, slots = DELAY_SLOTS, gi = 6, sel = SEL_FX2A)
    val DELAY_LEVEL = cont("delay_level", "Effect Level", DLY, a(0x60, 0x00, 0x05, 0x66),
        slots = DELAY_SLOTS, gi = 7, sel = SEL_FX2A)
    val DELAY_DIRECT = cont("delay_direct", "Direct Mix", DLY, a(0x60, 0x00, 0x05, 0x67),
        slots = DELAY_SLOTS, gi = 8, sel = SEL_FX2A)

    // ---- Reverb (60 00 06 10) -------------------------------------------
    val REVERB_SW = toggle("reverb_sw", "Reverb", REV, a(0x60, 0x00, 0x06, 0x10),
        g3 = a(0x20, 0x00, 0x08, 0x05))
    val REVERB_TYPE = enum("reverb_type", "Тип", REV, a(0x60, 0x00, 0x06, 0x11), REVERB,
        slots = REVERB_SLOTS, gi = 0, sel = SEL_FX3)
    val REVERB_TIME = cont("reverb_time", "Time", REV, a(0x60, 0x00, 0x06, 0x12), max = 99,
        slots = REVERB_SLOTS, gi = 2, sel = SEL_FX3)
    val REVERB_PREDELAY = KatanaParam(
        "reverb_pre", "Pre-Delay (ms)", REV, a(0x60, 0x00, 0x06, 0x13), ParamKind.CONTINUOUS,
        min = 0, max = 500, default = 0, word = true, verified = true,
        gen3Slots = REVERB_SLOTS, gen3Index = 3, gen3Sel = SEL_FX3,
    )
    val REVERB_LOWCUT = enum("reverb_lc", "Low Cut", REV, a(0x60, 0x00, 0x06, 0x15),
        LOW_CUT.mapIndexed { i, s -> s to i }, slots = REVERB_SLOTS, gi = 7, sel = SEL_FX3)
    val REVERB_HIGHCUT = enum("reverb_hc", "High Cut", REV, a(0x60, 0x00, 0x06, 0x16),
        HIGH_CUT.mapIndexed { i, s -> s to i }, slots = REVERB_SLOTS, gi = 8, sel = SEL_FX3)
    val REVERB_DENSITY = cont("reverb_density", "Density", REV, a(0x60, 0x00, 0x06, 0x17))
    val REVERB_LEVEL = cont("reverb_level", "Effect Level", REV, a(0x60, 0x00, 0x06, 0x18),
        slots = REVERB_SLOTS, gi = 10, sel = SEL_FX3)
    val REVERB_DIRECT = cont("reverb_direct", "Direct Mix", REV, a(0x60, 0x00, 0x06, 0x19),
        slots = REVERB_SLOTS, gi = 11, sel = SEL_FX3)
    val REVERB_SPRING = cont("reverb_spring", "Spring Sens", REV, a(0x60, 0x00, 0x06, 0x1A))

    // ---- Noise Suppressor (60 00 06 63); Gen 3 NS section base 22528 -----
    val NS_SW = toggle("ns_sw", "Noise Suppressor", NS, a(0x60, 0x00, 0x06, 0x63),
        g3 = a(0x20, 0x00, 0x58, 0x00))
    val NS_THRESHOLD = cont("ns_thr", "Threshold", NS, a(0x60, 0x00, 0x06, 0x64),
        g3 = a(0x20, 0x00, 0x58, 0x01))
    val NS_RELEASE = cont("ns_rel", "Release", NS, a(0x60, 0x00, 0x06, 0x65),
        g3 = a(0x20, 0x00, 0x58, 0x02))

    val ALL: List<KatanaParam> = listOf(
        AMP_TYPE, GAIN, VOLUME, BASS, MIDDLE, TREBLE, PRESENCE,
        BOOST_SW, BOOST_TYPE, BOOST_DRIVE, BOOST_BOTTOM, BOOST_TONE,
        BOOST_SOLO, BOOST_SOLO_LVL, BOOST_LEVEL, BOOST_DIRECT,
        MOD_SW, MOD_TYPE,
        FX_SW, FX_TYPE,
        DELAY_SW, DELAY_TYPE, DELAY_TIME, DELAY_FEEDBACK, DELAY_HIGHCUT, DELAY_LEVEL, DELAY_DIRECT,
        REVERB_SW, REVERB_TYPE, REVERB_TIME, REVERB_PREDELAY, REVERB_LOWCUT, REVERB_HIGHCUT,
        REVERB_DENSITY, REVERB_LEVEL, REVERB_DIRECT, REVERB_SPRING,
        NS_SW, NS_THRESHOLD, NS_RELEASE,
    )

    val BY_CATEGORY: Map<String, List<KatanaParam>> = ALL.groupBy { it.category }
    val BY_ID: Map<String, KatanaParam> = ALL.associateBy { it.id }

    /** Which categories are effect blocks that have an on/off toggle first. */
    val EFFECT_SECTIONS = listOf(BOOST, MOD, FX, DLY, REV, NS)
    val AMP_SECTION = AMP

    // ---- Channel / preset select (00 01 00 00) --------------------------
    val CURRENT_PRESET_ADDR = a(0x00, 0x01, 0x00, 0x00)
    /** Data byte per channel: Panel=0, CH1..CH4 = 1..4. */
    val CHANNELS = listOf("Panel" to 0, "CH1" to 1, "CH2" to 2, "CH3" to 3, "CH4" to 4)

    // ---- Live-patch read spans (RQ1 these to snapshot current tone) ------
    data class ReadRange(val address: IntArray, val size: Int)

    /**
     * Gen 3 initial-read sequence, decoded from the Katana Librarian app's k1()
     * for KATANA_MK3: a system probe, the 9 patch sections (Y0 base 0x20000000),
     * and a couple of small blocks. These are valid Gen 3 addresses that make the
     * amp reply — the milestone that proves Gen 3 comms.
     */
    val GEN3_READ_RANGES = listOf(
        ReadRange(a(0x7F, 0x00, 0x00, 0x00), 1),
        ReadRange(a(0x20, 0x10, 0x00, 0x00), 16),
        ReadRange(a(0x20, 0x20, 0x00, 0x00), 16),
        ReadRange(a(0x20, 0x30, 0x00, 0x00), 16),
        ReadRange(a(0x20, 0x40, 0x00, 0x00), 16),
        ReadRange(a(0x20, 0x50, 0x00, 0x00), 16),
        ReadRange(a(0x20, 0x60, 0x00, 0x00), 16),
        ReadRange(a(0x20, 0x70, 0x00, 0x00), 16),
        ReadRange(a(0x21, 0x00, 0x00, 0x00), 16),
        ReadRange(a(0x21, 0x10, 0x00, 0x00), 16),
        ReadRange(a(0x00, 0x00, 0x00, 0x00), 4),
        ReadRange(a(0x10, 0x00, 0x24, 0x00), 1),
        ReadRange(a(0x10, 0x00, 0x26, 0x00), 13),
        // Live/edit area so the UI reflects the amp's current values and we can
        // confirm which addresses are right: amp block, effect on/off, NS.
        ReadRange(a(0x20, 0x00, 0x06, 0x00), 16),
        ReadRange(a(0x20, 0x00, 0x08, 0x00), 16),
        ReadRange(a(0x20, 0x00, 0x58, 0x00), 4),
        // FX-BOX selector bytes (COLOR block): tells which physical slot each
        // effect occupies, so banked effect params resolve to the right address.
        ReadRange(a(0x20, 0x00, 0x04, 0x00), GEN3_SELECTOR_COUNT),
    )

    val READ_RANGES = listOf(
        ReadRange(a(0x00, 0x00, 0x04, 0x20), 0x0A),   // amp panel
        ReadRange(a(0x60, 0x00, 0x00, 0x30), 0x0A),   // booster
        ReadRange(a(0x60, 0x00, 0x01, 0x40), 0x02),   // mod header
        ReadRange(a(0x60, 0x00, 0x03, 0x4C), 0x02),   // fx header
        ReadRange(a(0x60, 0x00, 0x05, 0x60), 0x08),   // delay
        ReadRange(a(0x60, 0x00, 0x06, 0x10), 0x0B),   // reverb
        ReadRange(a(0x60, 0x00, 0x06, 0x63), 0x03),   // noise suppressor
    )

    /** COLOR block that holds the 5 FX-BOX selector bytes. */
    val GEN3_SELECTOR_ADDR = a(0x20, 0x00, 0x04, 0x00)

    // ---- Neutral values + value sanitising ------------------------------
    /**
     * The value a parameter gets when a preset does not mention it.
     *
     * Presets are expanded to the FULL parameter set before being sent (see
     * [com.raznoe.katana.model.FactoryPresets]), so recalling one always lands
     * on a deterministic tone instead of inheriting leftovers from whatever was
     * loaded before — that inheritance was the cause of both "the preset didn't
     * really load" and stray background noise from an effect nobody asked for.
     *
     * [KatanaParam.default] (0 for every knob) is NOT usable here: a Direct Mix
     * of 0 mutes the dry guitar, and a Level of 0 makes the preset sound broken.
     */
    val NEUTRAL: Map<String, Int> = mapOf(
        // Amp: middle-of-the-road clean.
        "amp_type" to 1, "gain" to 50, "volume" to 80,
        "bass" to 50, "middle" to 50, "treble" to 50, "presence" to 50,
        // Booster: off, unity-ish.
        "boost_sw" to 0, "boost_type" to 1, "boost_drive" to 40, "boost_bottom" to 50,
        "boost_tone" to 50, "boost_solo" to 0, "boost_solo_lvl" to 50,
        "boost_level" to 70, "boost_direct" to 0,
        // Mod / FX: off, benign type.
        "mod_sw" to 0, "mod_type" to 29,   // Chorus
        "fx_sw" to 0, "fx_type" to 3,      // Comp
        // Delay: off; dry signal fully through, tame repeats.
        "delay_sw" to 0, "delay_type" to 0, "delay_time" to 400, "delay_fb" to 20,
        "delay_hc" to 12, "delay_level" to 40, "delay_direct" to 100,
        // Reverb: off; dry fully through, no rumble in the tail.
        "reverb_sw" to 0, "reverb_type" to 1, "reverb_time" to 40, "reverb_pre" to 0,
        "reverb_lc" to 6, "reverb_hc" to 11, "reverb_density" to 50,
        "reverb_level" to 35, "reverb_direct" to 100, "reverb_spring" to 50,
        // Noise suppressor: on by default — this amp hisses without it.
        "ns_sw" to 1, "ns_thr" to 30, "ns_rel" to 45,
    )

    fun neutral(p: KatanaParam): Int = NEUTRAL[p.id] ?: when (p.kind) {
        ParamKind.TOGGLE -> 0
        ParamKind.ENUM -> p.valueOfIndex(0)
        ParamKind.CONTINUOUS -> ((p.min + p.max) / 2)
    }

    /**
     * Coerce [value] into something the amp will actually accept for [p].
     *
     * ENUM values matter most: the wire lists have gaps (e.g. Delay has no
     * value 10), and sending a code the amp does not implement leaves the block
     * in an undefined state. We snap to the nearest valid code instead.
     */
    fun sanitize(p: KatanaParam, value: Int): Int = when (p.kind) {
        ParamKind.TOGGLE -> if (value != 0) 1 else 0
        ParamKind.CONTINUOUS -> value.coerceIn(p.min, p.max)
        ParamKind.ENUM ->
            if (p.optionValues.isEmpty()) value.coerceIn(0, p.options.lastIndex.coerceAtLeast(0))
            else if (value in p.optionValues) value
            else p.optionValues.minByOrNull { kotlin.math.abs(it - value) } ?: 0
    }

    /** True if [value] is a code the amp implements for [p]. */
    fun isValid(p: KatanaParam, value: Int): Boolean = sanitize(p, value) == value

    // ---- helpers ---------------------------------------------------------
    private fun a(b0: Int, b1: Int, b2: Int, b3: Int) = intArrayOf(b0, b1, b2, b3)

    private fun cont(id: String, label: String, cat: String, addr: IntArray,
                     max: Int = 100, verified: Boolean = true, g3: IntArray? = null,
                     slots: IntArray? = null, gi: Int = 0, sel: Int = -1) =
        KatanaParam(id, label, cat, addr, ParamKind.CONTINUOUS, max = max,
            verified = verified, addrGen3 = g3, gen3Slots = slots, gen3Index = gi, gen3Sel = sel)

    private fun toggle(id: String, label: String, cat: String, addr: IntArray, g3: IntArray? = null,
                       slots: IntArray? = null, gi: Int = 0, sel: Int = -1) =
        KatanaParam(id, label, cat, addr, ParamKind.TOGGLE, max = 1, verified = true,
            addrGen3 = g3, gen3Slots = slots, gen3Index = gi, gen3Sel = sel)

    private fun enum(id: String, label: String, cat: String, addr: IntArray,
                     items: List<Pair<String, Int>>, verified: Boolean = true, g3: IntArray? = null,
                     slots: IntArray? = null, gi: Int = 0, sel: Int = -1) =
        KatanaParam(
            id, label, cat, addr, ParamKind.ENUM,
            addrGen3 = g3,
            gen3Slots = slots, gen3Index = gi, gen3Sel = sel,
            max = items.size - 1,
            options = items.map { it.first },
            optionValues = items.map { it.second },
            verified = verified,
        )
}

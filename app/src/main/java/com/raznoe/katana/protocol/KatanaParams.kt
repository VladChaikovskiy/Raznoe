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
    /** Gen 3 wire address (decoded from Katana Librarian), if known. */
    val addrGen3: IntArray? = null,
) {
    override fun equals(other: Any?) = other is KatanaParam && other.id == id
    override fun hashCode() = id.hashCode()

    /** The wire address for a given generation (Gen 3 map where available). */
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

    // ---- Booster (60 00 00 30) ------------------------------------------
    val BOOST_SW = toggle("boost_sw", "Booster", BOOST, a(0x60, 0x00, 0x00, 0x30))
    val BOOST_TYPE = enum("boost_type", "Тип", BOOST, a(0x60, 0x00, 0x00, 0x31), BOOSTER)
    val BOOST_DRIVE = cont("boost_drive", "Drive", BOOST, a(0x60, 0x00, 0x00, 0x32))
    val BOOST_BOTTOM = cont("boost_bottom", "Bottom", BOOST, a(0x60, 0x00, 0x00, 0x33))
    val BOOST_TONE = cont("boost_tone", "Tone", BOOST, a(0x60, 0x00, 0x00, 0x34))
    val BOOST_SOLO = toggle("boost_solo", "Solo", BOOST, a(0x60, 0x00, 0x00, 0x35))
    val BOOST_SOLO_LVL = cont("boost_solo_lvl", "Solo Level", BOOST, a(0x60, 0x00, 0x00, 0x36))
    val BOOST_LEVEL = cont("boost_level", "Effect Level", BOOST, a(0x60, 0x00, 0x00, 0x37))
    val BOOST_DIRECT = cont("boost_direct", "Direct Mix", BOOST, a(0x60, 0x00, 0x00, 0x38))

    // ---- Mod (60 00 01 40) — type + on/off (per-type params via Console) --
    val MOD_SW = toggle("mod_sw", "Mod", MOD, a(0x60, 0x00, 0x01, 0x40))
    val MOD_TYPE = enum("mod_type", "Тип", MOD, a(0x60, 0x00, 0x01, 0x41), MOD_FX)

    // ---- FX (60 00 03 4C) ------------------------------------------------
    val FX_SW = toggle("fx_sw", "FX", FX, a(0x60, 0x00, 0x03, 0x4C))
    val FX_TYPE = enum("fx_type", "Тип", FX, a(0x60, 0x00, 0x03, 0x4D), MOD_FX)

    // ---- Delay (60 00 05 60) --------------------------------------------
    val DELAY_SW = toggle("delay_sw", "Delay", DLY, a(0x60, 0x00, 0x05, 0x60))
    val DELAY_TYPE = enum("delay_type", "Тип", DLY, a(0x60, 0x00, 0x05, 0x61), DELAY)
    val DELAY_TIME = KatanaParam(
        "delay_time", "Time (ms)", DLY, a(0x60, 0x00, 0x05, 0x62), ParamKind.CONTINUOUS,
        min = 1, max = 2000, default = 400, word = true, verified = true,
    )
    val DELAY_FEEDBACK = cont("delay_fb", "Feedback", DLY, a(0x60, 0x00, 0x05, 0x64))
    val DELAY_HIGHCUT = enum("delay_hc", "High Cut", DLY, a(0x60, 0x00, 0x05, 0x65),
        HIGH_CUT.mapIndexed { i, s -> s to i })
    val DELAY_LEVEL = cont("delay_level", "Effect Level", DLY, a(0x60, 0x00, 0x05, 0x66))
    val DELAY_DIRECT = cont("delay_direct", "Direct Mix", DLY, a(0x60, 0x00, 0x05, 0x67))

    // ---- Reverb (60 00 06 10) -------------------------------------------
    val REVERB_SW = toggle("reverb_sw", "Reverb", REV, a(0x60, 0x00, 0x06, 0x10))
    val REVERB_TYPE = enum("reverb_type", "Тип", REV, a(0x60, 0x00, 0x06, 0x11), REVERB)
    val REVERB_TIME = cont("reverb_time", "Time", REV, a(0x60, 0x00, 0x06, 0x12), max = 99)
    val REVERB_PREDELAY = KatanaParam(
        "reverb_pre", "Pre-Delay (ms)", REV, a(0x60, 0x00, 0x06, 0x13), ParamKind.CONTINUOUS,
        min = 0, max = 500, default = 0, word = true, verified = true,
    )
    val REVERB_LOWCUT = enum("reverb_lc", "Low Cut", REV, a(0x60, 0x00, 0x06, 0x15),
        LOW_CUT.mapIndexed { i, s -> s to i })
    val REVERB_HIGHCUT = enum("reverb_hc", "High Cut", REV, a(0x60, 0x00, 0x06, 0x16),
        HIGH_CUT.mapIndexed { i, s -> s to i })
    val REVERB_DENSITY = cont("reverb_density", "Density", REV, a(0x60, 0x00, 0x06, 0x17))
    val REVERB_LEVEL = cont("reverb_level", "Effect Level", REV, a(0x60, 0x00, 0x06, 0x18))
    val REVERB_DIRECT = cont("reverb_direct", "Direct Mix", REV, a(0x60, 0x00, 0x06, 0x19))
    val REVERB_SPRING = cont("reverb_spring", "Spring Sens", REV, a(0x60, 0x00, 0x06, 0x1A))

    // ---- Noise Suppressor (60 00 06 63) ---------------------------------
    val NS_SW = toggle("ns_sw", "Noise Suppressor", NS, a(0x60, 0x00, 0x06, 0x63))
    val NS_THRESHOLD = cont("ns_thr", "Threshold", NS, a(0x60, 0x00, 0x06, 0x64))
    val NS_RELEASE = cont("ns_rel", "Release", NS, a(0x60, 0x00, 0x06, 0x65))

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

    // ---- helpers ---------------------------------------------------------
    private fun a(b0: Int, b1: Int, b2: Int, b3: Int) = intArrayOf(b0, b1, b2, b3)

    private fun cont(id: String, label: String, cat: String, addr: IntArray,
                     max: Int = 100, verified: Boolean = true, g3: IntArray? = null) =
        KatanaParam(id, label, cat, addr, ParamKind.CONTINUOUS, max = max,
            verified = verified, addrGen3 = g3)

    private fun toggle(id: String, label: String, cat: String, addr: IntArray, g3: IntArray? = null) =
        KatanaParam(id, label, cat, addr, ParamKind.TOGGLE, max = 1, verified = true, addrGen3 = g3)

    private fun enum(id: String, label: String, cat: String, addr: IntArray,
                     items: List<Pair<String, Int>>, verified: Boolean = true, g3: IntArray? = null) =
        KatanaParam(
            id, label, cat, addr, ParamKind.ENUM,
            addrGen3 = g3,
            max = items.size - 1,
            options = items.map { it.first },
            optionValues = items.map { it.second },
            verified = verified,
        )
}

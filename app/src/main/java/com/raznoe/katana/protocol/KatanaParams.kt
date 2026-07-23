package com.raznoe.katana.protocol

/**
 * The Katana parameter map.
 *
 * ⚠️ IMPORTANT — READ THIS BEFORE TRUSTING THE ADDRESSES ⚠️
 *
 * The SysEx *framing* (header/commands/checksum in [KatanaSysEx]) is stable
 * across all Katana generations, but the concrete parameter ADDRESSES below
 * were derived from the community Katana MkII (Gen2) reverse engineering and
 * are treated here as PROVISIONAL for Gen 3. Roland has never published the
 * spec, and the Gen 3 map is still being confirmed by the community.
 *
 * This is deliberately a single, easy-to-edit table. To adapt to Gen 3:
 *   1. Use the "SysEx console / block read" screen to RQ1-read the temp-patch
 *      area, tweak one physical knob on the amp, read again, and diff to find
 *      the address that changed.
 *   2. Update the matching [KatanaParam.address] below and set verified = true.
 *
 * Addresses are 4 bytes. The Katana's live/edit ("temporary patch") area lives
 * under the 0x60 00 xx xx page — the one fully-documented anchor is the reverb
 * *type* at 60 00 12 14, which the community spec confirms.
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
    /** Labels for ENUM params, index == value. */
    val options: List<String> = emptyList(),
    /** false = address is a Gen2-derived guess still to be confirmed on Gen 3. */
    val verified: Boolean = false,
) {
    override fun equals(other: Any?) = other is KatanaParam && other.id == id
    override fun hashCode() = id.hashCode()
}

object KatanaParams {

    // Amp models exposed by the Katana "amp type" selector (order is the
    // commonly-cited Gen2 order; confirm/extend for Gen 3).
    val AMP_TYPES = listOf(
        "Acoustic", "Clean", "Crunch", "Lead", "Brown",
    )

    val REVERB_TYPES = listOf(
        "Room", "Hall", "Plate", "Spring", "Modulate",
    )

    // --- Amp / tone stack -------------------------------------------------
    val AMP_TYPE = KatanaParam(
        "amp_type", "Amp type", "AMP",
        intArrayOf(0x60, 0x00, 0x00, 0x00), ParamKind.ENUM,
        max = AMP_TYPES.lastIndex, options = AMP_TYPES,
    )
    val GAIN = param("gain", "Gain", "AMP", 0x60, 0x00, 0x00, 0x01)
    val VOLUME = param("volume", "Volume", "AMP", 0x60, 0x00, 0x00, 0x02)
    val BASS = param("bass", "Bass", "AMP", 0x60, 0x00, 0x00, 0x03)
    val MIDDLE = param("middle", "Middle", "AMP", 0x60, 0x00, 0x00, 0x04)
    val TREBLE = param("treble", "Treble", "AMP", 0x60, 0x00, 0x00, 0x05)
    val PRESENCE = param("presence", "Presence", "AMP", 0x60, 0x00, 0x00, 0x06)
    val MASTER = param("master", "Master", "AMP", 0x60, 0x00, 0x00, 0x07)

    // --- Effect on/off blocks --------------------------------------------
    val BOOSTER_SW = toggle("booster_sw", "Booster", "FX", 0x60, 0x00, 0x06, 0x00)
    val MOD_SW = toggle("mod_sw", "Mod", "FX", 0x60, 0x00, 0x08, 0x00)
    val FX_SW = toggle("fx_sw", "FX", "FX", 0x60, 0x00, 0x0A, 0x00)
    val DELAY_SW = toggle("delay_sw", "Delay", "FX", 0x60, 0x00, 0x0C, 0x00)
    val REVERB_SW = toggle("reverb_sw", "Reverb", "FX", 0x60, 0x00, 0x12, 0x00)

    // --- Reverb (the one fully-confirmed address anchor) ------------------
    val REVERB_TYPE = KatanaParam(
        "reverb_type", "Reverb type", "REVERB",
        intArrayOf(0x60, 0x00, 0x12, 0x14), ParamKind.ENUM,
        max = REVERB_TYPES.lastIndex, options = REVERB_TYPES,
        verified = true, // documented example in the community spec
    )

    /** Everything, in display order. */
    val ALL: List<KatanaParam> = listOf(
        AMP_TYPE, GAIN, VOLUME, BASS, MIDDLE, TREBLE, PRESENCE, MASTER,
        BOOSTER_SW, MOD_SW, FX_SW, DELAY_SW, REVERB_SW,
        REVERB_TYPE,
    )

    val BY_CATEGORY: Map<String, List<KatanaParam>> =
        ALL.groupBy { it.category }

    val BY_ID: Map<String, KatanaParam> = ALL.associateBy { it.id }

    /**
     * The temp-patch block used for "read current state" and for the diff
     * workflow that maps unknown Gen 3 addresses. Reading a healthy chunk of
     * the 0x60 00 00 00 page returns the live values.
     */
    val TEMP_PATCH_BASE = intArrayOf(0x60, 0x00, 0x00, 0x00)
    const val TEMP_PATCH_READ_SIZE = 0x80

    private fun param(id: String, label: String, cat: String, a0: Int, a1: Int, a2: Int, a3: Int) =
        KatanaParam(id, label, cat, intArrayOf(a0, a1, a2, a3), ParamKind.CONTINUOUS, max = 100)

    private fun toggle(id: String, label: String, cat: String, a0: Int, a1: Int, a2: Int, a3: Int) =
        KatanaParam(id, label, cat, intArrayOf(a0, a1, a2, a3), ParamKind.TOGGLE, max = 1)
}

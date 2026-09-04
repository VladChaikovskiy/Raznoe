package com.raznoe.katana

import com.raznoe.katana.protocol.KatanaParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the address map to the reverse-engineered SysEx specification
 * (docs/katana-address-map.md).
 *
 * The amp panel being off by one is the single bug behind every tone complaint
 * this app has had — the amp type was never written, so the amp stayed on
 * whatever character it was physically set to and no preset could change it.
 * These tests exist so that cannot come back unnoticed.
 */
class AddressMapTest {

    /** Front Panel: type first at 04 20, then gain, volume, bass, mid, treble, presence. */
    @Test fun ampPanel_matchesTheDocumentedBlock() {
        val expected = listOf(
            KatanaParams.AMP_TYPE to 0x20,
            KatanaParams.GAIN to 0x21,
            KatanaParams.VOLUME to 0x22,
            KatanaParams.BASS to 0x23,
            KatanaParams.MIDDLE to 0x24,
            KatanaParams.TREBLE to 0x25,
            KatanaParams.PRESENCE to 0x26,
        )
        for ((param, low) in expected) {
            assertArrayEquals(
                "MkII адрес ${param.id}",
                intArrayOf(0x00, 0x00, 0x04, low),
                param.addressFor(gen3 = false),
            )
        }
    }

    /** Gen 3 keeps the same in-block order, based at 20 00 06 00. */
    @Test fun gen3AmpPanel_keepsTheSameOrder() {
        val order = listOf(
            KatanaParams.AMP_TYPE, KatanaParams.GAIN, KatanaParams.VOLUME,
            KatanaParams.BASS, KatanaParams.MIDDLE, KatanaParams.TREBLE,
            KatanaParams.PRESENCE,
        )
        order.forEachIndexed { i, param ->
            assertArrayEquals(
                "Gen 3 адрес ${param.id}",
                intArrayOf(0x20, 0x00, 0x06, i),
                param.addrGen3,
            )
        }
    }

    /** Five amp characters, codes 0..4. A sixth entry shifted everything. */
    @Test fun ampTypes_areTheFiveTheAmpImplements() {
        assertEquals(
            listOf("Acoustic", "Clean", "Crunch", "Lead", "Brown"),
            KatanaParams.AMP_TYPES,
        )
        assertEquals(listOf(0, 1, 2, 3, 4), KatanaParams.AMP_TYPE.optionValues)
        assertEquals(1, KatanaParams.AMP_TYPE.valueOfIndex(1)) // Clean
        assertEquals(4, KatanaParams.AMP_TYPE.valueOfIndex(4)) // Brown
        // Nothing above 4 may ever reach the amp as a type.
        assertEquals(4, KatanaParams.sanitize(KatanaParams.AMP_TYPE, 5))
        assertEquals(4, KatanaParams.sanitize(KatanaParams.AMP_TYPE, 60))
    }

    /** Effect addresses the spec gives explicitly, spot-checked. */
    @Test fun effectAddresses_matchTheSpec() {
        val cases = mapOf(
            "boost_drive" to intArrayOf(0x60, 0x00, 0x00, 0x32),
            "boost_level" to intArrayOf(0x60, 0x00, 0x00, 0x37),
            "boost_direct" to intArrayOf(0x60, 0x00, 0x00, 0x38),
            "delay_time" to intArrayOf(0x60, 0x00, 0x05, 0x62),
            "delay_fb" to intArrayOf(0x60, 0x00, 0x05, 0x64),
            "delay_hc" to intArrayOf(0x60, 0x00, 0x05, 0x65),
            "reverb_time" to intArrayOf(0x60, 0x00, 0x06, 0x12),
            "reverb_pre" to intArrayOf(0x60, 0x00, 0x06, 0x13),
            "reverb_density" to intArrayOf(0x60, 0x00, 0x06, 0x17),
            "ns_sw" to intArrayOf(0x60, 0x00, 0x06, 0x63),
            "ns_thr" to intArrayOf(0x60, 0x00, 0x06, 0x64),
            "ns_rel" to intArrayOf(0x60, 0x00, 0x06, 0x65),
        )
        for ((id, addr) in cases) {
            val p = KatanaParams.BY_ID[id]
            assertNull("нет параметра $id", if (p == null) id else null)
            assertArrayEquals(id, addr, p!!.addressFor(gen3 = false))
        }
    }

    /** Two ranges the spec gives differently from the usual 0..100. */
    @Test fun rangesFollowTheSpec() {
        assertEquals(120, KatanaParams.BY_ID["delay_level"]!!.max) // 00..78
        assertEquals(10, KatanaParams.BY_ID["reverb_density"]!!.max) // 00..0A
        assertEquals(99, KatanaParams.BY_ID["reverb_time"]!!.max) // 00..63
        assertEquals(2000, KatanaParams.BY_ID["delay_time"]!!.max)
    }

    /** The documented CCs, and which block each one switches. */
    @Test fun toggleCcs_matchTheSpec() {
        assertEquals(16, KatanaParams.CC_BOOST_MOD)
        assertEquals(17, KatanaParams.CC_DELAY_FX)
        assertEquals(18, KatanaParams.CC_REVERB)
        assertEquals(19, KatanaParams.CC_LOOP)
        // BOOST/MOD is one knob with two ranges, so both share CC 16.
        assertEquals(16, KatanaParams.TOGGLE_CC["boost_sw"])
        assertEquals(16, KatanaParams.TOGGLE_CC["mod_sw"])
        assertEquals(17, KatanaParams.TOGGLE_CC["delay_sw"])
        assertEquals(17, KatanaParams.TOGGLE_CC["fx_sw"])
        assertEquals(18, KatanaParams.TOGGLE_CC["reverb_sw"])
        // The gate has no documented CC; it stays on its address.
        assertNull(KatanaParams.TOGGLE_CC["ns_sw"])
    }

    /** The panel snapshot block the spec names for "virtual presets". */
    @Test fun readRanges_includeTheDocumentedSnapshotBlock() {
        val panel = KatanaParams.READ_RANGES.firstOrNull {
            it.address.contentEquals(intArrayOf(0x00, 0x00, 0x04, 0x00))
        }
        assertNull("нет блока 00 00 04 00", if (panel == null) "missing" else null)
        assertEquals(0x2A, panel!!.size)
    }

    /**
     * The amp has two amp-type fields with incompatible codes, and confusing
     * them is a way to select the acoustic simulator while asking for Clean.
     * Panel: 0 Acoustic..4 Brown. Preamp models: 0x01 FULL RANGE, 0x08 JC-120,
     * 0x0B TWEED, 0x18 5150 DRIVE, 0x17 SLDN.
     */
    @Test fun preampModels_matchEachPanelCharacter() {
        assertEquals(
            listOf(0x01, 0x08, 0x0B, 0x18, 0x17),
            KatanaParams.PREAMP_TYPE_FOR_PANEL.toList(),
        )
        assertEquals(KatanaParams.AMP_TYPES.size, KatanaParams.PREAMP_TYPE_FOR_PANEL.size)
        assertArrayEquals(intArrayOf(0x60, 0x00, 0x00, 0x51), KatanaParams.PREAMP_TYPE_ADDR)
        // Clean must never be sent as 1 into the preamp field: that is FULL RANGE.
        assertEquals(0x08, KatanaParams.PREAMP_TYPE_FOR_PANEL[1])
    }

    /** A value above 4 can only have come from the 28-model field. */
    @Test fun preampSpace_isRecognisedByAnOutOfPanelRangeValue() {
        for (v in 0..4) {
            assertEquals("панельное $v", false, KatanaParams.isPreampSpaceValue(v))
        }
        for (v in listOf(5, 8, 0x0B, 0x18, 27)) {
            assertEquals("расширенное $v", true, KatanaParams.isPreampSpaceValue(v))
        }
    }
}

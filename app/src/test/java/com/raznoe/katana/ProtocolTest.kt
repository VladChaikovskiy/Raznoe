package com.raznoe.katana

import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.KatanaSysEx
import com.raznoe.katana.usb.UsbMidiPacketizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the SysEx protocol, dialect detection and the Gen 3
 * address arithmetic. No Android dependencies, so they run on the JVM in CI.
 */
class ProtocolTest {

    @After fun reset() = KatanaSysEx.resetProfile()

    @Test fun checksum_matchesDocumentedExample() {
        assertEquals(0x79, KatanaSysEx.checksum(intArrayOf(0x60, 0x00, 0x12, 0x14, 0x01)))
    }

    @Test fun buildSet_framesAndChecksums() {
        val msg = KatanaSysEx.buildSet(KatanaParams.REVERB_TYPE.address, 1)
        assertEquals(0xF0, msg.first().toInt() and 0xFF)
        assertEquals(0xF7, msg.last().toInt() and 0xFF)
        assertEquals(0x12, msg[7].toInt() and 0xFF) // DT1
        val body = intArrayOf(0x60, 0x00, 0x06, 0x11, 0x01)
        val ck = msg[msg.size - 2].toInt() and 0xFF
        assertEquals(0, (body.sum() + ck) % 128)
    }

    @Test fun parse_roundTripsAddressAndData() {
        val msg = KatanaSysEx.buildSet(KatanaParams.REVERB_TYPE.address, 1)
        val inc = KatanaSysEx.parse(msg)
        assertNotNull(inc)
        assertEquals(listOf(0x60, 0x00, 0x06, 0x11), inc!!.address.toList())
        assertEquals(listOf(1), inc.data.toList())
    }

    @Test fun enum_mapsIndexToGappedWireValue() {
        val mod = KatanaParams.MOD_TYPE
        val chorus = mod.options.indexOf("Chorus")
        assertEquals(29, mod.valueOfIndex(chorus))
        assertEquals(chorus, mod.indexOfValue(29))
    }

    @Test fun wordParam_survives2ByteEncode() {
        val hi = (400 shr 7) and 0x7F
        val lo = 400 and 0x7F
        val msg = KatanaSysEx.buildSet(KatanaParams.DELAY_TIME.address, intArrayOf(hi, lo))
        val inc = KatanaSysEx.parse(msg)!!
        val decoded = ((inc.data[0] and 0x7F) shl 7) or (inc.data[1] and 0x7F)
        assertEquals(400, decoded)
    }

    @Test fun usbMidi_packetizationRoundTrips() {
        val msg = KatanaSysEx.buildSet(KatanaParams.GAIN.address, 42)
        val packets = UsbMidiPacketizer.encodeSysEx(msg)
        assertEquals(0, packets.size % 4)
        val restored = ArrayList<Byte>()
        val re = UsbMidiPacketizer.SysExReassembler { restored.addAll(it.toList()) }
        var off = 0
        while (off < packets.size) { re.push(packets.copyOfRange(off, off + 4), 4); off += 4 }
        assertEquals(msg.toList(), restored.toByteArray().toList())
    }

    @Test fun identityRequest_isUniversal() {
        assertEquals("F0 7E 7F 06 01 F7", KatanaSysEx.toHex(KatanaSysEx.identityRequest()))
    }

    @Test fun identityReply_selectsGen3Dialect() {
        val reply = KatanaSysEx.fromHex("F0 7E 00 06 02 41 07 05 00 00 05 00 00 00 F7")
        assertTrue(KatanaSysEx.adoptFromIdentity(reply))
        assertEquals(KatanaSysEx.Gen.GEN3, KatanaSysEx.generation)
        val g3 = KatanaSysEx.buildSet(intArrayOf(0x20, 0x10, 0x00, 0x00), 42)
        assertTrue(KatanaSysEx.toHex(g3).startsWith("F0 41 00 01 05 07 12"))
    }

    @Test fun identityReply_selectsMkiiDialect() {
        val reply = KatanaSysEx.fromHex("F0 7E 00 06 02 41 33 02 00 00 00 00 00 00 F7")
        assertTrue(KatanaSysEx.adoptFromIdentity(reply))
        assertEquals(KatanaSysEx.Gen.MKII, KatanaSysEx.generation)
    }

    @Test fun rq1_sizeIsBigEndian() {
        val q = KatanaSysEx.buildQuery(intArrayOf(0x60, 0x00, 0x00, 0x00), 300)
        assertEquals(
            listOf(0x00, 0x00, 0x01, 0x2C),
            listOf(q[12], q[13], q[14], q[15]).map { it.toInt() and 0xFF },
        )
    }

    @Test fun gen3AddressMath_matchesDecodedAppValues() {
        // Confirmed on real hardware / decoded from Katana Librarian.
        assertEquals(listOf(0x20, 0x00, 0x06, 0x00), KatanaSysEx.gen3AddrFromBase(1536, 0).toList())
        assertEquals(listOf(0x20, 0x00, 0x0A, 0x00), KatanaSysEx.gen3AddrFromBase(2560, 0).toList())
        assertEquals(listOf(0x20, 0x00, 0x28, 0x01), KatanaSysEx.gen3AddrFromBase(10240, 1).toList())
        assertEquals(listOf(0x20, 0x00, 0x34, 0x00), KatanaSysEx.gen3AddrFromBase(13312, 0).toList())
        assertEquals(listOf(0x20, 0x00, 0x58, 0x01), KatanaSysEx.gen3AddrFromBase(22528, 1).toList())
    }

    @Test fun bankedEffects_carrySlotMetadata() {
        assertEquals(listOf(2560, 3072, 3584), KatanaParams.BOOST_TYPE.gen3Slots?.toList())
        assertEquals(KatanaParams.SEL_FX1A, KatanaParams.BOOST_TYPE.gen3Sel)
        assertEquals(listOf(4096, 4608, 5120), KatanaParams.MOD_TYPE.gen3Slots?.toList())
        assertEquals(listOf(5632, 6144, 6656), KatanaParams.FX_TYPE.gen3Slots?.toList())
        assertEquals(listOf(0x20, 0x00, 0x58, 0x01), KatanaParams.NS_THRESHOLD.addrGen3?.toList())
    }

    @Test fun paramMap_isConsistent() {
        assertTrue(KatanaParams.ALL.all { it.address.size == 4 })
        assertEquals(KatanaParams.ALL.size, KatanaParams.ALL.map { it.id }.toSet().size)
    }
}

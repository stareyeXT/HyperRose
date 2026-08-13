package com.dohex.hyperrose.profile

import com.dohex.hyperrose.profile.rose_cambrian.RoseCambrianProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoseCambrianResponseParserTest {

    private val protocol = RoseCambrianProfile.protocol

    @Test
    fun `type4 single byte battery produces overall`() {
        // DD 00 04 0C 3C AA -> 0x3C = 60
        val frame = byteArrayOf(
            0xDD.toByte(), 0x00, 0x04, 0x0C, 0x3C, 0xAA.toByte(),
        )
        val battery = protocol.parseResponse(frame)
            .filterIsInstance<DeviceResponse.Battery>()
        assertEquals(1, battery.size)
        assertEquals(60, battery[0].info.overall)
    }

    @Test
    fun `type4 out of range byte produces Unknown`() {
        // DD 00 04 0C FF AA -> 0xFF 越界
        val frame = byteArrayOf(
            0xDD.toByte(), 0x00, 0x04, 0x0C, 0xFF.toByte(), 0xAA.toByte(),
        )
        val results = protocol.parseResponse(frame)
        assertTrue("Expected Unknown, got $results", results.all { it is DeviceResponse.Unknown })
    }

    @Test
    fun `status tlv single nonzero among three produces overall`() {
        // DD 00 15 [00 04 0C FF 3C FF] AA —— len=04 type=0C values=FF 3C FF
        val frame = byteArrayOf(
            0xDD.toByte(), 0x00, 0x15, 0x04, 0x0C,
            0xFF.toByte(), 0x3C, 0xFF.toByte(), 0xAA.toByte(),
        )
        val battery = protocol.parseResponse(frame)
            .filterIsInstance<DeviceResponse.Battery>()
        assertEquals(1, battery.size)
        assertEquals(60, battery[0].info.overall)
    }
}

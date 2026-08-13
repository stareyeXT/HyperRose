package com.dohex.hyperrose.profile

import com.dohex.hyperrose.profile.budsfeel_lite.BudsFeelLiteProfile
import com.dohex.hyperrose.profile.budsfeel_mk2.BudsFeelMk2Profile
import com.dohex.hyperrose.profile.rose_cambrian.RoseCambrianProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MalformedProtocolResponseTest {
    @Test
    fun `truncated tlvs are ignored without throwing`() {
        val checksummed = byteArrayOf(
            0xDD.toByte(), 0x00, 0x15, 0x02, 0x09, 0xFD.toByte(), 0xAA.toByte(),
        )
        val cambrian = byteArrayOf(
            0xDD.toByte(), 0x00, 0x15, 0x02, 0x09, 0xAA.toByte(),
        )

        listOf(
            BudsFeelMk2Profile.protocol.parseResponse(checksummed),
            BudsFeelLiteProfile.protocol.parseResponse(checksummed),
            RoseCambrianProfile.protocol.parseResponse(cambrian),
        ).forEach { results ->
            assertTrue(results.isEmpty() || results.all { it is DeviceResponse.Unknown })
        }
    }

    @Test
    fun `unknown mk2 ear battery is not exposed as 255 percent`() {
        val data = byteArrayOf(
            0xDD.toByte(), 0x00, 0x15, 0x04, 0x0C, 0xFF.toByte(), 0x64, 0xFF.toByte(),
        )
        val checksum = (data.sum() and 0xFF).toByte()
        val results = BudsFeelMk2Profile.protocol.parseResponse(
            data + byteArrayOf(checksum, 0xAA.toByte()),
        )
        val battery = results.filterIsInstance<DeviceResponse.Battery>().single()

        assertNull(battery.info.left)
        assertEquals(100, battery.info.right?.level)
        assertNull(battery.info.caseBattery)
    }

    @Test
    fun `unknown enum values do not overwrite device state`() {
        val checksummedPayload = byteArrayOf(
            0xDD.toByte(), 0x00, 0x02, 0x09, 0x7F,
        )
        val checksum = (checksummedPayload.sum() and 0xFF).toByte()
        val checksummedFrame = checksummedPayload + byteArrayOf(checksum, 0xAA.toByte())
        val cambrianFrame = checksummedPayload + byteArrayOf(0xAA.toByte())

        listOf(
            BudsFeelMk2Profile.protocol.parseResponse(checksummedFrame),
            BudsFeelLiteProfile.protocol.parseResponse(checksummedFrame),
            RoseCambrianProfile.protocol.parseResponse(cambrianFrame),
        ).forEach { results ->
            assertTrue(results.all { it is DeviceResponse.Unknown })
        }
    }

    @Test
    fun `unknown cambrian battery is not exposed as 255 percent`() {
        val frame = byteArrayOf(
            0xDD.toByte(), 0x00, 0x15, 0x04, 0x0C,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xAA.toByte(),
        )

        val batteries = RoseCambrianProfile.protocol.parseResponse(frame)
            .filterIsInstance<DeviceResponse.Battery>()

        assertTrue(batteries.isEmpty())
    }
}

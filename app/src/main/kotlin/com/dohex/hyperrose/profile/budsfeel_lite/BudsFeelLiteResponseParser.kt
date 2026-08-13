package com.dohex.hyperrose.profile.budsfeel_lite

import com.dohex.hyperrose.model.AncMode
import com.dohex.hyperrose.model.EarBatteryState
import com.dohex.hyperrose.model.TwsBatteryState
import com.dohex.hyperrose.model.asBatteryLevelOrNull
import com.dohex.hyperrose.profile.DeviceResponse

object BudsFeelLiteResponseParser {
    fun parse(data: ByteArray): List<DeviceResponse> {
        if (data.size < 5) return listOf(DeviceResponse.Unknown)

        val header = data[0].toInt() and 0xFF
        if (header != 0xDD) return listOf(DeviceResponse.Unknown)

        val expectedCk = (data.dropLast(2).sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        if (data[data.size - 2] != expectedCk || data[data.size - 1] != 0xAA.toByte())
            return listOf(DeviceResponse.Unknown)

        val responseType = data[2].toInt() and 0xFF
        return when (responseType) {
            0x15 -> parseStatusResponse(data)
            0x02 -> listOf(parseNotification(data))
            else -> listOf(DeviceResponse.Unknown)
        }
    }

    private fun parseStatusResponse(data: ByteArray): List<DeviceResponse> {
        val results = mutableListOf<DeviceResponse>()
        var i = 3
        val end = data.size - 2
        while (i < end - 1) {
            val len = data[i].toInt() and 0xFF
            if (len < 2 || i + len >= end) { i++; continue }

            val ptype = data[i + 1].toInt() and 0xFF
            when (ptype) {
                0x09 -> {
                    val v = data[i + 2].toInt() and 0xFF
                    results.add(parseAncValue(v))
                }
                0x0E -> {
                    results.add(DeviceResponse.GameMode(data[i + 2].toInt() == 0x01))
                }
                0x0C -> {
                    if (len >= 4 && i + 4 < end) {
                        val left = (data[i + 2].toInt() and 0xFF).asBatteryLevelOrNull()
                        val right = (data[i + 3].toInt() and 0xFF).asBatteryLevelOrNull()
                        val caseLevel = (data[i + 4].toInt() and 0xFF).asBatteryLevelOrNull()
                        if (left != null || right != null || caseLevel != null) {
                            results.add(DeviceResponse.Battery(TwsBatteryState(
                                left = left?.let { EarBatteryState(it, false) },
                                right = right?.let { EarBatteryState(it, false) },
                                caseBattery = caseLevel,
                            )))
                        }
                    }
                }
            }
            i += len + 1
        }
        return results
    }

    private fun parseNotification(data: ByteArray): DeviceResponse {
        if (data.size < 7) return DeviceResponse.Unknown
        val ptype = data[3].toInt() and 0xFF
        val value = data[4].toInt() and 0xFF
        return when (ptype) {
            0x09 -> parseAncValue(value)
            0x0E -> DeviceResponse.GameMode(value == 0x01)
            else -> DeviceResponse.Unknown
        }
    }

    private fun parseAncValue(value: Int): DeviceResponse = when (value) {
        0x01 -> DeviceResponse.Anc(AncMode.NOISE_CANCEL)
        0x02 -> DeviceResponse.Anc(AncMode.NORMAL)
        0x03 -> DeviceResponse.Anc(AncMode.TRANSPARENT)
        0x04 -> DeviceResponse.Anc(AncMode.WIND_NOISE)
        else -> DeviceResponse.Unknown
    }
}

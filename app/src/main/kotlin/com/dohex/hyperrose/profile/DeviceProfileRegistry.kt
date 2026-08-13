package com.dohex.hyperrose.profile

import android.annotation.SuppressLint
import com.dohex.hyperrose.profile.budsfeel_lite.BudsFeelLiteProfile
import com.dohex.hyperrose.profile.budsfeel_mk2.BudsFeelMk2Profile
import com.dohex.hyperrose.profile.rose_cambrian.RoseCambrianProfile

object DeviceProfileRegistry {
    /** All known device profiles, ordered by priority (first match wins). */
    val profiles: List<DeviceProfile> = listOf(
        EarfreeI5Profile,
        BudsFeelMk2Profile,
        BudsFeelLiteProfile,
        RoseCambrianProfile,
    )

    /** Default profile to display when no device is connected. */
    val defaultProfile: DeviceProfile get() = profiles.first()

    /** Find the first profile whose [DeviceProfile.nameKeywords] match [deviceName]. */
    fun findByName(deviceName: String): DeviceProfile? =
        profiles.firstOrNull { it.matchesDeviceName(deviceName) }

    /** Find profile by its [DeviceProfile.id]. */
    fun findById(id: String): DeviceProfile? =
        profiles.firstOrNull { it.id == id }

    fun findByGattServiceUuid(uuid: java.util.UUID): DeviceProfile? =
        profiles.firstOrNull { uuid == it.serviceUuid }

    @SuppressLint("MissingPermission")
    fun findByDevice(device: android.bluetooth.BluetoothDevice): DeviceProfile? {
        runCatching { device.uuids }.getOrNull()?.forEach { parcelUuid ->
            findByGattServiceUuid(parcelUuid.uuid)?.let { return it }
        }
        val name = runCatching { device.name }.getOrNull()
            ?: runCatching { device.alias }.getOrNull()
            ?: return null
        return findByName(name)
    }
}

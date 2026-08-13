package com.dohex.hyperrose.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.dohex.hyperrose.hook.HyperRoseModuleEntry.Companion.TAG
import com.dohex.hyperrose.profile.DeviceProfile
import com.dohex.hyperrose.profile.TransportSpec
import io.github.libxposed.api.XposedModule

/**
 * BLE GATT 传输实现 — 在 com.android.bluetooth 进程内运行。
 */
@SuppressLint("MissingPermission")
class GattDeviceSession(
    context: Context,
    module: XposedModule,
    profile: DeviceProfile,
) : DeviceSession(context, module, profile) {

    override val isConnected: Boolean get() = ready

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var ready = false

    private val gattSpec: TransportSpec.Gatt
        get() = profile.transport as TransportSpec.Gatt

    override fun connect(device: BluetoothDevice) {
        connectedDevice = device
        module.log(Log.INFO, TAG, "GattDeviceSession: connecting to ${device.address}")
        ready = false
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        cleanupSession()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        ready = false
        connectedDevice = null
        currentBattery = null
        currentAnc = null
        currentAncDepth = null
        currentTransLevel = null
        currentEq = null
        currentGameMode = null
        currentLowLatency = null
    }

    override fun sendCommand(packet: ByteArray, description: String) {
        val char = writeChar ?: run {
            module.log(Log.WARN, TAG, "!!! sendCommand dropped: writeChar is null ($description)")
            return
        }
        val g = gatt ?: run {
            module.log(Log.WARN, TAG, "!!! sendCommand dropped: gatt is null ($description)")
            return
        }
        val hex = packet.toHexString()
        module.log(Log.DEBUG, TAG, "→ $hex")
        logTx(hex, description)
        char.value = packet
        if (!g.writeCharacteristic(char)) {
            module.log(Log.WARN, TAG, "!!! writeCharacteristic returned false ($description)")
        }
    }

    // ==================== GATT Callback ====================

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (gatt !== this@GattDeviceSession.gatt) {
                    gatt.close()
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failConnection("connection failed: $status")
                        return
                    }
                    module.log(Log.INFO, TAG, "GattDeviceSession: connected, discovering services")
                    if (!gatt.discoverServices()) failConnection("service discovery rejected")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    module.log(Log.INFO, TAG, "GattDeviceSession: disconnected")
                    disconnect()
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                if (gatt !== this@GattDeviceSession.gatt) {
                    gatt.close()
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    module.log(
                        Log.ERROR,
                        TAG,
                        "GattDeviceSession: service discovery failed: $status"
                    )
                    failConnection("service discovery failed")
                    return
                }

                val service = gatt.getService(gattSpec.serviceUuid)
                if (service == null) {
                    module.log(
                        Log.ERROR,
                        TAG,
                        "GattDeviceSession: service ${gattSpec.serviceUuid} not found"
                    )
                    failConnection("service not found")
                    return
                }

                writeChar = service.getCharacteristic(gattSpec.writeCharUuid)
                if (writeChar == null) {
                    module.log(
                        Log.ERROR,
                        TAG,
                        "GattDeviceSession: write char ${gattSpec.writeCharUuid} not found"
                    )
                    failConnection("write characteristic not found")
                    return
                }
                @Suppress("DEPRECATION")
                writeChar?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                val notifyChar = service.getCharacteristic(gattSpec.notifyCharUuid)
                if (notifyChar == null) {
                    module.log(
                        Log.ERROR,
                        TAG,
                        "GattDeviceSession: notify char ${gattSpec.notifyCharUuid} not found"
                    )
                    failConnection("notify characteristic not found")
                    return
                }

                if (!gatt.setCharacteristicNotification(notifyChar, true)) {
                    failConnection("enabling notifications failed")
                    return
                }
                val descriptor = notifyChar.getDescriptor(gattSpec.cccdUuid)
                if (descriptor == null) {
                    failConnection("notification descriptor not found")
                    return
                }
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!gatt.writeDescriptor(descriptor)) {
                    failConnection("notification descriptor write rejected")
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (gatt !== this@GattDeviceSession.gatt || descriptor.uuid != gattSpec.cccdUuid) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection("notification descriptor write failed: $status")
                    return
                }
                ready = true
                module.log(Log.INFO, TAG, "GattDeviceSession: GATT ready, querying initial status")
                broadcastDeviceConnected()
                handler.postDelayed(
                    { queryAllStatus() },
                    profile.gattTiming?.initialStatusQueryDelayMs ?: 120L
                )
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (gatt !== this@GattDeviceSession.gatt) return
                val data = characteristic.value ?: return
                handleResponse(data)
            }
        }

    private fun failConnection(reason: String) {
        module.log(Log.ERROR, TAG, "GattDeviceSession: $reason")
        disconnect()
    }
}

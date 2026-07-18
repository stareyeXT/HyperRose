package com.dohex.hyperrose.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.dohex.hyperrose.hook.HyperRoseModuleEntry.Companion.TAG
import com.dohex.hyperrose.profile.DeviceProfile
import com.dohex.hyperrose.profile.TransportSpec
import io.github.libxposed.api.XposedModule
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class RfcommDeviceSession(
    context: Context,
    module: XposedModule,
    profile: DeviceProfile,
) : DeviceSession(context, module, profile) {

    override val isConnected: Boolean get() = dataSocket?.isConnected == true

    private var dataSocket: BluetoothSocket? = null
    private var readerThread: Thread? = null
    private var connectThread: Thread? = null
    private val pendingCommands = ConcurrentLinkedQueue<Pair<ByteArray, String>>()
    private var running = false

    override fun connect(device: BluetoothDevice) {
        connectedDevice = device
        module.log(Log.INFO, TAG, "RfcommDeviceSession: connecting to ${device.address}")
        registerRefreshReceiver()

        val transport = profile.transport as TransportSpec.Rfcomm
        connectThread = Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(transport.dataChannelUuid)
                socket.connect()
                module.log(Log.INFO, TAG, "RfcommDeviceSession: RFCOMM connected")
                dataSocket = socket
                startReader()
                flushPendingCommands()
                queryAllStatus()
                broadcastDeviceConnected()
            } catch (e: IOException) {
                module.log(Log.ERROR, TAG, "RfcommDeviceSession: connect failed", e)
                pendingCommands.clear()
                disconnect()
            }
        }.apply {
            name = "RfcommConnect"
            isDaemon = true
            start()
        }
    }

    override fun disconnect() {
        running = false
        readerThread?.interrupt()
        readerThread = null
        connectThread?.interrupt()
        connectThread = null
        pendingCommands.clear()
        try {
            dataSocket?.close()
        } catch (_: IOException) {
        }
        dataSocket = null
        connectedDevice = null
        handler.removeCallbacksAndMessages(null)
        currentBattery = null
        currentAnc = null
        currentAncDepth = null
        currentTransLevel = null
        currentEq = null
        currentGameMode = null
        currentLowLatency = null
        module.log(Log.INFO, TAG, "RfcommDeviceSession: disconnected")
    }

    override fun sendCommand(packet: ByteArray, description: String) {
        val socket = dataSocket
        if (socket != null && socket.isConnected) {
            writeToSocket(socket, packet, description)
        } else {
            pendingCommands.add(packet to description)
            module.log(Log.DEBUG, TAG, "Queued command (connecting): $description")
        }
    }

    private fun writeToSocket(socket: BluetoothSocket, packet: ByteArray, description: String) {
        try {
            socket.outputStream.write(packet)
            val hex = packet.toHexString()
            module.log(Log.DEBUG, TAG, "→ $hex")
            logTx(hex, description)
        } catch (e: IOException) {
            module.log(Log.ERROR, TAG, "RfcommDeviceSession: send failed", e)
        }
    }

    private fun flushPendingCommands() {
        val socket = dataSocket ?: return
        var count = 0
        while (true) {
            val cmd = pendingCommands.poll() ?: break
            writeToSocket(socket, cmd.first, cmd.second)
            count++
        }
        if (count > 0) module.log(Log.DEBUG, TAG, "Flushed $count pending commands")
    }

    private fun startReader() {
        running = true
        readerThread = Thread {
            val buf = ByteArray(512)
            val input = dataSocket!!.inputStream
            val frameBuf = ByteArray(2048)
            var frameLen = 0

            while (running) {
                try {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (frameLen + n > frameBuf.size) frameLen = 0
                    System.arraycopy(buf, 0, frameBuf, frameLen, n)
                    frameLen += n

                    var processed = 0
                    while (frameLen - processed >= 5) {
                        val aaIdx = frameBuf.indexOf(0xAA.toByte(), processed)
                        if (aaIdx < processed + 4) {
                            processed = if (aaIdx == -1) frameLen else aaIdx + 1
                            continue
                        }
                        val frameEnd = aaIdx + 1
                        val frame = frameBuf.copyOfRange(processed, frameEnd)
                        if (verifyChecksum(frame)) {
                            handler.post { handleResponse(frame) }
                            processed = frameEnd
                        } else {
                            processed++
                        }
                    }
                    if (processed > 0) {
                        frameLen -= processed
                        System.arraycopy(frameBuf, processed, frameBuf, 0, frameLen)
                    }
                } catch (e: IOException) {
                    if (running) module.log(Log.ERROR, TAG, "RfcommDeviceSession: read error", e)
                    break
                }
            }
        }.apply {
            name = "RfcommReader"
            isDaemon = true
            start()
        }
    }

    private fun verifyChecksum(frame: ByteArray): Boolean {
        if (frame.size < 4) return false
        if (!profile.hasFrameChecksum) return frame[frame.size - 1] == 0xAA.toByte()
        val ckPos = frame.size - 2
        var sum = 0
        for (i in 0 until ckPos) {
            sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        return frame[ckPos] == sum.toByte()
    }

    private fun ByteArray.indexOf(element: Byte, start: Int): Int {
        for (i in start until this.size) {
            if (this[i] == element) return i
        }
        return -1
    }
}

@file:SuppressLint("MissingPermission")

package com.dohex.hyperrose.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dohex.hyperrose.debug.BleLog
import com.dohex.hyperrose.model.AncDepth
import com.dohex.hyperrose.model.AncMode
import com.dohex.hyperrose.model.EqPreset
import com.dohex.hyperrose.model.TransparencyLevel
import com.dohex.hyperrose.model.TwsBatteryState
import com.dohex.hyperrose.model.withLastKnownCaseBattery
import com.dohex.hyperrose.profile.DeviceProfile
import com.dohex.hyperrose.profile.DeviceResponse
import com.dohex.hyperrose.profile.TransportSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-process RFCOMM client for devices using Bluetooth Classic (e.g. BudsFeel MK2).
 *  All state exposed via StateFlow for Compose UI consumption. */
class StandaloneRfcommClient(
    private val context: Context,
    val profile: DeviceProfile,
) : StandaloneClient {
    companion object {
        private const val TAG = "HyperRose.StandaloneRfcommClient"
        private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    // Transport spec (non-null after init; profile must have TransportSpec.Rfcomm)
    private val rfcommSpec: TransportSpec.Rfcomm
        get() = profile.transport as TransportSpec.Rfcomm

    // StateFlows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _battery = MutableStateFlow<TwsBatteryState?>(null)
    val battery: StateFlow<TwsBatteryState?> = _battery.asStateFlow()

    private val _ancMode = MutableStateFlow<AncMode?>(null)
    val ancMode: StateFlow<AncMode?> = _ancMode.asStateFlow()

    private val _ancDepth = MutableStateFlow<AncDepth?>(null)
    val ancDepth: StateFlow<AncDepth?> = _ancDepth.asStateFlow()

    private val _transLevel = MutableStateFlow<TransparencyLevel?>(null)
    val transLevel: StateFlow<TransparencyLevel?> = _transLevel.asStateFlow()

    private val _eqMode = MutableStateFlow<EqPreset?>(null)
    val eqMode: StateFlow<EqPreset?> = _eqMode.asStateFlow()

    private val _gameMode = MutableStateFlow<Boolean?>(null)
    val gameMode: StateFlow<Boolean?> = _gameMode.asStateFlow()

    private val _lowLatency = MutableStateFlow<Boolean?>(null)
    val lowLatency: StateFlow<Boolean?> = _lowLatency.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    // Internal state
    private var dataSocket: BluetoothSocket? = null
    private var readerThread: Thread? = null
    private val pendingCommands = java.util.concurrent.ConcurrentLinkedQueue<Pair<ByteArray, String>>()

    @Volatile
    private var running = false

    @Volatile
    private var connectCancelled = false
    private val handler = Handler(Looper.getMainLooper())

    override fun connect(device: BluetoothDevice) {
        _deviceName.value = device.name
        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting to ${device.address} via RFCOMM")
        connectCancelled = false

        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(rfcommSpec.dataChannelUuid)
                socket.connect()
                if (connectCancelled) {
                    Log.i(TAG, "RFCOMM connect cancelled after socket opened")
                    runCatching { socket.close() }
                    return@Thread
                }
                dataSocket = socket
                handler.post {
                    _connectionState.value = ConnectionState.CONNECTED
                    Log.i(TAG, "RFCOMM connected")
                    startReader()
                    flushPendingCommands()
                    queryAllStatus()
                }
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed", e)
                pendingCommands.clear()
                handler.post {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }.apply {
            name = "RfcommConnect"
            isDaemon = true
            start()
        }
    }

    override fun disconnect() {
        connectCancelled = true
        running = false
        readerThread?.interrupt()
        readerThread = null
        runCatching { dataSocket?.close() }
        dataSocket = null
        handler.removeCallbacksAndMessages(null)
        _connectionState.value = ConnectionState.DISCONNECTED
        _battery.value = null
        _ancMode.value = null
        _ancDepth.value = null
        _transLevel.value = null
        _eqMode.value = null
        _gameMode.value = null
        _lowLatency.value = null
        _deviceName.value = null
    }

    fun sendCommand(packet: ByteArray, description: String = "") {
        val socket = dataSocket
        if (socket != null) {
            writeToSocket(socket, packet, description)
        } else {
            pendingCommands.add(packet to description)
            Log.d(TAG, "Queued command (dataSocket null): $description")
        }
    }

    private fun writeToSocket(socket: BluetoothSocket, packet: ByteArray, description: String) {
        try {
            socket.outputStream.write(packet)
            if (isBleLogEnabled()) {
                val hex = packet.toHexString()
                BleLog.log("App", "TX", hex, description, logTimeFormat.format(Date()))
            }
        } catch (e: IOException) {
            Log.e(TAG, "RFCOMM send failed", e)
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
        if (count > 0) Log.d(TAG, "Flushed $count pending commands")
    }

    override fun refreshStatus() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        queryAllStatus()
    }

    // Convenience methods
    override fun setAnc(mode: AncMode) =
        sendCommand(profile.protocol.ancCommand(mode), "Set ANC: $mode")

    override fun setAncDepth(depth: AncDepth) =
        sendCommand(profile.protocol.ancDepthCommand(depth), "Set ANC depth: $depth")

    override fun setTransLevel(level: TransparencyLevel) =
        sendCommand(profile.protocol.transLevelCommand(level), "Set transparency: $level")

    override fun setEq(mode: EqPreset) =
        sendCommand(profile.protocol.eqCommand(mode), "Set EQ: $mode")

    override fun setGameMode(enabled: Boolean) =
        sendCommand(profile.protocol.gameModeCommand(enabled), "Set game mode: $enabled")

    override fun setLowLatency(enabled: Boolean) =
        sendCommand(profile.protocol.lowLatencyCommand(enabled), "Set low latency: $enabled")

    override fun findLeft() = sendCommand(profile.protocol.findLeftOn, "Find left")

    override fun findRight() = sendCommand(profile.protocol.findRightOn, "Find right")

    override fun stopFind() = sendCommand(profile.protocol.findAllOff, "Stop find")

    /** Send raw hex command (for debug page). */
    override fun sendRawCommand(hex: String) {
        val normalized = hex.replace(" ", "").replace("\n", "").replace("\r", "")
        if (normalized.isEmpty() || normalized.length % 2 != 0 ||
            !normalized.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        ) {
            Log.w(TAG, "sendRawCommand: invalid hex: $hex")
            return
        }
        val bytes = ByteArray(normalized.length / 2) {
            normalized.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        sendCommand(bytes, "Raw: $normalized")
    }

    // ==================== Reader thread ====================

    private fun startReader() {
        running = true
        readerThread = Thread {
            val buf = ByteArray(512)
            val frameBuf = ByteArray(2048)
            var frameLen = 0
            val input = dataSocket!!.inputStream

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
                    if (running) Log.e(TAG, "RFCOMM read error", e)
                    break
                }
            }

            // Unexpected disconnect
            if (running) {
                handler.post { disconnect() }
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

    // ==================== Response handling ====================

    private fun handleResponse(data: ByteArray) {
        val results = profile.protocol.parseResponse(data)
        if (isBleLogEnabled()) {
            val hex = data.toHexString()
            BleLog.log("App", "RX", hex, results.toString(), logTimeFormat.format(Date()))
        }
        for (result in results) {
            when (result) {
                is DeviceResponse.Battery -> {
                    _battery.value = result.info.withLastKnownCaseBattery(_battery.value)
                }

                is DeviceResponse.Anc -> {
                    _ancMode.value = result.mode
                }

                is DeviceResponse.AncDepthChanged -> {
                    _ancDepth.value = result.depth
                }

                is DeviceResponse.TransparencyChanged -> {
                    _transLevel.value = result.level
                }

                is DeviceResponse.Eq -> {
                    _eqMode.value = result.mode
                }

                is DeviceResponse.GameMode -> {
                    _gameMode.value = result.enabled
                }

                is DeviceResponse.LowLatencyChanged -> {
                    _lowLatency.value = result.enabled
                }

                is DeviceResponse.Unknown -> {}
            }
        }
    }

    private fun isBleLogEnabled(): Boolean = com.dohex.hyperrose.hook.BluetoothProcessHook.isBleLogEnabled()

    // ==================== Status polling ====================

    private var pollScheduled = false

    private fun queryAllStatus() {
        profile.protocol.statusQuerySequence.forEachIndexed { index, query ->
            handler.postDelayed({ sendCommand(query, "Query status") }, 120L * index)
        }
        if (!pollScheduled) {
            pollScheduled = true
            handler.postDelayed(object : Runnable {
                override fun run() {
                    sendCommand(profile.protocol.queryBattery, "Query battery")
                    handler.postDelayed(this, 30_000L)
                }
            }, 30_000L)
        }
    }
}

private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

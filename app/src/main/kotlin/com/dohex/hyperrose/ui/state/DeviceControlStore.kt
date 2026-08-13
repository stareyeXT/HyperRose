package com.dohex.hyperrose.ui.state

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.dohex.hyperrose.ipc.BluetoothCommandDispatcher
import com.dohex.hyperrose.ipc.sendHyperRoseBroadcast
import com.dohex.hyperrose.model.AncDepth
import com.dohex.hyperrose.model.AncMode
import com.dohex.hyperrose.model.EarBatteryState
import com.dohex.hyperrose.model.EqPreset
import com.dohex.hyperrose.model.TransparencyLevel
import com.dohex.hyperrose.model.TwsBatteryState
import com.dohex.hyperrose.model.asBatteryLevelOrNull
import com.dohex.hyperrose.model.isSingleValue
import com.dohex.hyperrose.model.singleDisplayValue
import com.dohex.hyperrose.model.withLastKnownCaseBattery
import com.dohex.hyperrose.profile.TransportSpec
import com.dohex.hyperrose.service.StandaloneClient
import com.dohex.hyperrose.service.StandaloneGattClient
import com.dohex.hyperrose.service.StandaloneRfcommClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dohex.hyperrose.ipc.HyperRoseIpc as HyperRoseAction

enum class DeviceConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED,
}

enum class ConnectionTransport {
    NONE, DIRECT_BLE, DIRECT_RFCOMM, HOOK_BRIDGE,
}

data class RoseDeviceItem(
    val name: String,
    val address: String,
)

/**
 * App 侧统一状态与控制入口。
 * - 直接模式：StandaloneGattClient
 * - 桥接模式：接收 Hook 广播 + BluetoothCommandDispatcher 下发控制命令
 */
class DeviceControlStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val directGattClient = StandaloneGattClient(
        appContext, com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile
    )
    private var directRfcommClient: StandaloneRfcommClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var bridgeFallbackJob: Job? = null

    // Direct-connect retry state
    private var directRetryDevice: android.bluetooth.BluetoothDevice? = null
    private var directRetryProfile: com.dohex.hyperrose.profile.DeviceProfile? = null
    private var directRetryCount = 0
    private var directRetryJob: Job? = null
    private var rfcommObserverJobs: List<Job> = emptyList()

    companion object {
        private const val BRIDGE_TIMEOUT_MS = 5_000L
        private const val DIRECT_MAX_RETRIES = 3
        private const val DIRECT_RETRY_DELAY_MS = 1_500L
    }

    private val _hasBluetoothPermission = MutableStateFlow(false)
    val hasBluetoothPermission: StateFlow<Boolean> = _hasBluetoothPermission.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<RoseDeviceItem>>(emptyList())
    val pairedDevices: StateFlow<List<RoseDeviceItem>> = _pairedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    private val _transport = MutableStateFlow(ConnectionTransport.NONE)
    val transport: StateFlow<ConnectionTransport> = _transport.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    private val _connectedDevice = MutableStateFlow<android.bluetooth.BluetoothDevice?>(null)
    private var connectedProfileId: String? = null

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

    private val _gameMode = MutableStateFlow(false)
    val gameMode: StateFlow<Boolean> = _gameMode.asStateFlow()

    private val _lowLatency = MutableStateFlow(false)
    val lowLatency: StateFlow<Boolean> = _lowLatency.asStateFlow()

    private val _capabilities = MutableStateFlow(
        com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.capabilities
    )
    val capabilities: StateFlow<com.dohex.hyperrose.profile.DeviceCapabilities> =
        _capabilities.asStateFlow()

    private var receiverRegistered = false

    private val bridgeReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            if (!com.dohex.hyperrose.ipc.BroadcastSenderValidator.isAllowed(
                    context.packageManager,
                    sentFromUid,
                    setOf(
                        HyperRoseAction.PACKAGE_APP,
                        HyperRoseAction.PACKAGE_BLUETOOTH,
                        HyperRoseAction.PACKAGE_MI_BLUETOOTH,
                        HyperRoseAction.PACKAGE_MILINK,
                    ),
                )
            ) return
            when (intent.action) {
                HyperRoseAction.ANC_SELECT -> {
                    val modeName = intent.getStringExtra(HyperRoseAction.EXTRA_MODE) ?: return@onReceive
                    val mode = runCatching { AncMode.valueOf(modeName) }.getOrNull() ?: return@onReceive
                    val client = activeDirectClient()
                    if (client != null) {
                        client.setAnc(mode)
                    }
                }

                HyperRoseAction.DEVICE_CONNECTED -> {
                    bridgeFallbackJob?.cancel()

                    val device = intent.getParcelableExtra(
                        HyperRoseAction.EXTRA_DEVICE,
                        android.bluetooth.BluetoothDevice::class.java,
                    )
                    val profileId = intent.getStringExtra(HyperRoseAction.EXTRA_PROFILE_ID)
                    if (profileId != null) {
                        _capabilities.value =
                            com.dohex.hyperrose.profile.DeviceProfileRegistry.findById(profileId)?.capabilities
                                ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.capabilities
                    }

                    val directActive = _transport.value == ConnectionTransport.DIRECT_RFCOMM ||
                        _transport.value == ConnectionTransport.DIRECT_BLE
                    val directPending = directRetryDevice != null
                    // hook 已在蓝牙进程建立会话：为避免双连接占用耳机射频，不再额外发起 App 直连，
                    // 直接走桥接模式。用户显式触发直连（设备选择页）仍走 attemptDirectConnect。
                    if (!directActive && !directPending && device != null) {
                        val profile = profileId?.let {
                            com.dohex.hyperrose.profile.DeviceProfileRegistry.findById(it)
                        } ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.findByDevice(device)
                        _connectedDevice.value = device
                        connectedProfileId = profile?.id
                        _transport.value = ConnectionTransport.HOOK_BRIDGE
                        _connectionState.value = DeviceConnectionState.CONNECTED
                        clearDirectRetry()
                    } else if (!directActive && !directPending) {
                        // 无法直连（缺少设备信息），回退桥接
                        _transport.value = ConnectionTransport.HOOK_BRIDGE
                        _connectionState.value = DeviceConnectionState.CONNECTED
                        _connectedDevice.value = null
                        connectedProfileId = null
                    }

                    _deviceName.value = device?.name ?: _deviceName.value
                            ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.displayName

                    intent.enumExtra<AncMode>(HyperRoseAction.EXTRA_MODE)
                        ?.let { _ancMode.value = it }
                    intent.enumExtra<EqPreset>(HyperRoseAction.EXTRA_EQ_MODE)
                        ?.let { _eqMode.value = it }
                    if (intent.hasExtra(HyperRoseAction.EXTRA_ENABLED)) {
                        _gameMode.value =
                            intent.getBooleanExtra(HyperRoseAction.EXTRA_ENABLED, false)
                    }
                    val presetLeft = intent.getIntExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, -1)
                    val presetRight = intent.getIntExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, -1)
                    val presetCase = intent.getIntExtra(HyperRoseAction.EXTRA_CASE_LEVEL, -1)
                    if (presetLeft >= 0 || presetRight >= 0 || presetCase >= 0) {
                        parseBattery(intent)?.let {
                            _battery.value = if (it.right == null) it else it.withLastKnownCaseBattery(_battery.value)
                        }
                    }
                }

                HyperRoseAction.DEVICE_DISCONNECTED -> {
                    if (_transport.value == ConnectionTransport.HOOK_BRIDGE) {
                        _connectionState.value = DeviceConnectionState.DISCONNECTED
                        _transport.value = ConnectionTransport.NONE
                        clearState()
                    }
                }

                HyperRoseAction.BATTERY_CHANGED -> {
                    parseBattery(intent)?.let {
                        _battery.value = if (it.right == null) it else it.withLastKnownCaseBattery(_battery.value)
                    }
                }

                HyperRoseAction.ANC_CHANGED -> {
                    intent.enumExtra<AncMode>(HyperRoseAction.EXTRA_MODE)
                        ?.let { _ancMode.value = it }
                }

                HyperRoseAction.ANC_DEPTH_CHANGED -> {
                    intent.enumExtra<AncDepth>(HyperRoseAction.EXTRA_DEPTH)
                        ?.let { _ancDepth.value = it }
                }

                HyperRoseAction.TRANS_LEVEL_CHANGED -> {
                    intent.enumExtra<TransparencyLevel>(HyperRoseAction.EXTRA_LEVEL)?.let {
                        _transLevel.value = it
                    }
                }

                HyperRoseAction.EQ_CHANGED -> {
                    intent.enumExtra<EqPreset>(HyperRoseAction.EXTRA_EQ_MODE)
                        ?.let { _eqMode.value = it }
                }

                HyperRoseAction.GAME_MODE_CHANGED -> {
                    if (intent.hasExtra(HyperRoseAction.EXTRA_ENABLED)) {
                        _gameMode.value =
                            intent.getBooleanExtra(HyperRoseAction.EXTRA_ENABLED, false)
                    }
                }

                HyperRoseAction.DEVICE_COLOR_CHANGED -> {
                    val colorName = intent.getStringExtra(HyperRoseAction.EXTRA_COLOR)
                    if (_transport.value == ConnectionTransport.DIRECT_RFCOMM ||
                        _transport.value == ConnectionTransport.DIRECT_BLE
                    ) {
                        val battery = _battery.value
                        if (battery != null && colorName != null) {
                            broadcastFocusIslandWithColor(battery, colorName)
                        }
                    }
                }

            }
        }
    }

    init {
        observeDirectGatt()
        registerBridgeReceiver()
        refreshPermissionState()
        scope.launch { autoConnectPreferredDevice() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun autoConnectPreferredDevice() {
        delay(500)
        if (!_hasBluetoothPermission.value) {
            refreshPermissionState()
            if (!_hasBluetoothPermission.value) return
        }
        val preferred = withContext(Dispatchers.IO) {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext null
            adapter.bondedDevices.firstOrNull { device ->
                com.dohex.hyperrose.profile.DeviceProfileRegistry.findByDevice(device) != null
            }
        } ?: return
        val profile =
            com.dohex.hyperrose.profile.DeviceProfileRegistry.findByDevice(preferred)
        _deviceName.value = preferred.name ?: preferred.address
        _connectedDevice.value = preferred
        connectedProfileId = profile?.id
        _capabilities.value = profile?.capabilities
            ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.capabilities
        com.dohex.hyperrose.data.AuthorizedDeviceStore.add(appContext, preferred.address)
        attemptDirectConnect(preferred, profile)
    }

    fun refreshPermissionState() {
        val hasConnect = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        val hasScan = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
        _hasBluetoothPermission.value = hasConnect && hasScan
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedDevices() {
        if (!_hasBluetoothPermission.value) {
            _pairedDevices.value = emptyList()
            return
        }
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext emptyList()
                adapter.bondedDevices.mapNotNull { device ->
                    if (com.dohex.hyperrose.profile.DeviceProfileRegistry.findByDevice(device) == null) return@mapNotNull null
                    RoseDeviceItem(name = device.name ?: device.address ?: "", address = device.address)
                }.sortedWith(
                    compareBy<RoseDeviceItem> {
                        com.dohex.hyperrose.profile.DeviceProfileRegistry.findByName(it.name)?.let { profile ->
                            com.dohex.hyperrose.profile.DeviceProfileRegistry.profiles.indexOf(profile)
                        } ?: Int.MAX_VALUE
                    }.thenBy { it.name.lowercase() }.thenBy { it.address },
                )
            }
            _pairedDevices.value = items
        }
    }

    @SuppressLint("MissingPermission")
    fun connectDirect(address: String) {
        if (!_hasBluetoothPermission.value) return
        scope.launch {
            val bonded = withContext(Dispatchers.IO) {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext null
                adapter.bondedDevices.firstOrNull { it.address == address }
            } ?: return@launch
            com.dohex.hyperrose.data.AuthorizedDeviceStore.add(appContext, address)
            val profile = com.dohex.hyperrose.profile.DeviceProfileRegistry.findByDevice(bonded)
            _deviceName.value = bonded.name ?: address
            _connectedDevice.value = bonded
            connectedProfileId = profile?.id
            _capabilities.value = profile?.capabilities
                ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.capabilities
            attemptDirectConnect(bonded, profile)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectDirectRfcomm(address: String) {
        if (!_hasBluetoothPermission.value) return
        if (_connectionState.value == DeviceConnectionState.CONNECTED &&
            _transport.value == ConnectionTransport.DIRECT_RFCOMM
        ) return
        scope.launch {
            val bonded = withContext(Dispatchers.IO) {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext null
                adapter.bondedDevices.firstOrNull { it.address == address }
            } ?: return@launch
            val profile = com.dohex.hyperrose.profile.DeviceProfileRegistry.findByDevice(bonded)
            _deviceName.value = bonded.name ?: address
            _connectedDevice.value = bonded
            connectedProfileId = profile?.id
            _capabilities.value = profile?.capabilities
                ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.capabilities
            com.dohex.hyperrose.data.AuthorizedDeviceStore.add(appContext, address)
            attemptDirectConnect(bonded, profile)
        }
    }

    /**
     * 优先尝试 App 直连（RFCOMM/BLE）。失败会自动重试若干次，
     * 全部失败后回退到 LSPosed 桥接模式。
     */
    @SuppressLint("MissingPermission")
    private fun attemptDirectConnect(
        bonded: android.bluetooth.BluetoothDevice,
        profile: com.dohex.hyperrose.profile.DeviceProfile?,
    ) {
        bridgeFallbackJob?.cancel()
        directRetryJob?.cancel()
        directRetryDevice = bonded
        directRetryProfile = profile
        directRetryCount = 0
        connectStandalone(bonded, profile)
    }

    /** 直连失败时调用：未超上限则重试，超限则回退桥接。返回 true 表示已处理（重试或回退）。 */
    @SuppressLint("MissingPermission")
    private fun onDirectConnectFailed(): Boolean {
        val device = directRetryDevice ?: return false
        val profile = directRetryProfile
        if (directRetryCount < DIRECT_MAX_RETRIES) {
            directRetryCount++
            directRetryJob?.cancel()
            directRetryJob = scope.launch {
                delay(DIRECT_RETRY_DELAY_MS)
                connectStandalone(device, profile)
            }
        } else {
            fallbackToBridge()
        }
        return true
    }

    private fun clearDirectRetry() {
        directRetryJob?.cancel()
        directRetryJob = null
        directRetryDevice = null
        directRetryProfile = null
        directRetryCount = 0
    }

    /** 直连彻底失败后放弃 App 直连。真实桥接连接只由 hook 的 DEVICE_CONNECTED 广播补齐，
     *  不在此伪造已连接态——否则耳机未连接时 App 会显示虚假的"已连接/LSPosed 桥接模式"。 */
    private fun fallbackToBridge() {
        clearDirectRetry()
        if (_transport.value == ConnectionTransport.HOOK_BRIDGE &&
            _connectionState.value == DeviceConnectionState.CONNECTED
        ) {
            // 桥接会话已由 hook 建立，保持现状。
            return
        }
        _transport.value = ConnectionTransport.NONE
        _connectionState.value = DeviceConnectionState.DISCONNECTED
        _connectedDevice.value = null
        connectedProfileId = null
    }

    @SuppressLint("MissingPermission")
    private fun connectStandalone(
        bonded: android.bluetooth.BluetoothDevice,
        profile: com.dohex.hyperrose.profile.DeviceProfile?,
    ) {
        when (profile?.transport) {
            is TransportSpec.Rfcomm -> {
                directRfcommClient?.disconnect()
                rfcommObserverJobs.forEach { it.cancel() }
                rfcommObserverJobs = emptyList()
                val client = StandaloneRfcommClient(appContext, profile)
                directRfcommClient = client
                rfcommObserverJobs = observeDirectRfcomm(client)
                _capabilities.value = profile.capabilities
                _transport.value = ConnectionTransport.DIRECT_RFCOMM
                _connectionState.value = DeviceConnectionState.CONNECTING
                client.connect(bonded)
            }

            else -> {
                _transport.value = ConnectionTransport.DIRECT_BLE
                _connectionState.value = DeviceConnectionState.CONNECTING
                directGattClient.connect(bonded)
            }
        }
    }

    fun setAnc(mode: AncMode) {
        _ancMode.value = mode
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.setAnc(appContext, mode)
            else -> client.setAnc(mode)
        }
    }

    fun setAncDepth(depth: AncDepth) {
        _ancDepth.value = depth
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.setAncDepth(appContext, depth)
            else -> client.setAncDepth(depth)
        }
    }

    fun setTransLevel(level: TransparencyLevel) {
        _transLevel.value = level
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.setTransLevel(appContext, level)
            else -> client.setTransLevel(level)
        }
    }

    fun setEq(mode: EqPreset) {
        _eqMode.value = mode
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.setEq(appContext, mode)
            else -> client.setEq(mode)
        }
    }

    fun setGameMode(enabled: Boolean) {
        _gameMode.value = enabled
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.setGameMode(appContext, enabled)
            else -> client.setGameMode(enabled)
        }
    }

    fun setLowLatency(enabled: Boolean) {
        _lowLatency.value = enabled
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.setLowLatency(appContext, enabled)
            else -> client.setLowLatency(enabled)
        }
    }

    fun findLeft() {
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.findLeft(appContext)
            else -> client.findLeft()
        }
    }

    fun findRight() {
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.findRight(appContext)
            else -> client.findRight()
        }
    }

    fun stopFind() {
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.stopFind(appContext)
            else -> client.stopFind()
        }
    }

    /** 发送原始 hex 指令（供调试页使用），根据当前传输模式路由 */
    fun sendRawCommand(hex: String) {
        when (val client = activeDirectClient()) {
            null -> {
                Intent(HyperRoseAction.RAW_SEND).apply {
                    setPackage(HyperRoseAction.PACKAGE_BLUETOOTH)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(HyperRoseAction.EXTRA_HEX, hex)
                    appContext.sendHyperRoseBroadcast(this)
                }
            }

            else -> client.sendRawCommand(hex)
        }
    }

    fun refreshStatus() {
        when (val client = activeDirectClient()) {
            null -> BluetoothCommandDispatcher.refreshStatus(appContext)
            else -> client.refreshStatus()
        }
    }

    fun disconnect() {
        bridgeFallbackJob?.cancel()
        clearDirectRetry()
        directRfcommClient?.disconnect()
        directGattClient.disconnect()
        BluetoothCommandDispatcher.disconnectGatt(appContext)
        _connectionState.value = DeviceConnectionState.DISCONNECTED
        _transport.value = ConnectionTransport.NONE
        clearState()
    }

    fun setTemporaryConnectionState(
        name: String,
        battery: TwsBatteryState?,
        profileId: String? = null,
    ) {
        if (_connectionState.value == DeviceConnectionState.CONNECTED) return
        _transport.value = ConnectionTransport.HOOK_BRIDGE
        _connectionState.value = DeviceConnectionState.CONNECTED
        _deviceName.value = name
        if (battery != null) {
            _battery.value = battery
        }
        val id = profileId ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.findByName(name)?.id
        if (id != null) {
            _capabilities.value =
                com.dohex.hyperrose.profile.DeviceProfileRegistry.findById(id)?.capabilities
                    ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.capabilities
        }
    }

    fun release() {
        bridgeFallbackJob?.cancel()
        directRetryJob?.cancel()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(bridgeReceiver) }
            receiverRegistered = false
        }
        rfcommObserverJobs.forEach { it.cancel() }
        rfcommObserverJobs = emptyList()
        directRfcommClient?.disconnect()
        directRfcommClient = null
        directGattClient.disconnect()
        scope.coroutineContext.cancel()
    }

    private fun observeDirectGatt() {
        directGattClient.connectionState.onEach { state ->
            if (_transport.value != ConnectionTransport.DIRECT_BLE && state == StandaloneGattClient.ConnectionState.CONNECTED) {
                _transport.value = ConnectionTransport.DIRECT_BLE
            }
            when (state) {
                StandaloneGattClient.ConnectionState.DISCONNECTED -> {
                    // 直连尝试期间断开 = 本次直连失败，交给重试/回退逻辑
                    if (directRetryDevice != null &&
                        _connectionState.value != DeviceConnectionState.CONNECTED
                    ) {
                        onDirectConnectFailed()
                    } else if (_transport.value == ConnectionTransport.DIRECT_BLE) {
                        _connectionState.value = DeviceConnectionState.DISCONNECTED
                        _transport.value = ConnectionTransport.NONE
                        clearState()
                        broadcastDeviceDisconnected()
                    }
                }

                StandaloneGattClient.ConnectionState.CONNECTING -> {
                    _connectionState.value = DeviceConnectionState.CONNECTING
                    _transport.value = ConnectionTransport.DIRECT_BLE
                }

                StandaloneGattClient.ConnectionState.CONNECTED -> {
                    clearDirectRetry()
                    _connectionState.value = DeviceConnectionState.CONNECTED
                    _transport.value = ConnectionTransport.DIRECT_BLE
                }
            }
        }.launchIn(scope)

        directGattClient.deviceName.onEach { name ->
            if (!name.isNullOrBlank()) {
                _deviceName.value = name
            }
        }.launchIn(scope)

        directGattClient.battery.onEach {
            _battery.value = it?.withLastKnownCaseBattery(_battery.value)
            if (it != null) {
                broadcastToSystem(HyperRoseAction.BATTERY_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, it.left?.level ?: -1)
                    putExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, it.right?.level ?: -1)
                    putExtra(HyperRoseAction.EXTRA_LEFT_CHARGING, it.left?.isCharging ?: false)
                    putExtra(HyperRoseAction.EXTRA_RIGHT_CHARGING, it.right?.isCharging ?: false)
                    putExtra(HyperRoseAction.EXTRA_CASE_LEVEL, it.caseBattery ?: -1)
                    putExtra(HyperRoseAction.EXTRA_OVERALL_LEVEL, it.overall ?: -1)
                }
                broadcastFocusIsland(it)
            }
        }.launchIn(scope)

        directGattClient.profileMatchResult.onEach { result ->
            if (result == null || result.actualProfileId == connectedProfileId) return@onEach
            val device = _connectedDevice.value ?: return@onEach
            val newProfile = com.dohex.hyperrose.profile.DeviceProfileRegistry.findById(result.actualProfileId)
            connectedProfileId = result.actualProfileId
            _capabilities.value = newProfile?.capabilities
                ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.capabilities
            newProfile?.let { _deviceName.value = it.displayName }
            directGattClient.disconnect()
            attemptDirectConnect(device, newProfile)
        }.launchIn(scope)

        directGattClient.ancMode.onEach {
            if (it != null) {
                _ancMode.value = it
                broadcastToSystem(HyperRoseAction.ANC_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_MODE, it.name)
                }
            }
        }.launchIn(scope)

        directGattClient.ancDepth.onEach {
            if (it != null) {
                _ancDepth.value = it
                broadcastToSystem(HyperRoseAction.ANC_DEPTH_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_DEPTH, it.name)
                }
            }
        }.launchIn(scope)

        directGattClient.transLevel.onEach {
            if (it != null) {
                _transLevel.value = it
                broadcastToSystem(HyperRoseAction.TRANS_LEVEL_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_LEVEL, it.name)
                }
            }
        }.launchIn(scope)

        directGattClient.eqMode.onEach {
            if (it != null) {
                _eqMode.value = it
                broadcastToSystem(HyperRoseAction.EQ_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_EQ_MODE, it.name)
                }
            }
        }.launchIn(scope)

        directGattClient.gameMode.onEach {
            if (it != null) {
                _gameMode.value = it
                broadcastToSystem(HyperRoseAction.GAME_MODE_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_ENABLED, it)
                }
            }
        }.launchIn(scope)

        directGattClient.lowLatency.onEach {
            if (it != null) {
                _lowLatency.value = it
                broadcastToSystem(HyperRoseAction.LOW_LATENCY_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_ENABLED, it)
                }
            }
        }.launchIn(scope)
    }

    private fun observeDirectRfcomm(client: StandaloneRfcommClient): List<Job> {
        val jobs = mutableListOf<Job>()
        client.connectionState.onEach { state ->
            if (client !== directRfcommClient) return@onEach
            when (state) {
                StandaloneRfcommClient.ConnectionState.DISCONNECTED -> {
                    if (directRetryDevice != null &&
                        _connectionState.value != DeviceConnectionState.CONNECTED
                    ) {
                        onDirectConnectFailed()
                    } else if (_transport.value == ConnectionTransport.DIRECT_RFCOMM) {
                        _connectionState.value = DeviceConnectionState.DISCONNECTED
                        _transport.value = ConnectionTransport.NONE
                        clearState()
                        broadcastDeviceDisconnected()
                    }
                }

                StandaloneRfcommClient.ConnectionState.CONNECTING -> {
                    _transport.value = ConnectionTransport.DIRECT_RFCOMM
                    _connectionState.value = DeviceConnectionState.CONNECTING
                }

                StandaloneRfcommClient.ConnectionState.CONNECTED -> {
                    clearDirectRetry()
                    _transport.value = ConnectionTransport.DIRECT_RFCOMM
                    _connectionState.value = DeviceConnectionState.CONNECTED
                }
            }
        }.also { jobs.add(it.launchIn(scope)) }

        client.deviceName.onEach { name ->
            if (client !== directRfcommClient) return@onEach
            if (!name.isNullOrBlank()) _deviceName.value = name
        }.also { jobs.add(it.launchIn(scope)) }

        client.battery.onEach {
            if (client !== directRfcommClient) return@onEach
            _battery.value = it?.withLastKnownCaseBattery(_battery.value)
            if (it != null) {
                broadcastToSystem(HyperRoseAction.BATTERY_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, it.left?.level ?: -1)
                    putExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, it.right?.level ?: -1)
                    putExtra(HyperRoseAction.EXTRA_LEFT_CHARGING, it.left?.isCharging ?: false)
                    putExtra(HyperRoseAction.EXTRA_RIGHT_CHARGING, it.right?.isCharging ?: false)
                    putExtra(HyperRoseAction.EXTRA_CASE_LEVEL, it.caseBattery ?: -1)
                    putExtra(HyperRoseAction.EXTRA_OVERALL_LEVEL, it.overall ?: -1)
                }
                broadcastFocusIsland(it)
            }
        }.also { jobs.add(it.launchIn(scope)) }

        client.ancMode.onEach {
            if (client !== directRfcommClient) return@onEach
            if (it != null) {
                _ancMode.value = it
                broadcastToSystem(HyperRoseAction.ANC_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_MODE, it.name)
                }
            }
        }.also { jobs.add(it.launchIn(scope)) }
        client.ancDepth.onEach {
            if (client !== directRfcommClient) return@onEach
            if (it != null) {
                _ancDepth.value = it
                broadcastToSystem(HyperRoseAction.ANC_DEPTH_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_DEPTH, it.name)
                }
            }
        }.also { jobs.add(it.launchIn(scope)) }
        client.transLevel.onEach {
            if (client !== directRfcommClient) return@onEach
            if (it != null) {
                _transLevel.value = it
                broadcastToSystem(HyperRoseAction.TRANS_LEVEL_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_LEVEL, it.name)
                }
            }
        }.also { jobs.add(it.launchIn(scope)) }
        client.eqMode.onEach {
            if (client !== directRfcommClient) return@onEach
            if (it != null) {
                _eqMode.value = it
                broadcastToSystem(HyperRoseAction.EQ_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_EQ_MODE, it.name)
                }
            }
        }.also { jobs.add(it.launchIn(scope)) }
        client.gameMode.onEach {
            if (client !== directRfcommClient) return@onEach
            if (it != null) {
                _gameMode.value = it
                broadcastToSystem(HyperRoseAction.GAME_MODE_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_ENABLED, it)
                }
            }
        }.also { jobs.add(it.launchIn(scope)) }
        client.lowLatency.onEach {
            if (client !== directRfcommClient) return@onEach
            if (it != null) {
                _lowLatency.value = it
                broadcastToSystem(HyperRoseAction.LOW_LATENCY_CHANGED) {
                    putExtra(HyperRoseAction.EXTRA_ENABLED, it)
                }
            }
        }.also { jobs.add(it.launchIn(scope)) }
        return jobs
    }

    private fun broadcastToSystem(action: String, extras: Intent.() -> Unit) {
        listOf(
            HyperRoseAction.PACKAGE_MILINK,
            HyperRoseAction.PACKAGE_MI_BLUETOOTH,
        ).forEach { pkg ->
            appContext.sendHyperRoseBroadcast(
                Intent(action).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    extras()
                },
            )
        }
    }

    private fun broadcastFocusIsland(battery: TwsBatteryState) {
        val device = _connectedDevice.value ?: return
        val pid = connectedProfileId
            ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.findByDevice(device)?.id
            ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.id
        val isMono = battery.isSingleValue()
        val color = defaultProfileColorFor(pid).lowercase()
        val leftImage = resolveIslandImage(pid, color, leftSide = true)
        val rightImage = if (isMono) null else resolveIslandImage(pid, color, leftSide = false)
        appContext.sendHyperRoseBroadcast(
            Intent(HyperRoseAction.SHOW_ISLAND).apply {
                setPackage(HyperRoseAction.PACKAGE_MI_BLUETOOTH)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, if (isMono) -1 else (battery.left?.level ?: -1))
                putExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, if (isMono) -1 else (battery.right?.level ?: -1))
                putExtra(HyperRoseAction.EXTRA_LEFT_CHARGING, battery.left?.isCharging ?: false)
                putExtra(HyperRoseAction.EXTRA_RIGHT_CHARGING, battery.right?.isCharging ?: false)
                putExtra(HyperRoseAction.EXTRA_CASE_LEVEL, if (isMono) (battery.singleDisplayValue() ?: -1) else (battery.caseBattery ?: -1))
                putExtra(HyperRoseAction.EXTRA_DEVICE, device)
                putExtra(HyperRoseAction.EXTRA_PROFILE_ID, pid)
                putExtra(HyperRoseAction.EXTRA_COLOR, color)
                putExtra(HyperRoseAction.EXTRA_LEFT_IMAGE, leftImage)
                putExtra(HyperRoseAction.EXTRA_RIGHT_IMAGE, rightImage)
            },
        )
    }

    private fun defaultProfileColorFor(profileId: String): String =
        com.dohex.hyperrose.model.DeviceColorProfile.forDevice(profileId)
            ?.defaultColor()?.name ?: "GRAY"

    private fun resolveIslandImage(profileId: String, color: String, leftSide: Boolean): String? {
        val profile = com.dohex.hyperrose.model.DeviceColorProfile.forDevice(profileId) ?: return null
        val parsedColor = runCatching {
            com.dohex.hyperrose.model.EarphoneColor.valueOf(color.uppercase())
        }.getOrNull() ?: profile.defaultColor()
        return profile.islandImageNameFor(parsedColor, leftSide)
    }

    private fun broadcastFocusIslandWithColor(battery: TwsBatteryState, colorName: String) {
        val device = _connectedDevice.value ?: return
        val pid = connectedProfileId
            ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.findByDevice(device)?.id
            ?: com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.id
        val isMono = battery.isSingleValue()
        val color = colorName.lowercase()
        val leftImage = resolveIslandImage(pid, color, leftSide = true)
        val rightImage = if (isMono) null else resolveIslandImage(pid, color, leftSide = false)
        appContext.sendHyperRoseBroadcast(
            Intent(HyperRoseAction.SHOW_ISLAND).apply {
                setPackage(HyperRoseAction.PACKAGE_MI_BLUETOOTH)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, if (isMono) -1 else (battery.left?.level ?: -1))
                putExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, if (isMono) -1 else (battery.right?.level ?: -1))
                putExtra(HyperRoseAction.EXTRA_LEFT_CHARGING, battery.left?.isCharging ?: false)
                putExtra(HyperRoseAction.EXTRA_RIGHT_CHARGING, battery.right?.isCharging ?: false)
                putExtra(HyperRoseAction.EXTRA_CASE_LEVEL, if (isMono) (battery.singleDisplayValue() ?: -1) else (battery.caseBattery ?: -1))
                putExtra(HyperRoseAction.EXTRA_DEVICE, device)
                putExtra(HyperRoseAction.EXTRA_PROFILE_ID, pid)
                putExtra(HyperRoseAction.EXTRA_COLOR, color)
                putExtra(HyperRoseAction.EXTRA_LEFT_IMAGE, leftImage)
                putExtra(HyperRoseAction.EXTRA_RIGHT_IMAGE, rightImage)
            },
        )
    }

    private fun broadcastDeviceDisconnected() {
        val device = _connectedDevice.value ?: return
        appContext.sendHyperRoseBroadcast(
            Intent(HyperRoseAction.DEVICE_DISCONNECTED).apply {
                setPackage(HyperRoseAction.PACKAGE_MI_BLUETOOTH)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(HyperRoseAction.EXTRA_DEVICE, device)
            },
        )
    }

    private fun registerBridgeReceiver() {
        if (receiverRegistered) return
        val filter =
            IntentFilter().apply {
                HyperRoseAction.BRIDGE_STATE_ACTIONS.forEach(::addAction)
                addAction(HyperRoseAction.ANC_SELECT)
                addAction(HyperRoseAction.DEVICE_COLOR_CHANGED)
            }
        appContext.registerReceiver(bridgeReceiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun activeDirectClient(): StandaloneClient? = when (_transport.value) {
        ConnectionTransport.DIRECT_BLE -> directGattClient
        ConnectionTransport.DIRECT_RFCOMM -> directRfcommClient
        else -> null
    }


    private inline fun <reified T : Enum<T>> Intent.enumExtra(key: String): T? =
        getStringExtra(key)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private fun parseBattery(intent: Intent): TwsBatteryState? {
        val overallLevel =
            intent.getIntExtra(HyperRoseAction.EXTRA_OVERALL_LEVEL, -1).asBatteryLevelOrNull()
        if (overallLevel != null) return TwsBatteryState(overall = overallLevel)
        val leftLevel =
            intent.getIntExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, -1).asBatteryLevelOrNull()
        val rightLevel =
            intent.getIntExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, -1).asBatteryLevelOrNull()
        val caseLevel =
            intent.getIntExtra(HyperRoseAction.EXTRA_CASE_LEVEL, -1).asBatteryLevelOrNull()

        if (leftLevel == null && rightLevel == null && caseLevel == null) {
            return null
        }

        val left = leftLevel?.let {
            EarBatteryState(
                level = it,
                isCharging = intent.getBooleanExtra(HyperRoseAction.EXTRA_LEFT_CHARGING, false),
            )
        }

        val right = rightLevel?.let {
            EarBatteryState(
                level = it,
                isCharging = intent.getBooleanExtra(
                    HyperRoseAction.EXTRA_RIGHT_CHARGING, false
                ),
            )
        }

        val levels = listOfNotNull(left?.level, right?.level, caseLevel)
        val nonZeroCount = levels.count { it > 0 }
        if (nonZeroCount == 1) {
            val realLevel = levels.first { it > 0 }
            return TwsBatteryState(
                left = EarBatteryState(realLevel, false),
                right = null, caseBattery = null,
            )
        }
        return TwsBatteryState(
            left = left,
            right = right,
            caseBattery = caseLevel,
        )
    }

    private fun clearState() {
        _deviceName.value = null
        _connectedDevice.value = null
        connectedProfileId = null
        _battery.value = null
        _ancMode.value = null
        _ancDepth.value = null
        _transLevel.value = null
        _eqMode.value = null
        _gameMode.value = false
        _lowLatency.value = false
        _capabilities.value =
            com.dohex.hyperrose.profile.DeviceProfileRegistry.defaultProfile.capabilities
    }
}

package com.dohex.hyperrose.hook

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.Log
import com.dohex.hyperrose.hook.HyperRoseModuleEntry.Companion.TAG
import com.dohex.hyperrose.ipc.QuickControlIntentFactory
import com.dohex.hyperrose.ipc.BroadcastSenderValidator
import com.dohex.hyperrose.model.AncMode
import com.dohex.hyperrose.model.asBatteryLevelOrNull
import com.dohex.hyperrose.util.FocusIslandBridge
import com.dohex.hyperrose.util.ReflectionHelper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import com.dohex.hyperrose.ipc.HyperRoseIpc as HyperRoseAction

/** 在 com.xiaomi.bluetooth 进程接收 SHOW_ISLAND 广播并发送超级岛。 对齐 HyperOriG 的“宿主发岛”策略。 */
@SuppressLint("MissingPermission")
object MiBluetoothFocusIslandHook {
    // Bump the IDs because Android persists channel importance permanently;
    // the previous release created the focus channel as IMPORTANCE_LOW.
    private const val CHANNEL_ID = "hyperrose.focus.v2"
    private const val CONNECTION_CHANNEL_ID = "hyperrose.connection.v2"
    private const val ISLAND_NOTIFICATION_ID = 10086
    private const val ISLAND_TIMEOUT_SECONDS = 30
    private const val QUICK_CONTROL_REQUEST_CODE = 10086
    private var receiverRegistered = false
    private var moduleContext: Context? = null
    private var lastKnownCaseLevel: Int? = null
    private var lastKnownAncMode: AncMode? = null
    private var lastConnectedDevice: BluetoothDevice? = null
    private var connectionSessionActive = false
    private var lastIslandLeft = -1
    private var lastIslandRight = -1
    private var lastIslandCase = -1
    private var lastIslandLeftCharging = false
    private var lastIslandRightCharging = false
    private var lastLeftImageName: String? = null
    private var lastRightImageName: String? = null

    // 会话内首条 SHOW_ISLAND 才展示大岛；之后的更新只发轻量焦点通知，避免反复展开。
    private var firstIslandShown = false
    private val iconCache = mutableMapOf<String, Icon?>()
    private val trustedBroadcastSenders =
        setOf(HyperRoseAction.PACKAGE_APP, HyperRoseAction.PACKAGE_BLUETOOTH)

    @SuppressLint("PrivateApi")
    fun init(
        module: XposedModule,
        param: PackageLoadedParam,
    ) {
        val cl = param.defaultClassLoader
        try {
            val notifClass = cl.loadClass("com.android.bluetooth.ble.app.MiuiBluetoothNotification")
            val ctor = notifClass.declaredConstructors.firstOrNull { it.parameterCount >= 1 }
            if (ctor == null) {
                module.log(
                    Log.WARN,
                    TAG,
                    "MiBluetoothFocusIslandHook: MiuiBluetoothNotification constructor not found",
                )
                return
            }

            module.hook(ctor).intercept { chain ->
                val result = chain.proceed()
                try {
                    val context =
                        (chain.getArg(0) as? Context)
                            ?: (
                                    try {
                                        ReflectionHelper.getField(
                                            chain.thisObject,
                                            "mContext"
                                        ) as? Context
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    )
                    if (context != null) registerReceiver(module, context)
                } catch (t: Throwable) {
                    module.log(
                        Log.ERROR,
                        TAG,
                        "MiBluetoothFocusIslandHook: failed to register receiver",
                        t,
                    )
                }
                result
            }

            module.log(
                Log.INFO,
                TAG,
                "MiBluetoothFocusIslandHook: hooked MiuiBluetoothNotification constructor",
            )
        } catch (t: Throwable) {
            module.log(Log.ERROR, TAG, "MiBluetoothFocusIslandHook: failed to install hook", t)
        }
    }

    private fun registerReceiver(
        module: XposedModule,
        context: Context,
    ) {
        if (receiverRegistered) return

        if (moduleContext == null) {
            moduleContext = runCatching {
                context.createPackageContext("com.dohex.hyperrose", Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull()
        }

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    if (intent.action != Intent.ACTION_USER_PRESENT &&
                        !BroadcastSenderValidator.isAllowed(
                            ctx.packageManager,
                            sentFromUid,
                            trustedBroadcastSenders,
                        )
                    ) return
                    when (intent.action) {
                        HyperRoseAction.SHOW_ISLAND -> {
                            val device =
                                intent.getParcelableExtra(
                                    HyperRoseAction.EXTRA_DEVICE,
                                    BluetoothDevice::class.java,
                                )
                            beginConnectionSession(device)

                            val left =
                                intent.getIntExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, -1)
                                    .asBatteryLevelOrNull()
                                    ?: -1
                            val right =
                                intent.getIntExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, -1)
                                    .asBatteryLevelOrNull()
                                    ?: -1
                            val currentCaseLevel =
                                intent.getIntExtra(HyperRoseAction.EXTRA_CASE_LEVEL, -1)
                                    .asBatteryLevelOrNull()
                            val caseLevel = currentCaseLevel ?: lastKnownCaseLevel ?: -1
                            if (currentCaseLevel != null) {
                                lastKnownCaseLevel = currentCaseLevel
                            }
                            val leftCharging =
                                intent.getBooleanExtra(HyperRoseAction.EXTRA_LEFT_CHARGING, false)
                            val rightCharging =
                                intent.getBooleanExtra(HyperRoseAction.EXTRA_RIGHT_CHARGING, false)
                            if (left < 0 && right < 0 && caseLevel < 0) return

                            val leftImageName = intent.getStringExtra(HyperRoseAction.EXTRA_LEFT_IMAGE)
                            val rightImageName = intent.getStringExtra(HyperRoseAction.EXTRA_RIGHT_IMAGE)
                            val caseImageName = intent.getStringExtra(HyperRoseAction.EXTRA_CASE_IMAGE)

                            // 仅在上一条通知实际投递成功后去重；失败时允许相同状态重试。
                            if (firstIslandShown &&
                                left == lastIslandLeft && right == lastIslandRight &&
                                caseLevel == lastIslandCase &&
                                leftCharging == lastIslandLeftCharging &&
                                rightCharging == lastIslandRightCharging &&
                                leftImageName == lastLeftImageName &&
                                rightImageName == lastRightImageName
                            ) {
                                return
                            }

                            // 仅会话内首条 SHOW_ISLAND 展开大岛，之后只更新轻量焦点通知。
                            if (!firstIslandShown) {
                                val leftIcon = resolveIcon(leftImageName)
                                val rightIcon = resolveIcon(rightImageName)
                                val caseIcon = resolveIcon(caseImageName)
                                val shown = runCatching {
                                    showIsland(
                                        context = ctx,
                                        device = device,
                                        left = left,
                                        right = right,
                                        caseLevel = caseLevel,
                                        leftCharging = leftCharging,
                                        rightCharging = rightCharging,
                                        leftIcon = leftIcon,
                                        rightIcon = rightIcon,
                                        caseIcon = caseIcon,
                                    )
                                }.getOrElse {
                                    module.log(
                                        Log.WARN,
                                        TAG,
                                        "MiBluetoothFocusIslandHook: show island failed",
                                        it,
                                    )
                                    false
                                }
                                if (shown) {
                                    firstIslandShown = true
                                    rememberIslandState(
                                        left,
                                        right,
                                        caseLevel,
                                        leftCharging,
                                        rightCharging,
                                        leftImageName,
                                        rightImageName,
                                    )
                                }
                            } else {
                                val shown = runCatching {
                                    showFocusNotification(
                                        context = ctx,
                                        device = device,
                                        left = left,
                                        right = right,
                                        caseLevel = caseLevel,
                                        leftCharging = leftCharging,
                                        rightCharging = rightCharging,
                                        leftIcon = resolveIcon(leftImageName),
                                        rightIcon = resolveIcon(rightImageName),
                                        caseIcon = resolveIcon(caseImageName),
                                    )
                                }.getOrElse {
                                    module.log(
                                        Log.WARN,
                                        TAG,
                                        "MiBluetoothFocusIslandHook: update focus notification failed",
                                        it,
                                    )
                                    false
                                }
                                if (shown) {
                                    rememberIslandState(
                                        left,
                                        right,
                                        caseLevel,
                                        leftCharging,
                                        rightCharging,
                                        leftImageName,
                                        rightImageName,
                                    )
                                }
                            }
                        }

                        HyperRoseAction.DEVICE_CONNECTED -> {
                            val device = intent.getParcelableExtra(
                                HyperRoseAction.EXTRA_DEVICE,
                                BluetoothDevice::class.java,
                            )
                            val newSession = beginConnectionSession(device)
                            lastKnownAncMode = intent.getStringExtra(HyperRoseAction.EXTRA_MODE)
                                ?.let { runCatching { AncMode.valueOf(it) }.getOrNull() }
                            if (newSession) {
                                showConnectionNotification(
                                    context = ctx,
                                    device = device,
                                )
                            }
                        }

                        HyperRoseAction.DEVICE_DISCONNECTED -> {
                            lastKnownCaseLevel = null
                            resetIslandDeliveryState()
                            lastKnownAncMode = null
                            lastConnectedDevice = null
                            connectionSessionActive = false
                            cancelIsland(ctx)
                        }

                        HyperRoseAction.ANC_CHANGED -> {
                            lastKnownAncMode = intent.getStringExtra(HyperRoseAction.EXTRA_MODE)
                                ?.let { runCatching { AncMode.valueOf(it) }.getOrNull() }
                        }

                        Intent.ACTION_USER_PRESENT -> {
                            lastIslandLeft = -1
                            lastIslandRight = -1
                            lastIslandCase = -1
                            lastIslandLeftCharging = false
                            lastIslandRightCharging = false
                            lastLeftImageName = null
                            lastRightImageName = null
                        }
                    }
                }
            }

        val filter =
            IntentFilter(HyperRoseAction.SHOW_ISLAND).apply {
                addAction(Intent.ACTION_USER_PRESENT)
                HyperRoseAction.BRIDGE_STATE_ACTIONS
                    .asSequence()
                    .filter {
                        it == HyperRoseAction.DEVICE_CONNECTED || it == HyperRoseAction.DEVICE_DISCONNECTED
                    }.forEach(::addAction)
                addAction(HyperRoseAction.ANC_CHANGED)
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        module.log(Log.INFO, TAG, "MiBluetoothFocusIslandHook: receiver ready")
    }

    @SuppressLint("NotificationPermission")
    private fun showIsland(
        context: Context,
        device: BluetoothDevice?,
        left: Int,
        right: Int,
        caseLevel: Int,
        leftCharging: Boolean,
        rightCharging: Boolean,
        leftIcon: Icon?,
        rightIcon: Icon?,
        caseIcon: Icon?,
    ): Boolean =
        postStatusNotification(
            context = context,
            device = device,
            left = left,
            right = right,
            caseLevel = caseLevel,
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            leftIcon = leftIcon,
            rightIcon = rightIcon,
            caseIcon = caseIcon,
            firstFloat = true,
        )

    /** 首条大岛之后的所有电量更新：原地刷新，不再重复上浮。 */
    @SuppressLint("NotificationPermission")
    private fun showFocusNotification(
        context: Context,
        device: BluetoothDevice?,
        left: Int,
        right: Int,
        caseLevel: Int,
        leftCharging: Boolean,
        rightCharging: Boolean,
        leftIcon: Icon?,
        rightIcon: Icon?,
        caseIcon: Icon?,
    ): Boolean =
        postStatusNotification(
            context = context,
            device = device,
            left = left,
            right = right,
            caseLevel = caseLevel,
            leftCharging = leftCharging,
            rightCharging = rightCharging,
            leftIcon = leftIcon,
            rightIcon = rightIcon,
            caseIcon = caseIcon,
            firstFloat = false,
        )

    /**
     * 统一的通知构建：通知栏卡片 + 超级岛 + AOD + 降噪循环按钮共用同一份
     * focus payload。首次（firstFloat=true）展开大岛，后续原地更新。
     */
    @SuppressLint("NotificationPermission")
    private fun postStatusNotification(
        context: Context,
        device: BluetoothDevice?,
        left: Int,
        right: Int,
        caseLevel: Int,
        leftCharging: Boolean,
        rightCharging: Boolean,
        leftIcon: Icon?,
        rightIcon: Icon?,
        caseIcon: Icon?,
        firstFloat: Boolean,
    ): Boolean {
        val nm =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val content =
            buildBatteryText(
                left = left,
                right = right,
                caseLevel = caseLevel,
                leftCharging = leftCharging,
                rightCharging = rightCharging,
            )

        val ancAction = buildAncCycleAction(context, device)
        val extras =
            FocusIslandBridge.buildBatteryIslandExtras(
                leftLevel = left,
                rightLevel = right,
                caseLevel = caseLevel,
                leftCharging = leftCharging,
                rightCharging = rightCharging,
                islandTimeoutSeconds = ISLAND_TIMEOUT_SECONDS,
                deviceName = device?.name ?: "耳机",
                leftIcon = leftIcon,
                rightIcon = rightIcon,
                headsetIcon = caseIcon ?: leftIcon ?: rightIcon,
                ancAction = ancAction,
                ancLabel = ancAction.title?.toString(),
                firstFloat = firstFloat,
            )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "HyperRose 通知",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "耳机状态通知"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                setAllowBubbles(true)
            },
        )

        val builder =
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(device?.name ?: "HyperRose")
                .setContentText(content)
                .setStyle(Notification.BigTextStyle().bigText(content))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(buildQuickControlPendingIntent(context, device, left, right))
                .addAction(ancAction)
        if (extras != null) builder.addExtras(extras)

        nm.notify(ISLAND_NOTIFICATION_ID, builder.build())
        return true
    }

    @SuppressLint("NotificationPermission")
    private fun showConnectionNotification(
        context: Context,
        device: BluetoothDevice?,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL_ID,
                "HyperRose 连接提示",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "耳机连接时显示快捷控制入口"
                setShowBadge(false)
            },
        )
        val name = device?.name ?: "耳机"
        // 连接时弹窗由 OfficialFastConnectDialogHook 复用官方 MiuiFastConnectActivity 呈现，
        // 这里仅保留一条可点击进入快捷控制浮窗的 heads-up 提示。
        val popupIntent = buildQuickControlPendingIntent(context, device, -1, -1)
        val notification = Notification.Builder(context, CONNECTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("耳机已连接")
            .setContentText(name)
            .setAutoCancel(true)
            .setTimeoutAfter(8_000L)
            .setContentIntent(popupIntent)
            .build()
        nm.notify(ISLAND_NOTIFICATION_ID, notification)
    }

    private fun buildBatteryText(
        left: Int,
        right: Int,
        caseLevel: Int,
        leftCharging: Boolean,
        rightCharging: Boolean,
    ): String {
        val segments = mutableListOf<String>()
        if (left >= 0) {
            segments += "L ${formatEarBattery(left, leftCharging)}"
        }
        if (right >= 0) {
            segments += "R ${formatEarBattery(right, rightCharging)}"
        }
        if (caseLevel >= 0) {
            segments += "C $caseLevel%"
        }
        return if (segments.isEmpty()) "电量未知" else segments.joinToString(" | ")
    }

    private fun formatEarBattery(
        level: Int,
        charging: Boolean,
    ): String = if (charging) "$level% ⚡" else "$level%"

    private fun rememberIslandState(
        left: Int,
        right: Int,
        caseLevel: Int,
        leftCharging: Boolean,
        rightCharging: Boolean,
        leftImageName: String?,
        rightImageName: String?,
    ) {
        lastIslandLeft = left
        lastIslandRight = right
        lastIslandCase = caseLevel
        lastIslandLeftCharging = leftCharging
        lastIslandRightCharging = rightCharging
        lastLeftImageName = leftImageName
        lastRightImageName = rightImageName
    }

    private fun beginConnectionSession(device: BluetoothDevice?): Boolean {
        val previousAddress = lastConnectedDevice?.address
        val incomingAddress = device?.address
        val newSession = startsNewIslandSession(
            sessionActive = connectionSessionActive,
            currentAddress = previousAddress,
            incomingAddress = incomingAddress,
        )
        connectionSessionActive = true
        if (device != null) lastConnectedDevice = device
        if (newSession) {
            lastKnownCaseLevel = null
            resetIslandDeliveryState()
        }
        return newSession
    }

    private fun resetIslandDeliveryState() {
        lastIslandLeft = -1
        lastIslandRight = -1
        lastIslandCase = -1
        lastIslandLeftCharging = false
        lastIslandRightCharging = false
        lastLeftImageName = null
        lastRightImageName = null
        firstIslandShown = false
    }

    private fun buildQuickControlPendingIntent(
        context: Context,
        device: BluetoothDevice?,
        left: Int,
        right: Int,
    ): PendingIntent {
        val caseLevel = lastKnownCaseLevel ?: -1
        val intent =
            QuickControlIntentFactory.createLaunchIntent(
                deviceName = device?.name,
                deviceAddress = device?.address,
                leftLevel = left,
                rightLevel = right,
                caseLevel = caseLevel,
                forceConnected = true,
            )

        // Android 15 / HyperOS 要求 PendingIntent 创建方显式允许后台启动，
        // 否则从通知 / 岛下拉打开控制浮窗会被 BAL 拦截。
        val activityOptions =
            ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
                )
            }
        return PendingIntent.getActivity(
            context,
            QUICK_CONTROL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            activityOptions.toBundle(),
        )
    }

    private fun buildAncCycleAction(
        context: Context,
        device: BluetoothDevice?,
    ): Notification.Action {
        val nextMode = when (lastKnownAncMode) {
            AncMode.NOISE_CANCEL -> AncMode.TRANSPARENT
            AncMode.TRANSPARENT -> AncMode.NORMAL
            AncMode.NORMAL, AncMode.WIND_NOISE, null -> AncMode.NOISE_CANCEL
        }
        val intent = Intent(HyperRoseAction.ANC_SELECT).apply {
            setPackage(HyperRoseAction.PACKAGE_BLUETOOTH)
            putExtra(HyperRoseAction.EXTRA_MODE, nextMode.name)
            device?.address?.let { putExtra(HyperRoseAction.EXTRA_DEVICE_ADDRESS, it) }
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            10087,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_rotate),
            "切换${nextMode.label}",
            pendingIntent,
        ).build()
    }

    private fun resolveIcon(drawableName: String?): Icon? {
        if (drawableName == null) return null
        // 缓存：避免每个 ~20s 电池轮询都重新 decodeResource（运行在 MiBluetooth 主进程）
        iconCache[drawableName]?.let { return it }
        val ctx = moduleContext ?: return null
        val resId = ctx.resources.getIdentifier(drawableName, "drawable", "com.dohex.hyperrose")
        if (resId == 0) {
            iconCache[drawableName] = null
            return null
        }
        val bitmap = BitmapFactory.decodeResource(ctx.resources, resId) ?: run {
            iconCache[drawableName] = null
            return null
        }
        val icon = Icon.createWithBitmap(bitmap)
        iconCache[drawableName] = icon
        return icon
    }

    private fun cancelIsland(context: Context) {
        val nm =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching { nm.cancel(ISLAND_NOTIFICATION_ID) }
    }
}

internal fun startsNewIslandSession(
    sessionActive: Boolean,
    currentAddress: String?,
    incomingAddress: String?,
): Boolean =
    !sessionActive ||
        (currentAddress != null &&
            incomingAddress != null &&
            !currentAddress.equals(incomingAddress, ignoreCase = true))

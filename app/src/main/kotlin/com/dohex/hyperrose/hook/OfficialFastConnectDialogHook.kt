package com.dohex.hyperrose.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.dohex.hyperrose.hook.HyperRoseModuleEntry.Companion.TAG
import com.dohex.hyperrose.ipc.BroadcastSenderValidator
import com.dohex.hyperrose.ipc.HyperRoseIpc as HyperRoseAction
import com.dohex.hyperrose.ipc.sendHyperRoseBroadcast
import com.dohex.hyperrose.model.DeviceColorProfile
import com.dohex.hyperrose.model.EarphoneColor
import com.dohex.hyperrose.util.ReflectionHelper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 复用 Bluetooth Extension 的官方 MiuiFastConnectActivity 连接弹窗（对齐 SonyPods）。
 *
 * 官方弹窗的 Activity 与 controller 回调运行在 com.xiaomi.bluetooth:ui 进程，
 * 因此本 Hook 同时安装在主进程（负责发起官方 Activity）与 :ui 进程（负责注入真实
 * 电量/名称，并阻止官方失败检查在约 1 秒后关闭弹窗）。
 */
@SuppressLint("MissingPermission")
object OfficialFastConnectDialogHook {
    private const val LOG_TAG = "HyperRose-OfficialDialog"
    private const val XIAOMI_PACKAGE = "com.xiaomi.bluetooth"
    private const val UI_PROCESS_SUFFIX = ":ui"
    private const val FAST_CONNECT_ACTIVITY = "com.android.bluetooth.ble.app.MiuiFastConnectActivity"
    private const val FAST_CONNECT_ACTIVITY_VARIANT =
        "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity"
    private const val FAST_CONTROLLER_CLASS =
        "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectController"
    private const val FAST_CONNECT_ACTION = "com.android.bluetooth.FAST_CONNECT_DEVICE"

    // 模块专属标记：官方 Activity 类也服务于小米自家耳机流程，只有带此标记 + 地址匹配的
    // 实例才由本模块接管（fail-closed）。
    private const val EXTRA_MODULE_DIALOG_MARKER =
        "com.dohex.hyperrose.extra.OFFICIAL_FAST_CONNECT_DIALOG"
    private const val EXTRA_MODULE_DIALOG_ADDRESS =
        "com.dohex.hyperrose.extra.OFFICIAL_FAST_CONNECT_ADDRESS"
    private const val EXTRA_MODULE_DIALOG_NAME =
        "com.dohex.hyperrose.extra.OFFICIAL_FAST_CONNECT_NAME"
    private const val EXTRA_MODULE_DIALOG_PROFILE =
        "com.dohex.hyperrose.extra.OFFICIAL_FAST_CONNECT_PROFILE"
    private const val EXTRA_MODULE_DIALOG_IMAGE_RES =
        "com.dohex.hyperrose.extra.OFFICIAL_FAST_CONNECT_IMAGE_RES"
    private const val EXTRA_MODULE_DIALOG_LEFT_RES =
        "com.dohex.hyperrose.extra.OFFICIAL_FAST_CONNECT_LEFT_RES"
    private const val EXTRA_MODULE_DIALOG_RIGHT_RES =
        "com.dohex.hyperrose.extra.OFFICIAL_FAST_CONNECT_RIGHT_RES"
    private const val MODULE_DIALOG_MARKER = "hyperrose_official_fast_connect"

    private val isUiProcess: Boolean
        get() = runCatching { android.app.Application.getProcessName() }
            .getOrNull()?.endsWith(UI_PROCESS_SUFFIX) == true

    // ---- 主进程状态 ----
    private var mainStateReceiver: BroadcastReceiver? = null
    private var mainSnapshot: DialogSnapshot? = null
    private var lastLaunchedAddress: String? = null

    // ---- :ui 进程状态 ----
    private var uiStateReceiver: BroadcastReceiver? = null
    private var activeActivity: Activity? = null
    private var activeController: Any? = null
    private var activeView: View? = null
    private var activeAddress: String? = null
    private var activeName: String? = null
    private var activeProfileId: String? = null
    private var activeImageRes = 0
    private var activeLeftRes = 0
    private var activeRightRes = 0
    private var latestSnapshot: DialogSnapshot? = null
    private var officialHandler: Handler? = null
    private var handlerDispatchGuardInstalled = false
    private var batteryTextHookInstalled = false
    private val batteryTextRewriteDepth = ThreadLocal.withInitial { false }
    private val fastControllerHookedClasses = mutableSetOf<String>()
    private val fastSuccessHookedClasses = mutableSetOf<String>()
    private val uiHandler = Handler(Looper.getMainLooper())

    // ==================== 状态快照 ====================

    private data class DialogSnapshot(
        val connected: Boolean = false,
        val deviceName: String? = null,
        val deviceAddress: String? = null,
        val profileId: String? = null,
        val colorName: String? = null,
        val overall: Int? = null,
        val left: Int? = null,
        val right: Int? = null,
        val cradle: Int? = null,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
    ) {
        val isSingle: Boolean get() = overall != null
        val leftLevel: Int? get() = left ?: overall
    }

    private fun batteryFromIntent(intent: Intent): DialogSnapshot {
        val overall = intent.getIntExtra(HyperRoseAction.EXTRA_OVERALL_LEVEL, -1).takeIf { it in 0..100 }
        val left = intent.getIntExtra(HyperRoseAction.EXTRA_LEFT_LEVEL, -1).takeIf { it in 0..100 }
        val right = intent.getIntExtra(HyperRoseAction.EXTRA_RIGHT_LEVEL, -1).takeIf { it in 0..100 }
        val cradle = intent.getIntExtra(HyperRoseAction.EXTRA_CASE_LEVEL, -1).takeIf { it in 0..100 }
        return DialogSnapshot(
            connected = true,
            overall = overall,
            left = if (overall != null) null else left,
            right = if (overall != null) null else right,
            cradle = if (overall != null) null else cradle,
            leftCharging = intent.getBooleanExtra(HyperRoseAction.EXTRA_LEFT_CHARGING, false),
            rightCharging = intent.getBooleanExtra(HyperRoseAction.EXTRA_RIGHT_CHARGING, false),
        )
    }

    // ==================== 入口 ====================

    fun init(
        module: XposedModule,
        cl: ClassLoader,
    ) {
        if (isUiProcess) {
            installUiHooks(module, cl)
        } else {
            installMainHooks(module, cl)
        }
    }

    // ==================== 主进程：发起官方弹窗 ====================

    private fun installMainHooks(module: XposedModule, cl: ClassLoader) {
        currentApplicationContext()?.let { registerMainStateReceiver(module, it) }
        // MiuiBluetoothNotification 构造成功可作为注册广播接收器的第二次机会。
        runCatching {
            val notifClass = cl.loadClass("com.android.bluetooth.ble.app.MiuiBluetoothNotification")
            val ctor = notifClass.declaredConstructors.firstOrNull { it.parameterCount == 2 }
            if (ctor != null) {
                module.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val context = chain.thisObject?.let { obj ->
                            ReflectionHelper.getField(obj, "mContext") as? Context
                        }
                        if (context != null) registerMainStateReceiver(module, context)
                    }
                    result
                }
            }
        }.onFailure { module.log(Log.WARN, LOG_TAG, "MiuiBluetoothNotification hook skipped", it) }
    }

    private fun registerMainStateReceiver(module: XposedModule, context: Context) {
        if (mainStateReceiver != null) return
        val appContext = context.applicationContext ?: context
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (!BroadcastSenderValidator.isAllowed(
                            ctx.packageManager,
                            sentFromUid,
                            setOf(HyperRoseAction.PACKAGE_BLUETOOTH, HyperRoseAction.PACKAGE_APP),
                        )
                    ) return
                    when (intent.action) {
                        HyperRoseAction.DEVICE_CONNECTED -> {
                            val device = intent.getParcelableExtra(
                                HyperRoseAction.EXTRA_DEVICE, BluetoothDevice::class.java,
                            )
                            val battery = batteryFromIntent(intent)
                            val snapshot =
                                DialogSnapshot(
                                    connected = true,
                                    deviceName = runCatching { device?.name ?: device?.alias }.getOrNull(),
                                    deviceAddress = device?.address,
                                    profileId = intent.getStringExtra(HyperRoseAction.EXTRA_PROFILE_ID),
                                    colorName = intent.getStringExtra(HyperRoseAction.EXTRA_COLOR),
                                    overall = battery.overall,
                                    left = battery.left,
                                    right = battery.right,
                                    cradle = battery.cradle,
                                    leftCharging = battery.leftCharging,
                                    rightCharging = battery.rightCharging,
                                )
                            mainSnapshot = snapshot
                            maybeLaunchOfficialDialog(appContext, snapshot)
                        }

                        HyperRoseAction.BATTERY_CHANGED -> {
                            val battery = batteryFromIntent(intent)
                            val current = mainSnapshot
                            if (current != null && current.connected) {
                                mainSnapshot =
                                    current.copy(
                                        overall = battery.overall,
                                        left = battery.left,
                                        right = battery.right,
                                        cradle = battery.cradle,
                                        leftCharging = battery.leftCharging,
                                        rightCharging = battery.rightCharging,
                                    )
                            }
                        }

                        HyperRoseAction.DEVICE_DISCONNECTED -> {
                            mainSnapshot = null
                            lastLaunchedAddress = null
                        }
                    }
                }
            }
        val filter =
            IntentFilter(HyperRoseAction.DEVICE_CONNECTED).apply {
                addAction(HyperRoseAction.DEVICE_DISCONNECTED)
                addAction(HyperRoseAction.BATTERY_CHANGED)
            }
        runCatching {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            mainStateReceiver = receiver
        }.onFailure { module.log(Log.WARN, LOG_TAG, "main state receiver registration failed", it) }
    }

    private fun maybeLaunchOfficialDialog(context: Context, snapshot: DialogSnapshot) {
        val address = snapshot.deviceAddress ?: return
        if (!snapshot.connected) return
        if (address.equals(lastLaunchedAddress, ignoreCase = true)) return
        // 模块 App 前台时抑制弹窗：用户已经在 App 里，无需再抢一个连接弹窗。
        if (isModuleUiForeground(context)) {
            Log.i(LOG_TAG, "official dialog suppressed: module UI foreground address=$address")
            return
        }
        if (launchOfficialActivity(context, snapshot)) {
            lastLaunchedAddress = address
        }
    }

    private fun launchOfficialActivity(context: Context, snapshot: DialogSnapshot): Boolean {
        val address = snapshot.deviceAddress ?: return false
        val device = runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }
            .getOrNull() ?: return false
        val deviceName = snapshot.deviceName ?: runCatching { device.name ?: device.alias }.getOrNull()

        val profile = DeviceColorProfile.forDevice(snapshot.profileId)
        val color = snapshot.colorName?.let { runCatching { EarphoneColor.valueOf(it) }.getOrNull() }
        val theme = profile?.let { p -> color?.let { p.themeFor(it) } ?: p.defaultTheme() }
        val imageRes = theme?.caseRes ?: 0
        val leftRes = theme?.leftRes ?: 0
        val rightRes = theme?.rightRes ?: 0

        val intent =
            Intent().apply {
                setClassName(XIAOMI_PACKAGE, FAST_CONNECT_ACTIVITY_VARIANT)
                action = FAST_CONNECT_ACTION
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("android.bluetooth.device.extra.DEVICE", device)
                deviceName?.takeIf { it.isNotBlank() }?.let { putExtra("device_name_over_write", it) }
                // 01010200 = 内置 Redmi AirDots controller，给官方 TWS 布局。
                putExtra("headset_miui_data", fakeAirDotsData(snapshot))
                putExtra("headset_adv_row_bytes", fakeAdvRowData(snapshot))
                putExtra(
                    "headset_addresses",
                    arrayOf(address, "00:00:00:00:00:00", deviceName?.takeIf { it.isNotBlank() } ?: address),
                )
                putExtra("type_layout_hid_fastconnect", 1)
                putExtra("headset_extra_data", intArrayOf(0, 0x0F, 0, 1, 0))
                putExtra("current_a2dp_devices", 0)
                putExtra(EXTRA_MODULE_DIALOG_MARKER, MODULE_DIALOG_MARKER)
                putExtra(EXTRA_MODULE_DIALOG_ADDRESS, address)
                putExtra(EXTRA_MODULE_DIALOG_NAME, deviceName ?: "")
                putExtra(EXTRA_MODULE_DIALOG_PROFILE, snapshot.profileId ?: "")
                putExtra(EXTRA_MODULE_DIALOG_IMAGE_RES, imageRes)
                putExtra(EXTRA_MODULE_DIALOG_LEFT_RES, leftRes)
                putExtra(EXTRA_MODULE_DIALOG_RIGHT_RES, rightRes)
            }
        return runCatching {
            context.startActivity(intent)
            Log.i(LOG_TAG, "official PairingDialog Activity launched address=$address name=$deviceName")
            true
        }.onFailure {
            Log.e(LOG_TAG, "official PairingDialog Activity launch failed", it)
        }.getOrDefault(false)
    }

    private fun fakeAirDotsData(snapshot: DialogSnapshot): ByteArray {
        fun level(value: Int?): Int = value?.coerceIn(0, 100) ?: 0
        return ByteArray(24).apply {
            this[0] = 0x16
            this[1] = 0x01
            this[2] = 0x02
            this[3] = 0x00
            this[4] = 0x00
            this[5] = level(snapshot.leftLevel).toByte()
            this[6] = level(snapshot.right).toByte()
            this[7] = level(snapshot.cradle).toByte()
            this[8] = 0x01
            this[9] = 0x02
            this[10] = 0x03
            this[11] = 0x04
            this[12] = 0x05
            this[13] = 0x06
            this[14] = 0x07
            this[15] = 0x08
            this[16] = 0x09
        }
    }

    private fun fakeAdvRowData(snapshot: DialogSnapshot): ByteArray {
        fun level(value: Int?): Int = value?.coerceIn(0, 100) ?: 0
        val name = snapshot.deviceName?.takeIf { it.isNotBlank() } ?: "HyperRose"
        val nameBytes = name.toByteArray(Charsets.UTF_8).take(20).toByteArray()
        val manufacturerPayload =
            byteArrayOf(
                0x16, 0x01, 0x02, 0x00, 0x00,
                level(snapshot.leftLevel).toByte(),
                level(snapshot.right).toByte(),
                level(snapshot.cradle).toByte(),
            )
        return byteArrayOf(
            (manufacturerPayload.size + 3).toByte(), 0xFF.toByte(), 0x01, 0x02,
        ) + manufacturerPayload + byteArrayOf((nameBytes.size + 1).toByte(), 0x09) + nameBytes
    }

    // ==================== :ui 进程：注入真实状态 ====================

    private fun installUiHooks(module: XposedModule, cl: ClassLoader) {
        installFrameworkActivityHooks(module, cl)
        installActivityHooks(module, cl, FAST_CONNECT_ACTIVITY)
        installActivityHooks(module, cl, FAST_CONNECT_ACTIVITY_VARIANT)
        installHandlerDispatchGuard(module, cl)
        installBatteryTextGuard(module, cl)
        module.log(Log.INFO, LOG_TAG, "official dialog :ui hooks installed")
    }

    private fun installFrameworkActivityHooks(module: XposedModule, cl: ClassLoader) {
        runCatching {
            val activityClass = cl.loadClass("android.app.Activity")
            val onCreate = activityClass.getDeclaredMethod("onCreate", Bundle::class.java)
            module.hook(onCreate).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && isManagedOfficialActivity(activity)) {
                    onOfficialActivityCreated(module, activity)
                }
                result
            }
            val onDestroy = activityClass.getDeclaredMethod("onDestroy")
            module.hook(onDestroy).intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null && isManagedOfficialActivity(activity)) {
                    onOfficialActivityDestroyed(activity)
                }
                chain.proceed()
            }
            module.log(Log.INFO, LOG_TAG, "official framework Activity fallback hooks installed")
        }.onFailure { module.log(Log.WARN, LOG_TAG, "official framework Activity hook unavailable", it) }
    }

    private fun installActivityHooks(module: XposedModule, cl: ClassLoader, className: String) {
        runCatching {
            val activityClass = cl.loadClass(className)
            val onCreate = activityClass.getDeclaredMethod("onCreate", Bundle::class.java)
            module.hook(onCreate).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && isManagedOfficialActivity(activity)) {
                    onOfficialActivityCreated(module, activity)
                }
                result
            }
            val onDestroy = activityClass.getDeclaredMethod("onDestroy")
            module.hook(onDestroy).intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null && isManagedOfficialActivity(activity)) {
                    onOfficialActivityDestroyed(activity)
                }
                chain.proceed()
            }
            module.log(Log.INFO, LOG_TAG, "official Activity hooks installed class=$className")
        }.onFailure { module.log(Log.WARN, LOG_TAG, "official Activity hooks unavailable class=$className", it) }
    }

    private fun onOfficialActivityCreated(module: XposedModule, activity: Activity) {
        val dialogAddress = managedOfficialAddress(activity) ?: return
        if (activeActivity === activity && activeController != null) return
        activeActivity = activity
        activeAddress = dialogAddress
        activeName = activity.intent?.getStringExtra(EXTRA_MODULE_DIALOG_NAME)
            ?: activity.intent?.getStringArrayExtra("headset_addresses")?.getOrNull(2)
        activeProfileId = activity.intent?.getStringExtra(EXTRA_MODULE_DIALOG_PROFILE)
        activeImageRes = activity.intent?.getIntExtra(EXTRA_MODULE_DIALOG_IMAGE_RES, 0) ?: 0
        activeLeftRes = activity.intent?.getIntExtra(EXTRA_MODULE_DIALOG_LEFT_RES, 0) ?: 0
        activeRightRes = activity.intent?.getIntExtra(EXTRA_MODULE_DIALOG_RIGHT_RES, 0) ?: 0
        // 立即建立已连接快照：让 Handler 守卫在首个电量广播前就能抑制官方失败检查，
        // 避免弹窗约 1 秒后被自动关闭（一闪而过）。
        latestSnapshot =
            DialogSnapshot(
                connected = true,
                deviceName = activeName,
                deviceAddress = activeAddress,
                profileId = activeProfileId,
            )
        registerUiStateReceiver(module, activity)

        activeController = findActivityController(activity)
        activeView = activeController?.let(::findControllerView)
        officialHandler = activeController?.let(::findControllerHandler)
        installFastControllerHooks(module, activeController, activity.classLoader)

        // 请求蓝牙进程重发状态，弥补 :ui 进程晚于连接启动而漏掉广播的窗口。
        runCatching {
            activity.sendHyperRoseBroadcast(
                Intent(HyperRoseAction.REFRESH_STATUS).apply {
                    setPackage(HyperRoseAction.PACKAGE_BLUETOOTH)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                },
            )
        }

        // 子类 onCreate 尚未给 controller 字段赋值，延迟重绑一次。
        uiHandler.post {
            if (activeActivity !== activity) return@post
            findActivityController(activity)?.let { controller ->
                activeController = controller
                activeView = findControllerView(controller)
                officialHandler = findControllerHandler(controller)
                installFastControllerHooks(module, controller, activity.classLoader)
                latestSnapshot?.let { applySnapshot(it) }
            }
        }
        latestSnapshot?.let { applySnapshot(it) }
        Log.i(LOG_TAG, "official Activity created address=$dialogAddress controller=${activeController?.javaClass?.name}")
    }

    private fun onOfficialActivityDestroyed(activity: Activity?) {
        if (activity == null || activity === activeActivity) {
            activeActivity = null
            activeController = null
            activeView = null
            officialHandler = null
            activeAddress = null
            activeName = null
            activeProfileId = null
            activeImageRes = 0
            activeLeftRes = 0
            activeRightRes = 0
        }
    }

    /**
     * 官方 controller 的成功回调后，会 post 一个延迟失败检查；我们的桥接已是权威连接源，
     * 该检查对合成 payload 是假阴性，约 1 秒后关闭弹窗。按 Handler 运行时类型识别并
     * 仅抑制 message 3/6，且仅在桥接仍报告已连接时生效。
     */
    private fun installHandlerDispatchGuard(module: XposedModule, cl: ClassLoader) {
        if (handlerDispatchGuardInstalled) return
        runCatching {
            val handlerClass = cl.loadClass("android.os.Handler")
            val dispatchMessage = handlerClass.getDeclaredMethod("dispatchMessage", Message::class.java)
            module.hook(dispatchMessage).intercept { chain ->
                val handler = chain.thisObject as? Handler
                val message = chain.getArg(0) as? Message
                val snapshot = latestSnapshot
                if (handler != null && message != null && snapshot != null &&
                    isManagedOfficialTarget(activeActivity) &&
                    handler === officialHandler &&
                    message.what in setOf(3, 6) &&
                    snapshot.connected
                ) {
                    Log.i(LOG_TAG, "official dialog ignored automatic close message=${message.what}")
                    return@intercept null
                }
                chain.proceed()
            }
            handlerDispatchGuardInstalled = true
            module.log(Log.INFO, LOG_TAG, "official dialog Handler guard installed")
        }.onFailure { module.log(Log.WARN, LOG_TAG, "official dialog Handler guard unavailable", it) }
    }

    /**
     * 官方渲染器会把自己的 fallback "0%" 写进语义电量 TextView。既然桥接里已有
     * 真实电量，就在 TextView.setText 的最终文本边界兜底：只替换 "0%"/"0"。
     */
    private fun installBatteryTextGuard(module: XposedModule, cl: ClassLoader) {
        if (batteryTextHookInstalled) return
        runCatching {
            val textViewClass = cl.loadClass("android.widget.TextView")
            val setText = textViewClass.getDeclaredMethod("setText", CharSequence::class.java)
            module.hook(setText).intercept { chain ->
                val result = chain.proceed()
                val textView = chain.thisObject as? TextView ?: return@intercept result
                if (batteryTextRewriteDepth.get() == true) return@intercept result
                val snapshot = latestSnapshot ?: return@intercept result
                if (!snapshot.connected ||
                    !isManagedOfficialTarget(activeActivity) ||
                    !isActiveBatteryTextView(textView)
                ) return@intercept result
                val value = batteryValueForTextView(textView, snapshot) ?: return@intercept result
                val current = textView.text?.toString()?.trim()
                if (current != "0%" && current != "0") return@intercept result
                batteryTextRewriteDepth.set(true)
                try {
                    textView.text = "${value.coerceIn(0, 100)}%"
                } finally {
                    batteryTextRewriteDepth.set(false)
                }
                result
            }
            batteryTextHookInstalled = true
            module.log(Log.INFO, LOG_TAG, "official dialog battery TextView guard installed")
        }.onFailure { module.log(Log.WARN, LOG_TAG, "official dialog battery TextView guard unavailable", it) }
    }

    private fun isActiveBatteryTextView(textView: TextView): Boolean {
        val activity = activeActivity ?: return false
        if (!isManagedOfficialTarget(activity)) return false
        val resourceName = resourceEntryName(activity, textView.id).lowercase()
        if (!resourceName.contains("battery") ||
            (!resourceName.contains("percent") && !resourceName.contains("level"))
        ) return false
        val roots = listOfNotNull(activeView, activity.window?.decorView).distinct()
        return roots.any { root -> allViews(root).any { it === textView } }
    }

    private fun batteryValueForTextView(textView: TextView, snapshot: DialogSnapshot): Int? {
        val activity = activeActivity ?: return null
        val name = resourceEntryName(activity, textView.id).lowercase()
        return when {
            name.contains("left") || name.contains("headsetl") -> snapshot.leftLevel
            name.contains("right") || name.contains("headsetr") -> snapshot.right
            name.contains("box") || name.contains("case") || name.contains("cradle") -> snapshot.cradle
            else -> snapshot.leftLevel ?: snapshot.right
        }
    }

    private fun installFastControllerHooks(module: XposedModule, controller: Any?, loader: ClassLoader) {
        val cl = controller?.javaClass?.classLoader ?: loader
        installFastControllerHook(module, FAST_CONTROLLER_CLASS, cl)
        installFastSuccessHook(module, FAST_CONTROLLER_CLASS, cl)
        controller?.javaClass?.name
            ?.takeIf { it != FAST_CONTROLLER_CLASS }
            ?.let {
                installFastControllerHook(module, it, cl)
                installFastSuccessHook(module, it, cl)
            }
    }

    private fun installFastControllerHook(module: XposedModule, className: String, classLoader: ClassLoader) {
        if (className in fastControllerHookedClasses) return
        runCatching {
            val c = Class.forName(className, false, classLoader)
            val modifyView =
                c.declaredMethods.firstOrNull {
                    it.name == "modifyView" && it.parameterTypes.size == 4
                } ?: return@runCatching
            module.hook(modifyView).intercept { chain ->
                val result = chain.proceed()
                val view = chain.args.filterIsInstance<View>().firstOrNull() ?: activeView
                onOfficialViewRefreshed(view, chain.thisObject)
                result
            }
            fastControllerHookedClasses += className
            module.log(Log.INFO, LOG_TAG, "official fast controller modifyView hook installed class=$className")
        }.onFailure { module.log(Log.WARN, LOG_TAG, "official fast controller modifyView unavailable class=$className", it) }
    }

    private fun installFastSuccessHook(module: XposedModule, className: String, classLoader: ClassLoader) {
        if (className in fastSuccessHookedClasses) return
        runCatching {
            val c = Class.forName(className, false, classLoader)
            val method = c.declaredMethods.firstOrNull { it.name == "updateConnectSuccessDilog" }
                ?: return@runCatching
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val view = chain.args.filterIsInstance<View>().firstOrNull()
                    ?: activeView
                    ?: activeController?.let(::findControllerView)
                onOfficialViewRefreshed(view, chain.thisObject)
                result
            }
            fastSuccessHookedClasses += className
            module.log(Log.INFO, LOG_TAG, "official fast success hook installed class=$className")
        }.onFailure { module.log(Log.WARN, LOG_TAG, "official fast success hook unavailable class=$className", it) }
    }

    private fun onOfficialViewRefreshed(view: View?, controller: Any?) {
        if (view == null) return
        if (!isManagedOfficialTarget(activeActivity) ||
            (controller != null && controller !== activeController)
        ) return
        activeView = view
        latestSnapshot?.let { snapshot ->
            applyOfficialIdentity(snapshot)
            refreshBatteryText(view, snapshot)
            refreshBatteryIcons(view, snapshot)
            replaceOfficialImages(view)
        }
    }

    private fun registerUiStateReceiver(module: XposedModule, context: Context) {
        if (uiStateReceiver != null) return
        val appContext = context.applicationContext ?: context
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (!BroadcastSenderValidator.isAllowed(
                            ctx.packageManager,
                            sentFromUid,
                            setOf(HyperRoseAction.PACKAGE_BLUETOOTH, HyperRoseAction.PACKAGE_APP),
                        )
                    ) return
                    when (intent.action) {
                        HyperRoseAction.BATTERY_CHANGED -> {
                            val battery = batteryFromIntent(intent)
                            latestSnapshot =
                                DialogSnapshot(
                                    connected = true,
                                    deviceName = activeName,
                                    deviceAddress = activeAddress,
                                    profileId = activeProfileId,
                                    overall = battery.overall,
                                    left = battery.left,
                                    right = battery.right,
                                    cradle = battery.cradle,
                                    leftCharging = battery.leftCharging,
                                    rightCharging = battery.rightCharging,
                                )
                            latestSnapshot?.let { applySnapshot(it) }
                        }

                        HyperRoseAction.DEVICE_DISCONNECTED -> {
                            latestSnapshot =
                                DialogSnapshot(connected = false, deviceAddress = activeAddress, deviceName = activeName)
                            latestSnapshot?.let { applySnapshot(it) }
                        }
                    }
                }
            }
        val filter =
            IntentFilter(HyperRoseAction.BATTERY_CHANGED).apply {
                addAction(HyperRoseAction.DEVICE_DISCONNECTED)
            }
        runCatching {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            uiStateReceiver = receiver
        }.onFailure { module.log(Log.WARN, LOG_TAG, "ui state receiver registration failed", it) }
    }

    private fun applySnapshot(snapshot: DialogSnapshot) {
        val activity = activeActivity ?: return
        if (!isManagedOfficialActivity(activity)) return
        val address = snapshot.deviceAddress ?: activeAddress ?: return
        if (activeAddress != null && !address.equals(activeAddress, ignoreCase = true)) return

        applyOfficialIdentity(snapshot)

        if (!snapshot.connected) {
            Log.i(LOG_TAG, "official dialog dismissed after disconnect address=$address")
            if (!activity.isFinishing) activity.finish()
            return
        }
        activeView?.let { view ->
            refreshBatteryText(view, snapshot)
            refreshBatteryIcons(view, snapshot)
            replaceOfficialImages(view)
        }
        Log.i(LOG_TAG, "official dialog state applied battery=${snapshot.left}/${snapshot.right}/${snapshot.cradle} single=${snapshot.isSingle}")
    }

    private fun applyOfficialIdentity(snapshot: DialogSnapshot?) {
        val activity = activeActivity ?: return
        val name = snapshot?.deviceName?.trim()?.takeIf { it.isNotBlank() }
            ?: activity.intent?.getStringArrayExtra("headset_addresses")?.getOrNull(2)
                ?.takeIf { it.isNotBlank() && !it.equals(activeAddress, ignoreCase = true) }
            ?: return

        val controller = activeController
        listOf(
            "mDeviceNameOverWrite",
            "deviceNameOverWrite",
            "mDeviceNameForDialog",
            "mDeviceNameForDialogLocal",
            "deviceNameForDialog",
        ).forEach { field ->
            runCatching { ReflectionHelper.setField(controller ?: return@forEach, field, name) }
        }

        val roots = listOfNotNull(activeView, activity.window?.decorView).distinct()
        roots.flatMap(::allViews)
            .filterIsInstance<TextView>()
            .filter { textView ->
                val resourceName = resourceEntryName(activity, textView.id).lowercase()
                resourceName.contains("title") ||
                    resourceName.contains("pairing") ||
                    textView.text?.toString() == "Air 2s"
            }
            .forEach { textView -> textView.text = name }
        Log.d(LOG_TAG, "official dialog name applied name=$name")
    }

    private fun refreshBatteryText(view: View, snapshot: DialogSnapshot) {
        val activity = activeActivity ?: return
        val roots = listOfNotNull(view, activity.window?.decorView).distinct()
        hideOfficialChargingIndicators(roots, activity)
        var updated = 0

        fun resourceId(resourceName: String): Int {
            val packages = listOf(XIAOMI_PACKAGE, activity.packageName)
            return packages.asSequence()
                .map { pkg -> runCatching { activity.resources.getIdentifier(resourceName, "id", pkg) }.getOrDefault(0) }
                .firstOrNull { it != 0 } ?: 0
        }

        fun showPathToRoot(target: View) {
            var current: View? = target
            while (current != null) {
                current.visibility = View.VISIBLE
                if (roots.any { it === current }) break
                current = current.parent as? View
            }
        }

        fun setPercent(resourceName: String, value: Int?) {
            val id = resourceId(resourceName)
            val targets = roots.flatMap(::allViews)
                .filterIsInstance<TextView>()
                .filter { textView ->
                    textView.id == id ||
                        resourceEntryName(activity, textView.id).equals(resourceName, ignoreCase = true)
                }
                .distinct()
            targets.forEach { textView ->
                if (value == null) {
                    textView.visibility = View.GONE
                    (textView.parent as? View)?.visibility = View.GONE
                } else {
                    textView.text = "${value.coerceIn(0, 100)}%"
                    showPathToRoot(textView)
                    updated++
                }
            }
        }

        if (snapshot.isSingle) {
            setPercent("textViewBoxBatteryPercent", snapshot.leftLevel)
        } else {
            setPercent("textViewHeadsetLBatteryPercent", snapshot.left)
            setPercent("textViewHeadsetRBatteryPercent", snapshot.right)
            setPercent("textViewBoxBatteryPercent", snapshot.cradle)
        }
        Log.d(LOG_TAG, "official dialog battery text applied updated=$updated values=${snapshot.left}/${snapshot.right}/${snapshot.cradle}")
    }

    private fun refreshBatteryIcons(view: View, snapshot: DialogSnapshot) {
        val activity = activeActivity ?: return
        val roots = listOfNotNull(view, activity.window?.decorView).distinct()
        hideOfficialChargingIndicators(roots, activity)
        val controller = activeController
        val arrays = controller?.let { findOfficialBatteryArrays(it, activity) }
        var updated = 0

        fun setIcon(resourceName: String, value: Int?) {
            val targets = roots.flatMap(::allViews)
                .filterIsInstance<ImageView>()
                .filter { imageView ->
                    resourceEntryName(activity, imageView.id).equals(resourceName, ignoreCase = true)
                }
                .distinct()
            if (value == null) {
                targets.forEach { imageView ->
                    imageView.visibility = View.GONE
                    (imageView.parent as? View)?.visibility = View.GONE
                }
                return
            }
            val drawable =
                if (controller != null && arrays != null) {
                    officialBatteryDrawable(controller, arrays, value)
                } else {
                    null
                } ?: return
            targets.forEach { imageView ->
                imageView.setImageDrawable(drawable.constantState?.newDrawable() ?: drawable)
                imageView.visibility = View.VISIBLE
                (imageView.parent as? View)?.visibility = View.VISIBLE
                updated++
            }
        }

        if (snapshot.isSingle) {
            setIcon("imageViewBoxBattery", snapshot.leftLevel)
        } else {
            setIcon("imageViewHeadsetLBattery", snapshot.left)
            setIcon("imageViewHeadsetRBattery", snapshot.right)
            setIcon("imageViewBoxBattery", snapshot.cradle)
        }
        Log.d(LOG_TAG, "official dialog battery icons applied updated=$updated")
    }

    private fun hideOfficialChargingIndicators(roots: List<View>, activity: Activity) {
        listOf(
            "imageViewHeadsetLCharge",
            "imageViewHeadsetRCharge",
            "imageViewBoxCharge",
        ).mapNotNull { findViewByResourceName(roots, activity, it) }
            .distinct()
            .forEach { it.visibility = View.GONE }
    }

    private fun replaceOfficialImages(view: View) {
        val activity = activeActivity ?: return
        val caseRes = activeImageRes
        val leftRes = activeLeftRes
        val rightRes = activeRightRes
        val roots = listOfNotNull(view, activity.window?.decorView).distinct()
        val imageViews = roots.flatMap(::allViews).filterIsInstance<ImageView>().distinct()

        val caseImage = loadModuleDrawable(activity, caseRes)
        val leftImage = loadModuleDrawable(activity, leftRes) ?: caseImage
        val rightImage = loadModuleDrawable(activity, rightRes) ?: caseImage

        fun applyTo(resName: String, drawable: Drawable?) {
            if (drawable == null) return
            imageViews.firstOrNull { imageResourceMatches(activity, it, resName) }
                ?.let { iv ->
                    iv.setImageDrawable(drawable)
                    iv.visibility = View.VISIBLE
                    (iv.parent as? View)?.visibility = View.VISIBLE
                }
        }

        // 大图（imageViewHeadset）= 整机(盒)；三行小图依次：左耳 / 右耳 / 整机(盒)。
        // imageViewBox 与大图同位置重叠，隐藏之，避免盖住整机图。
        applyTo("imageViewHeadset", caseImage)
        applyTo("imageViewHeadsetLBattery", leftImage)
        applyTo("imageViewHeadsetRBattery", rightImage)
        applyTo("imageViewBoxBattery", caseImage)
        imageViews.firstOrNull { imageResourceMatches(activity, it, "imageViewBox") }?.visibility = View.GONE

        // 大图在弹窗根视图内水平居中（逐级向上居中，兼容嵌套容器）。
        imageViews.firstOrNull { imageResourceMatches(activity, it, "imageViewHeadset") }
            ?.let { centerOfficialImage(view, it) }

        // 诊断（居中后）：含父容器宽度与 translationX，便于确认是否居中到位。
        imageViews.sortedBy { it.left }.forEach { iv ->
            val name = resourceEntryName(activity, iv.id).lowercase()
            if (name.contains("headset") || name.contains("box") || name.contains("ear") || name.contains("battery")) {
                val pw = (iv.parent as? View)?.width ?: -1
                Log.i(LOG_TAG, "[HR-DUMP] name=$name x=${iv.left} w=${iv.width} tx=${iv.translationX} parentW=$pw")
            }
        }
        Log.d(LOG_TAG, "official dialog images applied case=$caseRes left=$leftRes right=$rightRes")
    }

    /** 逐级向上把 image 居中到 root（对齐 SonyPods centerOfficialImage）。 */
    private fun centerOfficialImage(root: View, image: View) {
        var child: View? = image
        var guard = 0
        while (child != null && child !== root && guard++ < 8) {
            centerViewInParent(child)
            child = child.parent as? View
        }
    }

    /** 将指定 View 在其直接父容器内水平居中。 */
    private fun centerViewInParent(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        val params = view.layoutParams ?: return
        when (params) {
            is LinearLayout.LayoutParams -> {
                if (params.weight > 0f) {
                    params.weight = 0f
                    if (params.width == 0) params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                params.gravity =
                    (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) or Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }

            is RelativeLayout.LayoutParams -> {
                params.addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
                params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, 0)
                params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, 0)
                params.addRule(RelativeLayout.ALIGN_PARENT_START, 0)
                params.addRule(RelativeLayout.ALIGN_PARENT_END, 0)
                view.layoutParams = params
            }

            is FrameLayout.LayoutParams -> {
                params.gravity =
                    (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) or Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }
        }
        // 对自定义/嵌套容器做布局后二次校正。
        view.post {
            val parentWidth = parent.width
            val childWidth = view.width
            if (parentWidth > 0 && childWidth > 0) {
                view.translationX = (parentWidth - childWidth) / 2f - view.left
            }
        }
        parent.requestLayout()
    }

    private fun loadModuleDrawable(context: Context, resId: Int): Drawable? {
        if (resId == 0) return null
        return runCatching {
            val moduleCtx = context.createPackageContext("com.dohex.hyperrose", Context.CONTEXT_IGNORE_SECURITY)
            val drawable = moduleCtx.resources.getDrawable(resId, context.theme)
            if (drawable is BitmapDrawable && drawable.bitmap == null) {
                drawable.bitmap = android.graphics.BitmapFactory.decodeResource(moduleCtx.resources, resId)
            }
            drawable
        }.getOrNull()
    }

    // ==================== 身份校验（fail-closed） ====================

    private fun managedOfficialAddress(activity: Activity?): String? {
        if (activity == null) return null
        if (activity.javaClass.name != FAST_CONNECT_ACTIVITY &&
            activity.javaClass.name != FAST_CONNECT_ACTIVITY_VARIANT
        ) return null
        val intent = activity.intent ?: return null
        if (intent.getStringExtra(EXTRA_MODULE_DIALOG_MARKER) != MODULE_DIALOG_MARKER) return null
        return intent.getStringExtra(EXTRA_MODULE_DIALOG_ADDRESS)
            ?.trim()
            ?.takeIf(::isBluetoothAddress)
    }

    private fun isManagedOfficialActivity(activity: Activity?): Boolean {
        val address = managedOfficialAddress(activity) ?: return false
        val active = activeAddress
        return active == null || active.equals(address, ignoreCase = true)
    }

    private fun isManagedOfficialTarget(activity: Activity?): Boolean {
        val address = managedOfficialAddress(activity) ?: return false
        if (activeAddress != null && !activeAddress.equals(address, ignoreCase = true)) return false
        val snapshotAddress = latestSnapshot?.deviceAddress
        return snapshotAddress == null || snapshotAddress.equals(address, ignoreCase = true)
    }

    private fun isBluetoothAddress(value: String): Boolean =
        value.matches(Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$"))

    // ==================== 反射辅助 ====================

    private fun findActivityController(activity: Activity): Any? {
        var type: Class<*>? = activity.javaClass
        val candidates = ArrayList<Any>()
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(activity)
                }.getOrNull()?.let { value ->
                    val name = value.javaClass.name.lowercase()
                    if (name.contains("controller") && name.contains("fastconnect")) {
                        candidates += value
                    }
                }
            }
            type = type.superclass
        }
        return candidates.firstOrNull()
    }

    private fun findControllerView(controller: Any): View? {
        var type: Class<*>? = controller.javaClass
        val candidates = ArrayList<View>()
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller)
                }.getOrNull()?.let { value ->
                    if (value is View) candidates += value
                }
            }
            type = type.superclass
        }
        return candidates.firstOrNull()
    }

    private fun findControllerHandler(controller: Any): Handler? {
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                if (!Handler::class.java.isAssignableFrom(field.type)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller) as? Handler
                }.getOrNull()?.let { return it }
            }
            type = type.superclass
        }
        return null
    }

    private data class OfficialBatteryArrays(
        val light: IntArray,
        val dark: IntArray,
    )

    private fun findOfficialBatteryArrays(controller: Any, activity: Activity): OfficialBatteryArrays? {
        val arrays = ArrayList<IntArray>()
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) ||
                    field.type != IntArray::class.java
                ) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller) as? IntArray
                }.getOrNull()?.let { value -> if (value.isNotEmpty()) arrays += value }
            }
            type = type.superclass
        }

        fun isBatteryArray(value: IntArray, dark: Boolean): Boolean {
            val names = value.map { id -> resourceEntryName(activity, id).lowercase() }
            val batteryNames = names.filter { it.contains("battery") }
            if (batteryNames.size < 4) return false
            if (dark && batteryNames.none { it.contains("dark") }) return false
            if (!dark && batteryNames.any { it.contains("dark") }) return false
            return batteryNames.any { it.contains("_0") } && batteryNames.any { it.contains("100") }
        }

        val light = arrays.firstOrNull { isBatteryArray(it, dark = false) }
        val dark = arrays.firstOrNull { isBatteryArray(it, dark = true) }
        return if (light != null && dark != null) OfficialBatteryArrays(light, dark) else null
    }

    private fun officialBatteryDrawable(
        controller: Any,
        arrays: OfficialBatteryArrays,
        value: Int,
    ): Drawable? {
        val activity = activeActivity ?: return null
        val bucket = ((value.coerceIn(0, 100) + 19) / 20).coerceAtMost(5)
        val resourceId = (if (isDarkMode(activity)) arrays.dark else arrays.light)
            .getOrNull(bucket) ?: return null
        return invokeOfficialDrawableLoader(controller, resourceId)
            ?: runCatching { activity.resources.getDrawable(resourceId, activity.theme) }.getOrNull()
    }

    private fun invokeOfficialDrawableLoader(controller: Any, resourceId: Int): Drawable? {
        val candidates = ArrayList<Any>()
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller)
                }.getOrNull()?.let { value ->
                    if (value !is android.content.res.Resources && value !is View) candidates += value
                }
            }
            type = type.superclass
        }
        candidates.forEach { candidate ->
            var candidateType: Class<*>? = candidate.javaClass
            while (candidateType != null) {
                candidateType.declaredMethods.forEach { method ->
                    if (java.lang.reflect.Modifier.isStatic(method.modifiers) ||
                        method.parameterTypes.size != 1 ||
                        method.parameterTypes[0] != Int::class.javaPrimitiveType ||
                        !Drawable::class.java.isAssignableFrom(method.returnType)
                    ) return@forEach
                    runCatching {
                        method.isAccessible = true
                        method.invoke(candidate, resourceId) as? Drawable
                    }.getOrNull()?.let { return it }
                }
                candidateType = candidateType.superclass
            }
        }
        return null
    }

    private fun isDarkMode(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and 0x30) == 0x20

    private fun allViews(root: View): List<View> {
        val result = ArrayList<View>()
        fun visit(view: View) {
            result += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }

    private fun resourceEntryName(activity: Activity, id: Int): String =
        if (id == View.NO_ID) "" else runCatching {
            activity.resources.getResourceEntryName(id)
        }.getOrDefault("")

    private fun findViewByResourceName(roots: List<View>, activity: Activity, name: String): View? =
        roots.flatMap(::allViews).firstOrNull {
            resourceEntryName(activity, it.id).equals(name, ignoreCase = true)
        }

    private fun imageResourceMatches(activity: Activity, view: ImageView, entryName: String): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        val expected = listOf(
            activity.resources.getIdentifier(entryName, "id", XIAOMI_PACKAGE),
            activity.resources.getIdentifier(entryName, "id", activity.packageName),
        ).filter { it != 0 }
        return id in expected || resourceEntryName(activity, id).equals(entryName, ignoreCase = true)
    }

    private fun currentApplicationContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()

    /**
     * 查询模块 App 是否处于前台（实时进程调度状态，使用隐藏 API
     * ActivityManager.getUidProcessState）。失败时按非前台处理，不阻断弹窗。
     */
    private fun isModuleUiForeground(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val uid = context.packageManager
            .getApplicationInfo(HyperRoseAction.PACKAGE_APP, 0)
            .uid
        val state = ActivityManager::class.java
            .getMethod("getUidProcessState", Int::class.javaPrimitiveType)
            .invoke(am, uid) as Int
        val topState = ActivityManager::class.java
            .getField("PROCESS_STATE_TOP")
            .getInt(null)
        state == topState
    }.getOrDefault(false)
}

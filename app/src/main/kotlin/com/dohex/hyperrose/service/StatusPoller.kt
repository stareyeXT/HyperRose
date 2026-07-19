package com.dohex.hyperrose.service

import android.os.Handler
import com.dohex.hyperrose.profile.DeviceProfile

/**
 * 统一的状态轮询调度器：执行初始全量状态查询，并按 [DeviceProfile.gattTiming] 周期查询电池。
 * 三处实现（DeviceSession / StandaloneGattClient / StandaloneRfcommClient）共用同一逻辑，
 * 避免重复代码及各自的 pollScheduled 防护漂移。
 *
 * 非线程安全 —— 仅在创建它的 Handler 线程上调用。
 */
class StatusPoller(
    private val profile: DeviceProfile,
    private val handler: Handler,
    private val send: (ByteArray, String) -> Unit,
) {
    @Volatile
    private var pollScheduled: Boolean = false
    private var pollRunnable: Runnable? = null

    /** 执行一次全量状态查询，并启动周期电池轮询（幂等，已调度则跳过）。 */
    fun queryAllStatus() {
        val stepDelay = profile.gattTiming?.statusQueryStepDelayMs ?: 100L
        profile.protocol.statusQuerySequence.forEachIndexed { index, query ->
            handler.postDelayed({ send(query, "Query status") }, stepDelay * index)
        }
        if (!pollScheduled) {
            pollScheduled = true
            val interval = profile.gattTiming?.statusRefreshIntervalMs ?: 30_000L
            val r = object : Runnable {
                override fun run() {
                    send(profile.protocol.queryBattery, "Query battery")
                    handler.postDelayed(this, interval)
                }
            }
            pollRunnable = r
            handler.postDelayed(r, interval)
        }
    }

    /** 取消所有挂起的查询与周期轮询，并重置内部状态，以便下次连接可重新启动。 */
    fun cancel() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        pollScheduled = false
        handler.removeCallbacksAndMessages(null)
    }
}
package io.github.mangi.eta.agent.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 设备事件的入口（运行在独立进程 `:schedule`，所以系统广播**不会**唤醒主进程）。
 *
 * 覆盖：充电插拔、电量变化、解锁屏幕、Wi-Fi 连接变化。收到后统一交给
 * [ScheduledTaskScheduler.dispatchEvent]，由它按任务条件筛出命中的任务。
 *
 * ⚠️ 电量广播在高频变化时可能密集到达，这里做了节流：只有跨越 5% 台阶才分发，
 * 避免一条"电量≥80%且充电中"的任务在充电过程中被反复触发。
 */
internal class DeviceEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> ScheduledEvent.PowerConnected
            Intent.ACTION_POWER_DISCONNECTED -> ScheduledEvent.PowerDisconnected
            Intent.ACTION_SCREEN_ON -> return // 只关心解锁，亮屏不算
            Intent.ACTION_USER_PRESENT -> ScheduledEvent.ScreenUnlocked
            Intent.ACTION_BATTERY_CHANGED -> batteryEvent(intent) ?: return
            "android.net.wifi.WIFI_STATE_CHANGED" -> wifiEvent(intent) ?: return
            else -> return
        }
        runCatching { ScheduledTaskScheduler.dispatchEvent(context, event) }
            .onFailure { failure ->
                AndroidAgentLogger.warn("Device event dispatch failed: type=${failure.javaClass.simpleName}")
            }
    }

    private fun batteryEvent(intent: Intent): ScheduledEvent.BatteryChanged? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val charging = plugged
        if (!crossedStep(percent)) return null
        return ScheduledEvent.BatteryChanged(percent, charging)
    }

    private fun wifiEvent(intent: Intent): ScheduledEvent.WifiChanged? {
        val state = intent.getIntExtra("wifi_state", -1)
        return when (state) {
            3 -> ScheduledEvent.WifiChanged(true)   // WIFI_STATE_ENABLED
            1 -> ScheduledEvent.WifiChanged(false)  // WIFI_STATE_DISABLED
            else -> null
        }
    }

    /** 电量只有跨过 5% 台阶才放行，避免充电过程中反复触发。 */
    private fun crossedStep(percent: Int): Boolean {
        val step = percent / 5
        if (step == lastBatteryStep) return false
        lastBatteryStep = step
        return true
    }

    companion object {
        /** 只在 :schedule 进程内有效，进程重启后从 -1 重新开始（首次一定放行）。 */
        @Volatile
        private var lastBatteryStep: Int = -1

        /** 需要在清单里静态注册的 action 列表，供 BootReceiver 复用与文档核对。 */
        val ACTIONS: List<String> = listOf(
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_CHANGED,
            Intent.ACTION_USER_PRESENT,
            "android.net.wifi.WIFI_STATE_CHANGED",
        )

        fun filter(): IntentFilter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
    }
}

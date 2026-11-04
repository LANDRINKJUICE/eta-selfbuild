package io.github.mangi.eta.agent.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 电量监听（动态注册）。
 *
 * **为什么必须是动态注册**：`Intent.ACTION_BATTERY_CHANGED` 是 sticky 广播，
 * Android 明确规定"不能用 manifest 声明的组件接收，只能 registerReceiver"。
 * 它原先写在 `AndroidManifest` 的 [DeviceEventReceiver] intent-filter 里，
 * 于是「电量低于 X%」这类任务成了死功能 —— 建了永远不跑，而且 `onReceive` 根本不进，
 * 日志里都看不到（2026-11-04 代码体检 #1）。
 *
 * 注册点选在主进程（Eta 日常常驻的那个），收到后转发给 `:schedule` 分发，
 * 不为一条广播去拉起一个新进程。
 *
 * **已知限制**：跨进程分发用的是 `startService`，Android 8+ 在 App 完全后台（没有前台服务、
 * 不可见）时可能被系统拒绝。那种情况下事件会被 [ScheduledTaskScheduler.dispatchEvent] 里的
 * runCatching 吞成一条 warn，电量类任务当次不触发 —— 但不影响其他触发方式，
 * 也不影响下一次电量跨台阶。
 */
internal object BatteryMonitor {
    /** 只有跨过 5% 台阶才分发，避免充电过程中把任务反复触发。 */
    private const val STEP_PERCENT = 5

    @Volatile
    private var registered = false

    /** 上一次分发的台阶；`Int.MIN_VALUE` 表示还没初始化过。 */
    @Volatile
    private var lastStep: Int = Int.MIN_VALUE

    fun start(context: Context) {
        if (registered) return
        val app = context.applicationContext
        // 先读一次当前电量当基线：否则注册瞬间的 sticky 回调会把"启动 App"当成一次电量变化。
        val initial = runCatching {
            (app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull() ?: -1
        lastStep = if (initial in 0..100) initial / STEP_PERCENT else Int.MIN_VALUE

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handle(context, intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 动态注册必须声明导出性；这里只收系统广播。
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        }.onSuccess {
            registered = true
        }.onFailure { failure ->
            AndroidAgentLogger.warn(
                "Battery monitor register failed: type=${failure.javaClass.simpleName}",
            )
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val step = percent / STEP_PERCENT
        if (step == lastStep) return
        lastStep = step
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        runCatching {
            ScheduledTaskScheduler.dispatchEvent(
                context,
                ScheduledEvent.BatteryChanged(percent, charging),
            )
        }.onFailure { failure ->
            AndroidAgentLogger.warn(
                "Battery event dispatch failed: type=${failure.javaClass.simpleName}",
            )
        }
    }
}

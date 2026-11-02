package io.github.mangi.eta.agent.schedule

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 定时任务的执行入口（运行在独立进程 `:schedule`）。
 *
 * 为什么用 Service 而不是 BroadcastReceiver：
 * - 事件型任务（通知、充电、电量、Wi-Fi、解锁）要在**独立进程**里处理，避免系统广播把主进程唤醒，
 *   用户正在用手机时不会因为一次定时任务而卡顿；主进程只有真正需要 Agent 能力时才被拉起。
 * - `onStartCommand` 天然有约 10 秒的启动窗口，足够完成"取任务 → 执行 → 续排下一次"，
 *   不需要 goAsync 那套广播技巧。
 *
 * 自身不持有任何 UI 依赖；执行与通知都在 [ScheduledActionRunner]。
 */
internal class ScheduledTaskReceiver : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(ScheduledTaskScheduler.EXTRA_TASK_ID)
        if (taskId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val appContext = applicationContext
        Thread {
            try {
                val task = ScheduledTaskStore.load(appContext).firstOrNull { it.id == taskId }
                if (task == null) {
                    AndroidAgentLogger.warn("Scheduled task missing, skip")
                } else if (task.enabled) {
                    ScheduledActionRunner.run(appContext, task)
                    ScheduledTaskStore.markRun(appContext, task.id, System.currentTimeMillis())
                }
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Scheduled task failed: type=${failure.javaClass.simpleName}")
            } finally {
                // 只有按时间触发的任务需要续排下一天；事件型任务不排。
                runCatching {
                    ScheduledTaskStore.load(appContext)
                        .firstOrNull { it.id == taskId }
                        ?.takeIf { it.enabled && it.trigger.usesAlarm }
                        ?.let { ScheduledTaskScheduler.schedule(appContext, it) }
                }
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }
}

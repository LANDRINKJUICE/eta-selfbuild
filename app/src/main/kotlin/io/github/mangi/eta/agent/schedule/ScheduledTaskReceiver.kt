package io.github.mangi.eta.agent.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 闹钟到点的入口。
 *
 * 只做三件事：取任务 → 执行动作 → 排下一天。放在独立线程里跑（`goAsync`），
 * 不阻塞广播主线程；任何异常都只记类型，保证接收器本身不会崩。
 */
internal class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledTaskScheduler.ACTION_RUN) return
        val taskId = intent.getStringExtra(ScheduledTaskScheduler.EXTRA_TASK_ID) ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
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
                // 无论成功失败都续排下一天：一次失败不应该让整条任务链断掉。
                runCatching {
                    ScheduledTaskStore.load(appContext)
                        .firstOrNull { it.id == taskId }
                        ?.takeIf { it.enabled }
                        ?.let { ScheduledTaskScheduler.schedule(appContext, it) }
                }
                pendingResult.finish()
            }
        }.start()
    }
}

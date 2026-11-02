package io.github.mangi.eta.agent.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.Calendar

/**
 * 用 AlarmManager 把 [ScheduledTask] 注册成每天一次的闹钟。
 *
 * 精确闹钟在 Android 12+ 需要用户在系统设置里授权；没有授权时退回 `setAndAllowWhileIdle`，
 * 触发时间可能被系统推迟几分钟到十几分钟，但**不会丢任务**，比直接不排更符合"定时干活"的预期。
 */
internal object ScheduledTaskScheduler {
    const val ACTION_RUN = "io.github.mangi.eta.action.RUN_SCHEDULED_TASK"
    const val EXTRA_TASK_ID = "task_id"

    /** 固定基址 + id 哈希，保证同一任务始终复用同一个 PendingIntent（重复注册即覆盖，不会堆积）。 */
    private const val REQUEST_CODE_BASE = 0x5C4E0000

    fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        val enabled = ScheduledTaskStore.load(appContext).filter { it.enabled }
        runCatching { cancelAll(appContext) }
        enabled.forEach { schedule(appContext, it) }
        AndroidAgentLogger.warn("Scheduled tasks rescheduled: count=${enabled.size}")
    }

    fun schedule(context: Context, task: ScheduledTask) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerMillis(task)
        val pending = pendingIntent(context, task.id)
        runCatching {
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }.onFailure { failure ->
            AndroidAgentLogger.warn("Scheduled task register failed: type=${failure.javaClass.simpleName}")
        }
    }

    fun cancel(context: Context, id: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { manager.cancel(pendingIntent(context, id)) }
    }

    /** 今天该时刻还没到就排在今天，已经过了就排明天。 */
    fun nextTriggerMillis(task: ScheduledTask, nowMillis: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMillis
        calendar.set(Calendar.HOUR_OF_DAY, task.hour)
        calendar.set(Calendar.MINUTE, task.minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= nowMillis) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    private fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        ScheduledTaskStore.load(context).forEach { task ->
            runCatching { manager.cancel(pendingIntent(context, task.id)) }
        }
    }

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true

    private fun pendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java)
            .setAction(ACTION_RUN)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

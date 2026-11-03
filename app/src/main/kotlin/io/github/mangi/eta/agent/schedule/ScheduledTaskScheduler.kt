package io.github.mangi.eta.agent.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.Calendar

/**
 * 把「每天定时」类型的任务注册成闹钟，并在条件满足时把**事件**转成一次执行。
 *
 * 设计要点：
 * 1. 只有 [ScheduledTrigger.DAILY_TIME] 需要闹钟；事件型任务靠系统广播唤醒，不占闹钟资源。
 * 2. 精确闹钟在 Android 12+ 需授权；没授权时退回 `setAndAllowWhileIdle`，触发可能推迟几分钟但不丢。
 * 3. 事件型任务在**独立进程**（@code :schedule）里处理，所以调度逻辑必须能脱离 UI 独立跑，
 *    这里不持有任何 Activity/Compose 依赖。
 */
internal object ScheduledTaskScheduler {
    const val ACTION_RUN = "io.github.mangi.eta.action.RUN_SCHEDULED_TASK"
    const val ACTION_EVENT = "io.github.mangi.eta.action.SCHEDULED_EVENT"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_EVENT = "event_json"

    /** 固定基址 + id 哈希，保证同一任务始终复用同一个 PendingIntent（重复注册即覆盖，不会堆积）。 */
    private const val REQUEST_CODE_BASE = 0x5C4E0000

    /**
     * 历代用过、现在已经不存在的任务 id。
     *
     * 为什么必须记着它们：`cancelAll` 只能按「当前任务列表」里的 id 取消，
     * 而 PendingIntent 的 requestCode = BASE + taskId.hashCode()。
     * 一旦任务 id 改名（例如 eta-device-1230 → eta-device-mon1230），
     * 旧 id 的闹钟就再也找不到人取消，会一直留在系统里每天多跑一次。
     *
     * 实测（2026-11-03）：把 9 个任务规整成 7 个并改了 4 个 id 之后，
     * 闹钟数变成 14（7 新 + 7 孤儿），而且孤儿还按旧的 daily 语义在跑。
     *
     * 维护规则：以后只要「删任务」或「改任务 id」，就把旧 id 加到这里，
     * 让下一次 rescheduleAll 顺手清掉。
     */
    private val LEGACY_TASK_IDS = listOf(
        "eta-device-1230",
        "eta-perm-0900",
        "eta-selfcheck-1400",
        "eta-backup-2200",
        "eta-clean-2330",
        "eta-memory-2300",
        "eta-weekcheck-2345",
    )

    fun rescheduleAll(context: Context) {
        val appContext = context.applicationContext
        // 首次启用（或清数据后）灌一套默认任务，否则新装的人是"一个任务都没有"的空白状态。
        // 已有任务时只打标记不覆盖，见 ScheduledDefaults。
        runCatching { ScheduledDefaults.seedIfNeeded(appContext) }
        runCatching { cancelAll(appContext) }
        val timeTasks = ScheduledTaskStore.load(appContext)
            .filter { it.enabled && it.trigger.usesAlarm }
        timeTasks.forEach { schedule(appContext, it) }
        AndroidAgentLogger.warn("Scheduled tasks rescheduled: count=${timeTasks.size}")
    }

    fun schedule(context: Context, task: ScheduledTask) {
        // 事件型任务不需要闹钟；以前排过的话要顺手取消，避免改类型后旧闹钟残留。
        if (!task.trigger.usesAlarm) {
            cancel(context, task.id)
            return
        }
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

    /**
     * 处理一个设备事件：找出所有命中的启用任务，逐个交给接收器执行。
     *
     * @return 命中的任务数（便于日志与测试核对）。
     */
    fun dispatchEvent(context: Context?, event: ScheduledEvent): Int {
        if (context == null) return 0
        val appContext = context.applicationContext
        val hits = ScheduledTaskStore.load(appContext).filter { it.enabled && it.matchesEvent(event) }
        if (hits.isEmpty()) return 0
        hits.forEach { task ->
            runCatching {
                appContext.startService(
                    Intent(appContext, ScheduledTaskReceiver::class.java)
                        .setAction(ACTION_EVENT)
                        .putExtra(EXTRA_TASK_ID, task.id)
                        .putExtra(EXTRA_EVENT, ScheduledEvent.encode(event)),
                )
            }.onFailure { failure ->
                AndroidAgentLogger.warn("Scheduled event dispatch failed: type=${failure.javaClass.simpleName}")
            }
            Unit
        }
        return hits.size
    }

    /** 今天该时刻还没到就排在今天，已经过了就排明天。 */
    /** 下一次触发时间：委托给 [ScheduledTaskSchedule]（纯逻辑，已有单测覆盖各类周期边界）。 */
    fun nextTriggerMillis(task: ScheduledTask, nowMillis: Long = System.currentTimeMillis()): Long =
        ScheduledTaskSchedule.nextTriggerMillis(task, nowMillis)

    private fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        idsToCancel(ScheduledTaskStore.load(context).map { it.id }).forEach { id ->
            runCatching { manager.cancel(pendingIntent(context, id)) }
        }
    }

    /**
     * 「取消这些 id 的闹钟」的完整集合 = 当前任务 + 历代遗留 id。
     *
     * 抽成纯函数是为了能单测 —— 上一版我把 LEGACY_TASK_IDS 定义好了却忘了接进 cancelAll，
     * 结果修复完全没生效（装机后闹钟仍是 14 个），而这种错误读代码时很容易看漏。
     */
    internal fun idsToCancel(currentIds: List<String>): List<String> =
        (currentIds + LEGACY_TASK_IDS).distinct()

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true

    private fun pendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java)
            .setAction(ACTION_RUN)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getService(
            context,
            REQUEST_CODE_BASE + taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

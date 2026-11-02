package io.github.mangi.eta.agent.schedule

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.ui.MainActivity
import java.util.Locale

/**
 * 定时任务的执行体：只读取系统状态，不做任何写操作、不调用模型。
 *
 * 结果通过通知展示（用户可见），日志里只写类型与计数，不写路径、设备标识等内容。
 */
internal object ScheduledActionRunner {
    private const val CHANNEL = "eta_scheduled_tasks"
    private const val NOTIFICATION_ID = 1188
    private const val HISTORY_MESSAGE_CHARS = 200

    fun run(context: Context, task: ScheduledTask) {
        val startedAt = System.currentTimeMillis()
        var ok = true
        val summary = runCatching { describe(context, task) }
            .getOrElse { failure ->
                ok = false
                "执行失败（${failure.javaClass.simpleName}）"
            }
        // 自定义指令返回的是"执行失败…"文本时，也要按失败记账，否则历史里会看起来一切正常。
        if (summary.startsWith("执行失败")) ok = false
        val duration = System.currentTimeMillis() - startedAt
        // 先落历史再发通知：通知可能被用户关掉，历史不会。
        runCatching {
            ScheduledRunLog.record(
                context,
                ScheduledRunLog.Record(
                    taskId = task.id,
                    atMillis = startedAt,
                    ok = ok,
                    message = summary.take(HISTORY_MESSAGE_CHARS),
                    durationMs = duration,
                ),
            )
        }
        notify(context, task, summary)
    }

    private fun describe(context: Context, task: ScheduledTask): String = when (task.action) {
        ScheduledActionKind.DEVICE_STATUS -> deviceStatus(context)
        ScheduledActionKind.MEMORY_STATUS -> memoryStatus(context)
        ScheduledActionKind.PERMISSIONS_CHECK -> permissionStatus(context)
        // 自我体检：跑工作区脚本、看闹钟注册、量 tmp 与负载。不需要模型，但会起 shell。
        ScheduledActionKind.SELF_CHECK -> ScheduledHealthCheck.report(context, pendingAlarmCount(context))
        // 下面四个都要跑工作区脚本，统一收在 ScheduledWorkspaceActions 里。
        ScheduledActionKind.SNAPSHOT -> ScheduledWorkspaceActions.snapshot()
        ScheduledActionKind.BRAIN_BACKUP -> ScheduledWorkspaceActions.brainBackup()
        ScheduledActionKind.CLEAN_TMP -> ScheduledWorkspaceActions.cleanTmp()
        ScheduledActionKind.DRIFT_CHECK -> ScheduledWorkspaceActions.driftCheck()
        // 自定义指令：交给 Agent 跑一次，结果摘要进通知。
        ScheduledActionKind.CUSTOM_PROMPT -> ScheduledAgentRunner.run(context, task)
    }

    /**
     * 期望已注册的闹钟数：启用的「每天定时」任务条数。
     *
     * 只统计 `trigger.usesAlarm` 的任务 —— 事件型任务（充电、通知、解锁…）本来就不注册闹钟，
     * 把它们算进去会让体检显示「时钟丢了」这种假异常。
     */
    private fun pendingAlarmCount(context: Context): Int = runCatching {
        ScheduledTaskStore.load(context).count { it.enabled && it.trigger.usesAlarm }
    }.getOrDefault(0)

    private fun deviceStatus(context: Context): String {
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        val memory = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val totalGb = stat.totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val battery = batterySnapshot(context)
        val uptimeHours = SystemClock.elapsedRealtime() / 3_600_000
        val availableMb = memory.availMem / (1024 * 1024)
        val totalMb = memory.totalMem / (1024 * 1024)
        return buildString {
            append("内存 ${availableMb}/${totalMb} MB")
            append(" · 存储 ")
            append(String.format(Locale.US, "%.1f/%.0f GB", freeGb, totalGb))
            battery?.let { (percent, tempTenths) ->
                append(" · 电量 $percent%")
                if (tempTenths > 0) append(String.format(Locale.US, "（%.1f℃）", tempTenths / 10.0))
            }
            append(" · 开机 $uptimeHours 小时")
        }
    }

    private fun memoryStatus(context: Context): String {
        // 本进程是 :schedule，完整运行时（含记忆仓库）只在主进程初始化，
        // 所以这里按需初始化一次；Repository 内部对重复 init 有幂等保护。
        AgentMemoryRepository.init(context)
        val snapshot = AgentMemoryRepository.snapshot()
        val memoryContext = AgentMemoryContextBuilder.build(snapshot = snapshot, contextWindow = null)
        val coreChars = memoryContext.coreContent.length
        val suffix = if (!memoryContext.enabled) {
            "记忆未初始化"
        } else if (coreChars == 0) {
            "缺少「# 核心记忆」段，每轮不会注入任何记忆"
        } else if (memoryContext.coreTruncated) {
            "核心记忆 $coreChars 字符（已按预算截断，轮次注入只取前 $coreChars）"
        } else {
            "核心记忆 $coreChars 字符（完整注入）"
        }
        val sizeKb = snapshot.byteSize / 1024.0
        return String.format(Locale.US, "记忆文件 %.1f KB · %s", sizeKb, suffix)
    }

    private fun permissionStatus(context: Context): String {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val notifications = notificationManager?.areNotificationsEnabled() == true
        val overlay = Settings.canDrawOverlays(context)
        val accessibility = hasEnabledAccessibility(context)
        val batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
        val exactAlarm = context.getSystemService(AlarmManager::class.java)
            ?.let { manager ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    manager.canScheduleExactAlarms()
                } else {
                    true
                }
            } == true
        return buildString {
            append("通知 ").append(if (notifications) "开" else "关")
            append(" · 悬浮窗 ").append(if (overlay) "开" else "关")
            append(" · 无障碍 ").append(if (accessibility) "开" else "关")
            append(" · 精确闹钟 ").append(if (exactAlarm) "可" else "降级")
            append(" · 电池优化 ").append(if (batteryUnrestricted) "已放行" else "未放行")
        }
    }

    private fun hasEnabledAccessibility(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service -> service.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    private fun batterySnapshot(context: Context): Pair<Int, Int>? {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temperature = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return percent.takeIf { it >= 0 }?.let { it to temperature }
    }

    private fun notify(context: Context, task: ScheduledTask, summary: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "定时任务", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (task.action) {
            ScheduledActionKind.DEVICE_STATUS -> "定时任务：设备状态"
            ScheduledActionKind.MEMORY_STATUS -> "定时任务：记忆状态"
            ScheduledActionKind.PERMISSIONS_CHECK -> "定时任务：权限体检"
            ScheduledActionKind.SELF_CHECK -> "定时任务：自我体检"
            ScheduledActionKind.SNAPSHOT -> "定时任务：系统快照"
            ScheduledActionKind.BRAIN_BACKUP -> "定时任务：大脑备份"
            ScheduledActionKind.CLEAN_TMP -> "定时任务：清理 tmp"
            ScheduledActionKind.DRIFT_CHECK -> "定时任务：漂移检测"
            ScheduledActionKind.CUSTOM_PROMPT -> "定时任务：自定义指令"
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setSubText(String.format(Locale.US, "%02d:%02d", task.hour, task.minute))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        if (manager.areNotificationsEnabled()) {
            manager.notify(task.id, NOTIFICATION_ID, notification)
        }
    }
}

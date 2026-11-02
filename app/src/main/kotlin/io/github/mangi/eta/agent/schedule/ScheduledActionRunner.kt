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

    fun run(context: Context, task: ScheduledTask) {
        val summary = runCatching { describe(context, task.action) }
            .getOrElse { failure -> "执行失败（${failure.javaClass.simpleName}）" }
        notify(context, task, summary)
    }

    private fun describe(context: Context, kind: ScheduledActionKind): String = when (kind) {
        ScheduledActionKind.DEVICE_STATUS -> deviceStatus(context)
        ScheduledActionKind.MEMORY_STATUS -> memoryStatus()
        ScheduledActionKind.PERMISSIONS_CHECK -> permissionStatus(context)
    }

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

    private fun memoryStatus(): String {
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

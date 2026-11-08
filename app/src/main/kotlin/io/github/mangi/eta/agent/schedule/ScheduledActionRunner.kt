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
import io.github.mangi.eta.agent.memory.MemoryHealthReport
import io.github.mangi.eta.agent.notify.EtaNotificationChannels
import io.github.mangi.eta.agent.notify.ScheduledNotificationBuilder
import io.github.mangi.eta.agent.plugin.PluginInvoker
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

    /**
     * 通知正文最多多少字。
     *
     * 通知不是阅读器：以前直接把模型答复前 400 字塞进去，一屏都放不下，
     * 而且 markdown 不渲染，`**App 是哪个**` 原样显示（2026-11-04 用户截图反馈）。
     * 完整答复仍在会话里，通知只留结论。
     */
    private const val NOTIFICATION_TEXT_CHARS = 120

    fun run(context: Context, task: ScheduledTask, event: ScheduledEvent? = null) {
        val startedAt = System.currentTimeMillis()
        // 成败由执行方直接给出（[ScheduledOutcome]），不再靠摘要文案猜。
        // 猜前缀踩过三次：超时、模型 401、脚本「未执行：找不到脚本」全被记成成功。
        val outcome = runCatching { describe(context, task, event) }
            .getOrElse { failure ->
                ScheduledOutcome(false, "执行失败（${failure.javaClass.simpleName}）")
            }
        val ok = outcome.ok
        val summary = outcome.summary
        val duration = System.currentTimeMillis() - startedAt
        // 先落历史再发通知：通知可能被用户关掉，历史不会。
        val runRecord = ScheduledRunLog.Record(
            taskId = task.id,
            atMillis = startedAt,
            ok = ok,
            message = summary.take(HISTORY_MESSAGE_CHARS),
            durationMs = duration,
        )
        runCatching { ScheduledRunLog.record(context, runRecord) }
        // 通知用压缩版：完整答复留在会话和历史里，通知只显示结论（见 notificationText）。
        notify(context, task, notificationText(summary), ok, runRecord)
    }

    /**
     * 把答复压成「通知里能看的一两句」。
     *
     * 做两件事：去掉 markdown 标记（通知不渲染，星号和反引号只会碍眼）、
     * 按句子边界截到 [NOTIFICATION_TEXT_CHARS]。
     */
    private fun notificationText(raw: String): String {
        val flattened = raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\*\\*|__|`"), "")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*>\\s?"), "")
            .replace(Regex("(?m)^\\s*[-*]\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (flattened.length <= NOTIFICATION_TEXT_CHARS) return flattened
        val cut = flattened.take(NOTIFICATION_TEXT_CHARS)
        val lastBreak = cut.indexOfLast { it in "。！？；" }
        return if (lastBreak >= NOTIFICATION_TEXT_CHARS / 3) {
            cut.take(lastBreak + 1)
        } else {
            cut.trimEnd() + "…"
        }
    }

    private fun describe(
        context: Context,
        task: ScheduledTask,
        event: ScheduledEvent? = null,
    ): ScheduledOutcome = when (task.action) {
        ScheduledActionKind.DEVICE_STATUS -> ScheduledOutcome.of(deviceStatus(context))
        ScheduledActionKind.MEMORY_STATUS -> ScheduledOutcome.of(memoryStatus(context))
        ScheduledActionKind.PERMISSIONS_CHECK -> ScheduledOutcome.of(permissionStatus(context))
        // 自我体检：跑工作区脚本、看闹钟注册、量 tmp 与负载。不需要模型，但会起 shell。
        ScheduledActionKind.SELF_CHECK ->
            ScheduledOutcome.of(ScheduledHealthCheck.report(context, pendingAlarmCount(context)))
        // 下面四个都要跑工作区脚本，统一收在 ScheduledWorkspaceActions 里。
        ScheduledActionKind.SNAPSHOT -> ScheduledOutcome.of(ScheduledWorkspaceActions.snapshot(context))
        ScheduledActionKind.BRAIN_BACKUP ->
            ScheduledOutcome.of(ScheduledWorkspaceActions.brainBackup(context))
        ScheduledActionKind.CLEAN_TMP -> ScheduledOutcome.of(ScheduledWorkspaceActions.cleanTmp())
        ScheduledActionKind.DRIFT_CHECK -> ScheduledOutcome.of(ScheduledWorkspaceActions.driftCheck())
        // 自定义指令：交给 Agent 跑一次，结果摘要进通知。
        // 事件要一起传：通知匹配类任务靠它把「哪条通知」带进 prompt，
        // 否则 Agent 只能自己去打开通知栏看，把用户正在用的界面顶掉。
        ScheduledActionKind.CUSTOM_PROMPT -> ScheduledAgentRunner.run(context, task, event)
        // 插件：无人值守调用。需要确认的插件会被跳过（并写明原因），不会静默执行。
        ScheduledActionKind.PLUGIN -> runPlugin(context, task)
    }

    /** 跑一次插件，把结果折成 [ScheduledOutcome]（失败前缀「跳过：」也是失败，别记成成功）。 */
    private fun runPlugin(context: Context, task: ScheduledTask): ScheduledOutcome =
        when (val outcome = PluginInvoker.invokeUnattended(context, task.pluginId, task.pluginArguments)) {
            is PluginInvoker.Outcome.Ok ->
                ScheduledOutcome(ok = true, summary = outcome.output.ifBlank { "插件执行完成" })

            is PluginInvoker.Outcome.Failure ->
                ScheduledOutcome(ok = false, summary = "跳过：" + outcome.message)
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

    private fun memoryStatus(context: Context): String =
        runCatching { MemoryHealthReport.build(context).fullText() }
            .getOrDefault("记忆体检失败")

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

    private fun notify(
        context: Context,
        task: ScheduledTask,
        summary: String,
        ok: Boolean,
        record: ScheduledRunLog.Record?,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // 渠道在 EtaApp 启动时统一建好；这里再 ensure 一次，保证 :schedule 进程也能独立工作。
        EtaNotificationChannels.ensure(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = ScheduledNotificationBuilder.build(
            context = context,
            task = task,
            summary = summary,
            ok = ok,
            record = record,
            // 原样传：自定义指令有会话，本地动作为空；两种都由通知的回复入口兜住
            // （本地动作的回复由主进程新建会话投递，见 NotificationReplyReceiver 的注释）。
            // 只有自定义指令这类带会话的任务才给回复入口；本地动作没有对话上下文，不给按钮。
            conversationId = task.conversationId.takeIf { it.isNotBlank() },
        )
        manager.notify(ScheduledNotificationBuilder.notificationId(task.id), notification)
    }
}

/** 记忆体检阈值与判定已统一到 [MemoryHealthReport]（UI 卡片与定时任务共用同一份结论）。 */

package io.github.mangi.eta.agent.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.agent.schedule.ScheduledRunLog
import io.github.mangi.eta.agent.schedule.ScheduledTask
import io.github.mangi.eta.agent.schedule.ScheduledTaskNaming
import java.util.Locale

/**
 * 定时任务结果通知的构建器：**跑完弹一下 → 自动收起 → 留在通知栏**，并且能直接回复。
 *
 * 三个设计决定：
 * 1. **只有带会话的任务才给「回复」按钮**。本地动作（设备状态/记忆状态/权限体检）没有会话，
 *    给了按钮也无处安放，反而让用户以为回一句能追问。
 * 2. **用 BigTextStyle 展开全文**，收起态只显示一行摘要 —— 这叫「好看」的第一步：信息密度分级。
 * 3. **结果分成功/失败两种视觉**（标题文案 + 小图标），不用点开就知道成没成。
 */
internal object ScheduledNotificationBuilder {
    /** 一条任务最多保留一个通知位：同一任务重复执行只更新同一条，不堆一屏。 */
    fun notificationId(taskId: String): Int = NOTIFICATION_ID_BASE + (taskId.hashCode() and 0xFF)

    fun build(
        context: Context,
        task: ScheduledTask,
        summary: String,
        ok: Boolean,
        record: ScheduledRunLog.Record?,
        conversationId: String?,
    ): Notification {
        // 事件型任务（收到通知／充电／电量／Wi-Fi／解锁）没有「几点」的概念：
        // task.hour/minute 只是默认值 0/0，以前直接当 subText 印在通知上，
        // 结果「收到含『签到』的通知就处理」这条任务的通知显示成「09:00」，
        // 用户以为是计划时间（2026-11-04 真机反馈）。这里按触发方式分开取。
        val time = if (task.trigger.usesAlarm) {
            String.format(Locale.US, "%02d:%02d", task.hour, task.minute)
        } else {
            task.triggerSummary().text
        }
        val title = if (ok) {
            "定时任务完成 · ${actionTitle(task)}"
        } else {
            "定时任务失败 · ${actionTitle(task)}"
        }
        val open = EtaNotificationChannels.openAppIntent(context, conversationId, requestCode = task.id.hashCode())

        val builder = Notification.Builder(context, EtaNotificationChannels.SCHEDULED)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(summary.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(record?.atMillis ?: System.currentTimeMillis())
            .setSubText(time)
            .setCategory(if (ok) Notification.CATEGORY_STATUS else Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

        // 只有带会话的任务给「回复」：本地动作（设备状态、体检等）没有对话上下文，
        // 给了按钮也回复不到任何地方，反而误导用户（用户明确要求这种不显示）。
        conversationId?.takeIf { it.isNotBlank() }?.let { id ->
            builder.addAction(
                NotificationReply.action(
                    context = context,
                    conversationId = id,
                    notificationId = notificationId(task.id),
                    requestCode = task.id.hashCode(),
                ),
            )
        }
        return builder.build()
    }

    /**
     * 通知栏标题里的那件事叫什么。
     *
     * 优先用任务自己的名字（用户填的 → 关键词 → 指令正文里能看出来的事），取不到才回退到动作名。
     * **自定义指令类不再显示「自定义指令」**——用户 2026-11-04 反馈这样「怪怪的」，
     * 而且所有自定义任务在通知栏里长得一模一样，根本分不清谁是谁。
     */
    private fun actionTitle(task: ScheduledTask): String {
        ScheduledTaskNaming.nameCandidate(task).takeIf { it.isNotEmpty() }?.let { return it }
        return when (task.action) {
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.DEVICE_STATUS -> "设备状态"
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.MEMORY_STATUS -> "记忆状态"
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.PERMISSIONS_CHECK -> "权限体检"
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.SELF_CHECK -> "自我体检"
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.SNAPSHOT -> "系统快照"
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.BRAIN_BACKUP -> "大脑备份"
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.CLEAN_TMP -> "清理临时文件"
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.DRIFT_CHECK -> "系统漂移检测"
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.CUSTOM_PROMPT -> "定时提醒"
            io.github.mangi.eta.agent.schedule.ScheduledActionKind.PLUGIN -> "插件任务"
        }
    }

    private const val NOTIFICATION_ID_BASE = 2100
}

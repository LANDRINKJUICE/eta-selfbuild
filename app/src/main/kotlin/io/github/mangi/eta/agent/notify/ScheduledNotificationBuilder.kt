package io.github.mangi.eta.agent.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.github.mangi.eta.agent.schedule.ScheduledRunLog
import io.github.mangi.eta.agent.schedule.ScheduledTask
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
    /** 回复文字回传的 action。 */
    const val ACTION_REPLY = "io.github.mangi.eta.action.SCHEDULED_REPLY"
    const val EXTRA_CONVERSATION_ID = "conversation_id"
    const val EXTRA_REPLY_TEXT = "reply_text"
    const val KEY_REPLY_INPUT = "scheduled_reply_input"

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
        val time = String.format(Locale.US, "%02d:%02d", task.hour, task.minute)
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

        // 只有自定义指令（有会话）才给回复入口；本地动作没会话，给了也没意义。
        if (!conversationId.isNullOrBlank()) {
            builder.addAction(replyAction(context, task.id, conversationId))
        }
        return builder.build()
    }

    /** 通知栏内联回复：打完字直接回到发起该任务的那个会话。 */
    private fun replyAction(context: Context, taskId: String, conversationId: String): Notification.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_INPUT)
            .setLabel("回复这条任务")
            .build()
        val intent = Intent(context, ScheduledReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra("task_id", taskId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return Notification.Action.Builder(null, "回复", pending)
            .addRemoteInput(remoteInput)
            .build()
    }

    /** 从回复结果里取出用户输入的文字。 */
    fun extractReplyText(results: Bundle?): String =
        results?.getCharSequence(KEY_REPLY_INPUT)?.toString()?.trim().orEmpty()

    private fun actionTitle(task: ScheduledTask): String = when (task.action) {
        io.github.mangi.eta.agent.schedule.ScheduledActionKind.DEVICE_STATUS -> "设备状态"
        io.github.mangi.eta.agent.schedule.ScheduledActionKind.MEMORY_STATUS -> "记忆状态"
        io.github.mangi.eta.agent.schedule.ScheduledActionKind.PERMISSIONS_CHECK -> "权限体检"
        io.github.mangi.eta.agent.schedule.ScheduledActionKind.SELF_CHECK -> "自我体检"
        io.github.mangi.eta.agent.schedule.ScheduledActionKind.SNAPSHOT -> "系统快照"
        io.github.mangi.eta.agent.schedule.ScheduledActionKind.BRAIN_BACKUP -> "大脑备份"
        io.github.mangi.eta.agent.schedule.ScheduledActionKind.CLEAN_TMP -> "清理临时文件"
        io.github.mangi.eta.agent.schedule.ScheduledActionKind.DRIFT_CHECK -> "系统漂移检测"
        io.github.mangi.eta.agent.schedule.ScheduledActionKind.CUSTOM_PROMPT -> "自定义指令"
    }

    private const val NOTIFICATION_ID_BASE = 2100
}

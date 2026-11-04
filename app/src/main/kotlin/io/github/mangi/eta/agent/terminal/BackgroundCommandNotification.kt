package io.github.mangi.eta.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

internal object BackgroundCommandNotification {
    private const val CHANNEL = "eta_background_commands"
    private const val NOTIFICATION_ID = 1124

    fun show(context: Context, task: DetachedTask) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台命令结果", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TERMINAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (task.commandStatus) {
            "completed" -> "后台命令已完成"
            "timed_out" -> "后台命令已超时"
            "stopped" -> "后台命令已停止"
            "interrupted" -> "后台命令已中断"
            else -> "后台命令执行失败"
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(task.description.ifBlank { "在终端的后台任务中查看状态和日志" })
            .setSubText(task.exitCode?.let { "退出码 $it" })
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(task.id, NOTIFICATION_ID, notification)
    }

    /**
     * 自动唤醒跑完后的结果回执：复用同一条通知（同一 task.id 与 NOTIFICATION_ID），
     * 把「失败了」升级成「已自动处理」，用户不必回会话才知道发生了什么。
     */
    fun showWakeUpResult(context: Context, task: DetachedTask, summary: String, ok: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台命令结果", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TERMINAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // 折叠态只留一句直白的话；完整输出放展开态，避免长文案被系统截断得看不出重点。
        val outcome = if (ok) "已自动处理" else "自动处理失败"
        val short = BackgroundCommandSummary.shortReason(summary)
        val long = summary.trim().ifEmpty { detailFallback(ok) }.take(400)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ok) "后台命令失败，已自动处理" else "后台命令失败，自动处理未完成")
            .setContentText(if (short.isEmpty()) "$outcome，详情见会话" else "$outcome：$short")
            .setStyle(Notification.BigTextStyle().bigText(long))
            .setSubText(
                buildString {
                    append(task.description.ifBlank { "后台命令" })
                    task.exitCode?.let { append(" · 退出码 ").append(it) }
                }
            )
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(task.id, NOTIFICATION_ID, notification)
    }

    /** 展开态没内容时的兜底文案。 */
    private fun detailFallback(ok: Boolean): String =
        if (ok) "已自动处理完成，详情见会话" else "自动处理未完成，详情见会话"
}

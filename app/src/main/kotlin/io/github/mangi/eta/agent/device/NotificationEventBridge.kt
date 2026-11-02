package io.github.mangi.eta.agent.device

import android.service.notification.StatusBarNotification
import io.github.mangi.eta.agent.schedule.ScheduledEvent
import io.github.mangi.eta.agent.schedule.ScheduledTaskScheduler
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 把"收到通知"接进定时任务系统。
 *
 * 独立成一个小对象，是为了让通知监听服务保持单一职责：服务本身只负责记录历史，
 * 转发逻辑（关键词匹配、分发到 :schedule 进程）放在这里，也方便单独测试。
 *
 * 只读取标题与正文用于关键词匹配，**不写日志内容**，避免泄漏隐私。
 */
internal object NotificationEventBridge {
    /** 通知正文上限：只取前若干字符参与匹配，避免超长文本拖慢匹配。 */
    private const val MAX_TEXT_CHARS = 200

    fun forward(context: android.content.Context, sbn: StatusBarNotification) {
        runCatching {
            val extras = sbn.notification?.extras ?: return
            // 自己发的通知不参与触发，否则通知触发的任务可能自我循环。
            if (sbn.packageName == MODULE_PACKAGE) return
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                .orEmpty()
                .take(MAX_TEXT_CHARS)
            ScheduledTaskScheduler.dispatchEvent(
                context = context,
                event = ScheduledEvent.NotificationPosted(sbn.packageName, title, text),
            )
        }.onFailure { failure ->
            AndroidAgentLogger.warn("Notification bridge failed: type=${failure.javaClass.simpleName}")
        }
    }

    private const val MODULE_PACKAGE = "io.github.mangi.eta"
}

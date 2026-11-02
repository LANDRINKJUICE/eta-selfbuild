package io.github.mangi.eta.agent.notify

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 处理通知栏「回复」：**立刻在后台把这句话送进对应会话并执行**，不跳转到 App。
 *
 * 流程：取文字 → 收掉通知 → `goAsync()` 撑住进程 → 后台线程跑
 * [NotificationReplyRunner]（它会先申请前台服务，所以长任务不会被回收）。
 *
 * 只有**带会话**的通知才会有回复按钮（见各通知构建器），
 * 所以这里 conversationId 为空直接忽略 —— 没有会话就没有"回复到哪"的语义。
 */
internal class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationReply.ACTION_REPLY) return
        val text = NotificationReply.extractText(RemoteInput.getResultsFromIntent(intent))
        val conversationId = intent.getStringExtra(NotificationReply.EXTRA_CONVERSATION_ID).orEmpty()
        if (text.isBlank() || conversationId.isBlank()) return

        val appContext = context.applicationContext
        // 回复完把这条通知收掉，避免用户以为还没处理。
        runCatching {
            val id = intent.getIntExtra(NotificationReply.EXTRA_NOTIFICATION_ID, 0)
            if (id != 0) {
                appContext.getSystemService(NotificationManager::class.java)?.cancel(id)
            }
        }

        // goAsync：接收器 onReceive 返回后进程还能活一会儿；真正的长任务由前台服务持有。
        val pending = goAsync()
        Thread({
            try {
                NotificationReplyRunner.run(appContext, conversationId, text)
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Notification reply dispatch failed: type=${failure.javaClass.simpleName}")
            } finally {
                runCatching { pending.finish() }
            }
        }, "eta-notification-reply").start()
    }
}

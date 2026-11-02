package io.github.mangi.eta.agent.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 通知栏「回复」的统一实现：**所有与会话相关的通知共用这一套**。
 *
 * 为什么抽出来（原来各写各的，出了两个问题）：
 * 1. 定时任务通知里写了 RemoteInput，但只对"带会话"的任务生效 —— 本机 9 个任务全是本地动作，
 *    于是用户从来没见过回复按钮。
 * 2. 回复文字写进了队列，但**全库没有任何代码读它** → 即使点得到按钮也永远送不出去。
 * 现在把「动作构造」和「队列」收敛到一处，通知构建器只负责调用，
 * 再也不会出现"某个通知漏了回复入口"或者"写了没人收"。
 *
 * 平台约束：带 RemoteInput 的 PendingIntent **必须**是 `FLAG_MUTABLE`，
 * 否则 Android 12+ 会把输入结果丢掉（通知显示正常，但回复永远收不到）。
 */
internal object NotificationReply {
    const val ACTION_REPLY = "io.github.mangi.eta.action.NOTIFICATION_REPLY"
    const val EXTRA_CONVERSATION_ID = "conversation_id"
    const val EXTRA_NOTIFICATION_ID = "notification_id"
    const val KEY_REPLY_INPUT = "notification_reply_input"

    /**
     * 造一个带输入框的「回复」动作。
     *
     * @param conversationId 回复要落到哪个会话；**允许为空** —— 例如定时任务里的本地动作
     *   （设备状态、权限体检）本来就没有会话，这种回复由消费端新建一个会话来安放。
     * @param notificationId 回复后要收掉的通知 id
     */
    fun action(
        context: Context,
        conversationId: String,
        notificationId: Int,
        requestCode: Int,
    ): Notification.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_INPUT)
            .setLabel("回复")
            .build()
        val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return Notification.Action.Builder(null, "回复", pending)
            .addRemoteInput(remoteInput)
            .build()
    }

    /** 从回复结果里取出用户输入的文字。 */
    fun extractText(results: Bundle?): String =
        results?.getCharSequence(KEY_REPLY_INPUT)?.toString()?.trim().orEmpty()
}

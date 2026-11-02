package io.github.mangi.eta.agent.notify

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 处理通知栏「回复」：把用户打的字投递到对应会话。
 *
 * 为什么落盘排队、而不是当场把 Agent 拉起来：
 * 回复发生时用户很可能正在别的 App 里，直接拉起运行会抢走前台、打断他手上的事。
 * 队列由主界面消费（见 AgentAppRoot），那时会话上下文已就绪，投递最可靠。
 *
 * `conversationId` 允许为空（例如定时任务里的本地动作没有会话），
 * 这种回复由消费端新建一个会话来安放。
 */
internal class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationReply.ACTION_REPLY) return
        val text = NotificationReply.extractText(RemoteInput.getResultsFromIntent(intent))
        if (text.isBlank()) return
        val conversationId = intent.getStringExtra(NotificationReply.EXTRA_CONVERSATION_ID).orEmpty()
        val appContext = context.applicationContext
        runCatching {
            PendingReplyQueue.enqueue(appContext, conversationId, text)
        }.onFailure { failure ->
            AndroidAgentLogger.warn("Notification reply enqueue failed: type=${failure.javaClass.simpleName}")
        }
        // 回复完把这条通知收掉，避免用户以为还没处理。
        runCatching {
            val id = intent.getIntExtra(NotificationReply.EXTRA_NOTIFICATION_ID, 0)
            if (id != 0) {
                appContext.getSystemService(NotificationManager::class.java)?.cancel(id)
            }
        }
    }
}

/**
 * 待投递的回复队列（落盘，跨进程安全）。
 *
 * `conversationId` 可能为空字符串，语义是"没有既定会话，消费端新建一个"。
 */
internal object PendingReplyQueue {
    private const val PREFS = "eta_pending_replies"
    private const val KEY = "entries"
    private const val MAX = 20

    internal data class Entry(val conversationId: String, val text: String, val at: Long)

    fun enqueue(context: Context, conversationId: String, text: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = load(context).toMutableList()
        existing.add(Entry(conversationId, text, System.currentTimeMillis()))
        val array = org.json.JSONArray()
        existing.takeLast(MAX).forEach {
            array.put(
                org.json.JSONObject()
                    .put("conversation_id", it.conversationId)
                    .put("text", it.text)
                    .put("at", it.at),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun load(context: Context): List<Entry> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                // conversationId 允许为空：表示"没有既定会话"，由消费端新建
                val id = item.optString("conversation_id")
                val text = item.optString("text")
                if (text.isBlank()) null else Entry(id, text, item.optLong("at", 0L))
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}

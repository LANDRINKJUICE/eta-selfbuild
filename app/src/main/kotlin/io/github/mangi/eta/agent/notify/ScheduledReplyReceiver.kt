package io.github.mangi.eta.agent.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.agent.schedule.ScheduledTaskStore
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 处理通知栏内联回复：把用户打的字原样送进**发起该任务的那个会话**。
 *
 * 为什么要单独一个接收器而不是直接跳 Activity：
 * 通知栏回复的体验核心是「打完就走，不离开当前界面」。跳 Activity 会打断用户正在做的事，
 * 所以这里只把消息投递到一个待发送队列，用户下次回到会话时它已经在那儿了（或由运行时直接续跑）。
 */
internal class ScheduledReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledNotificationBuilder.ACTION_REPLY) return
        val conversationId = intent.getStringExtra(ScheduledNotificationBuilder.EXTRA_CONVERSATION_ID).orEmpty()
        val text = ScheduledNotificationBuilder.extractReplyText(
            android.app.RemoteInput.getResultsFromIntent(intent),
        )
        if (conversationId.isBlank() || text.isBlank()) return
        val appContext = context.applicationContext
        runCatching {
            PendingReplyQueue.enqueue(appContext, conversationId, text)
        }.onFailure { failure ->
            AndroidAgentLogger.warn("Scheduled reply enqueue failed: type=${failure.javaClass.simpleName}")
        }
        // 回复完把这条通知收掉，避免用户以为还没处理。
        runCatching {
            val taskId = intent.getStringExtra("task_id").orEmpty()
            if (taskId.isNotBlank()) {
                val manager = appContext.getSystemService(android.app.NotificationManager::class.java)
                manager?.cancel(ScheduledNotificationBuilder.notificationId(taskId))
            }
        }
    }
}

/**
 * 待发送的回复队列（跨进程：:schedule 写入不了主进程内存，所以落盘）。
 *
 * 为什么会话不直接开跑：通知栏回复发生在用户可能正在别的 App 里，
 * 直接拉起 Agent 会突然抢走前台。所以先排队，主界面下次可见时再发出去。
 */
internal object PendingReplyQueue {
    private const val PREFS = "eta_pending_replies"
    private const val KEY = "entries"

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
                val id = item.optString("conversation_id")
                val text = item.optString("text")
                if (id.isBlank() || text.isBlank()) null
                else Entry(id, text, item.optLong("at", 0L))
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    private const val MAX = 20
}

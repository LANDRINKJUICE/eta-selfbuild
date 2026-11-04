package io.github.mangi.eta.agent.tool

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 危险操作拦截的**确认闸门**与审计记录。
 *
 * 工作方式（不能阻塞主线程，也不能让 Binder 等太久）：
 * 1. 工具执行线程调用 [requestApproval]，拿到一个 pendingId 并**阻塞等待**。
 * 2. UI 轮询 [pending] 拿到待确认项，弹出确认卡片；用户点「继续」→ [approve]，「取消」→ [deny]。
 * 3. 工具线程被唤醒，按结果放行或拒绝。
 * 4. 若在 [APPROVAL_TIMEOUT_MS] 内无人确认，按**拒绝**处理（安全默认值）。
 *
 * 记录：[log] 保存最近若干条拦截记录，供设置页显示「今日已拦截 N 次」。
 */
internal object DangerousOperationGuard {
    /** 等待用户确认的最长时间；超时按拒绝处理。 */
    private const val APPROVAL_TIMEOUT_MS = 120_000L
    private const val POLL_INTERVAL_MS = 150L
    private const val LOG_PREFS = "eta_dangerous_ops"
    private const val KEY_LOG = "log"
    private const val MAX_LOG = 50

    /** 一条待确认请求。 */
    internal data class Pending(
        val id: Long,
        val toolName: String,
        val reason: String,
        val createdAt: Long,
    )

    private val lock = Object()
    @Volatile
    private var current: Pending? = null
    @Volatile
    private var decision: Boolean? = null
    private var sequence = 0L

    /**
     * 待确认请求的响应式视图。
     *
     * 界面以前用 200ms 轮询读 [pending]，等于主线程每秒唤醒 5 次只为拿一个静态值
     * （2026-11-04 代码体检 #12）。改成 Flow 后响应速度不变、开销归零。
     */
    private val pendingState = MutableStateFlow<Pending?>(null)
    val pendingFlow: StateFlow<Pending?> get() = pendingState

    /** 工具线程调用：阻塞直到用户决定或超时。返回 true 表示允许执行。 */
    fun requestApproval(toolName: String, reason: String): Boolean {
        val id: Long
        synchronized(lock) {
            id = ++sequence
            val request = Pending(id, toolName, reason, System.currentTimeMillis())
            current = request
            decision = null
            pendingState.value = request
        }
        val deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                synchronized(lock) {
                    decision?.let { return it }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            // 超时：按拒绝处理，并把原因写清楚，避免模型以为是工具本身坏了。
            return false
        } finally {
            synchronized(lock) {
                current = null
                decision = null
                pendingState.value = null
            }
        }
    }

    /** UI 调用：当前是否有待确认项。 */
    fun pending(): Pending? = synchronized(lock) { current }

    /** UI 调用：用户点了「继续」。 */
    fun approve(id: Long) {
        synchronized(lock) {
            if (current?.id == id) decision = true
        }
    }

    /** UI 调用：用户点了「取消」。 */
    fun deny(id: Long) {
        synchronized(lock) {
            if (current?.id == id) decision = false
        }
    }

    /** 记一条拦截审计。 */
    fun log(context: Context, toolName: String, reason: String, approved: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY_LOG, "[]")) }.getOrDefault(JSONArray())
        val entries = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.toMutableList()
        entries.add(
            0,
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("tool", toolName)
                .put("reason", reason)
                .put("approved", approved),
        )
        val trimmed = JSONArray()
        entries.take(MAX_LOG).forEach { trimmed.put(it) }
        prefs.edit().putString(KEY_LOG, trimmed.toString()).apply()
    }

    /** 今天被拦截的次数（用于「今日已拦截 N 次」）。 */
    fun todayCount(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY_LOG, "[]")) }.getOrDefault(JSONArray())
        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (0 until array.length()).count { index ->
            (array.optJSONObject(index)?.optLong("at", 0L) ?: 0L) >= startOfDay
        }
    }

    /** 最近若干条审计记录（设置页展示用）。 */
    fun recent(context: Context, limit: Int = 20): List<Pair<String, String>> {
        val prefs: SharedPreferences =
            context.applicationContext.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY_LOG, "[]")) }.getOrDefault(JSONArray())
        return (0 until minOf(array.length(), limit)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(item.optLong("at", 0L)))
            val verdict = if (item.optBoolean("approved", false)) "已允许" else "已拒绝"
            time to "$verdict · ${item.optString("tool")} · ${item.optString("reason")}"
        }
    }
}

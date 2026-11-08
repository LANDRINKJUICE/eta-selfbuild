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

    /** 用户在确认卡片上的三种选择。 */
    internal enum class Decision {
        /** 就这一次。 */
        ALLOW_ONCE,

        /** 本会话内同规则不再问（只在内存，进程重启即失效，不落盘）。 */
        ALLOW_SESSION,

        /** 拒绝。 */
        DENY,
    }

    /** 一条待确认请求。 */
    internal data class Pending(
        val id: Long,
        val toolName: String,
        val reason: String,
        val createdAt: Long,
        /** 命中的规则名；ask_user 这类非危险操作给空串，也用它做「这类别问了」的记忆键。 */
        val rule: String = "",
        /** 人话解释：这个操作会动到什么。 */
        val impact: String = "",
        /** 出事怎么补救；空串表示不好撤。 */
        val recovery: String = "",
        /** 是否可能变砖——决定标题措辞与按钮是否标红。 */
        val critical: Boolean = false,
        /** 待执行的具体内容（命令原文/写入路径），让用户看清楚再决定。 */
        val detail: String = "",
        /** 是否提供「这类别问了」——只有真正的危险操作才给，ask_user 不给。 */
        val allowSessionOption: Boolean = false,
    )

    private val lock = Object()
    @Volatile
    private var current: Pending? = null
    @Volatile
    private var decision: Decision? = null
    private var sequence = 0L

    /**
     * 本会话内已被「这类别问了」放行的规则名。
     *
     * 刻意**只存内存、不落盘**：重启 Eta 就回到逐一询问，
     * 免得用户过几天忘了自己曾经永久放行过什么。
     */
    private val rememberedRules = mutableSetOf<String>()

    /**
     * 某个规则是否已被「这类别问了」记住。
     *
     * 只读暴露给单测与插件执行器：插件那边要把「规则名拼得对不对」测出来 ——
     * 拼错的话记忆永远命不中，用户会一直被打扰而看不出哪里错了（2026-11-08）。
     */
    internal fun isRuleRemembered(rule: String): Boolean =
        rule.isNotEmpty() && synchronized(lock) { rule in rememberedRules }

    /**
     * 待确认请求的响应式视图。
     *
     * 界面以前用 200ms 轮询读 [pending]，等于主线程每秒唤醒 5 次只为拿一个静态值
     * （2026-11-04 代码体检 #12）。改成 Flow 后响应速度不变、开销归零。
     */
    private val pendingState = MutableStateFlow<Pending?>(null)
    val pendingFlow: StateFlow<Pending?> get() = pendingState

    /**
     * 工具线程调用：阻塞直到用户决定或超时。
     *
     * @param rule 命中的规则名。非空、且本次会话已被「这类别问了」放行过时，
     *   直接返回 [Decision.ALLOW_ONCE] 并跳过弹卡片；空串表示不参与记忆（ask_user 走这条）。
     */
    fun requestApproval(
        toolName: String,
        reason: String,
        rule: String = "",
        impact: String = "",
        recovery: String = "",
        critical: Boolean = false,
        detail: String = "",
        allowSessionOption: Boolean = false,
    ): Decision {
        // 本会话已放行过同类 → 不再打扰用户。
        if (rule.isNotEmpty()) {
            synchronized(lock) {
                if (rule in rememberedRules) return Decision.ALLOW_ONCE
            }
        }

        val id: Long
        synchronized(lock) {
            id = ++sequence
            val request = Pending(
                id = id,
                toolName = toolName,
                reason = reason,
                createdAt = System.currentTimeMillis(),
                rule = rule,
                impact = impact,
                recovery = recovery,
                critical = critical,
                detail = detail,
                // 没有规则名就没法记忆，所以「这类别问了」必须依赖 rule。
                allowSessionOption = allowSessionOption && rule.isNotEmpty(),
            )
            current = request
            decision = null
            pendingState.value = request
        }
        val deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                synchronized(lock) {
                    decision?.let { result ->
                        if (result == Decision.ALLOW_SESSION && rule.isNotEmpty()) {
                            rememberedRules.add(rule)
                        }
                        return result
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            // 超时：按拒绝处理，避免模型以为是工具本身坏了。
            return Decision.DENY
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

    /** UI 调用：用户选了某个结果。id 不匹配（界面过期）时静默忽略。 */
    fun decide(id: Long, value: Decision) {
        synchronized(lock) {
            if (current?.id == id) decision = value
        }
    }

    /** UI 调用：就这一次。 */
    fun approve(id: Long) = decide(id, Decision.ALLOW_ONCE)

    /** UI 调用：这类别问了（仅本会话有效）。 */
    fun approveSession(id: Long) = decide(id, Decision.ALLOW_SESSION)

    /** UI 调用：先别动。 */
    fun deny(id: Long) = decide(id, Decision.DENY)

    /** 记一条拦截审计。 */
    fun log(context: Context, toolName: String, reason: String, decision: Decision) {
        val prefs = context.applicationContext.getSharedPreferences(LOG_PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY_LOG, "[]")) }.getOrDefault(JSONArray())
        val entries = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.toMutableList()
        entries.add(
            0,
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("tool", toolName)
                .put("reason", reason)
                // 旧字段保留，历史记录仍能读；新记录补一个更细的 decision。
                .put("approved", decision != Decision.DENY)
                .put("decision", decision.name),
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
            val verdict = when {
                item.optString("decision") == Decision.ALLOW_SESSION.name -> "本会话已放行"
                item.optBoolean("approved", false) -> "已允许"
                else -> "已拒绝"
            }
            time to "$verdict · ${item.optString("tool")} · ${item.optString("reason")}"
        }
    }
}

package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.Locale

/**
 * 额度 / 用量快照。
 *
 * 数据全部来自本地 Room（conversation_messages 的 token 列），不联网、不调用模型。
 *
 * 口径限制（已实测核对实体定义）：conversation_messages 表没有 created_at 列，
 * 因此拿不到「这条回答是哪天产生的」，按天聚合需要新增列 + 数据库迁移，
 * 超出本次改动范围。当前实现退化为两个可验证的数字：
 * 全部历史累计，以及「最近 N 条回答」的窗口用量。
 */
@Immutable
internal data class TokenUsageSnapshot(
    val totalInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val totalCalls: Int = 0,
    val recentInputTokens: Long = 0L,
    val recentOutputTokens: Long = 0L,
    val recentCachedTokens: Long = 0L,
    val recentCalls: Int = 0,
) {
    val hasAnyUsage: Boolean get() = totalCalls > 0
}

/** 定时任务页底部卡片的窗口大小：最近 N 条有 token 记录的回答。 */
internal const val RecentUsageMessageWindow = 30

/**
 * 读用量快照。只有两条本地查询，放在 IO 线程调用。
 *
 * 「最近窗口」用 id 倒序（= 写入顺序）近似时间顺序，没有时间列也不会误报成别的时间段。
 */
internal suspend fun loadTokenUsageSnapshot(database: EtaDatabase): TokenUsageSnapshot {
    val dao = database.conversationDao()
    val total = dao.tokenUsageTotal()
    val recent = dao.tokenUsageRecent(RecentUsageMessageWindow)
    return TokenUsageSnapshot(
        totalInputTokens = total.inputTokens ?: 0L,
        totalOutputTokens = total.outputTokens ?: 0L,
        totalCachedTokens = total.cachedTokens ?: 0L,
        totalCalls = total.calls,
        recentInputTokens = recent.inputTokens ?: 0L,
        recentOutputTokens = recent.outputTokens ?: 0L,
        recentCachedTokens = recent.cachedTokens ?: 0L,
        recentCalls = recent.calls,
    )
}

/**
 * token 计数的紧凑格式化。
 *
 * 直接复用上下文按钮那一套 [formatCompactTokenCount]（ui/model/AgentModelPickerUiState.kt:169-181），
 * 保证「12.3K」的写法与输入框上方完全一致；这里只把聚合用的 Long 收口成 Int 再交给它。
 */
internal fun formatUsageTokenCount(value: Long, locale: Locale): String =
    formatCompactTokenCount(
        value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
        locale,
    )

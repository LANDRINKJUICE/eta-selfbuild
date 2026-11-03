package io.github.mangi.eta.agent.memory

import android.content.Context
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 记忆体检：只报「事实 + 该核对什么」，不判断内容对错。
 *
 * 判定与注入时的 stale 提示保持一致：状态类超过 [STALE_STATE_DAYS] 天、知识类超过
 * [STALE_KNOWLEDGE_DAYS] 天需要重新核对；日期写成未来算明显笔误（2026-11-04 真出现过一条 11-07）。
 *
 * 抽出来是为了让「设置 → 记忆」的卡片和定时任务给出同一份结论 —— 之前这份逻辑只存在于
 * 定时任务的执行体里，UI 上根本没有入口，用户看不到。
 */
internal object MemoryHealthReport {
    const val STALE_STATE_DAYS = 7L
    const val STALE_KNOWLEDGE_DAYS = 30L

    data class Report(
        val enabled: Boolean,
        val sizeKb: Double,
        val coreChars: Int,
        val coreTruncated: Boolean,
        val missingCore: Boolean,
        val staleTitles: List<String>,
    ) {
        val staleCount: Int get() = staleTitles.size

        /** 一句话结论，卡片标题用。 */
        fun summary(): String {
            val suffix = when {
                !enabled -> "记忆未初始化"
                missingCore -> "缺少「# 核心记忆」段，每轮不会注入任何记忆"
                coreTruncated -> "核心记忆 $coreChars 字符（已按预算截断）"
                else -> "核心记忆 $coreChars 字符（完整注入）"
            }
            return String.format(Locale.US, "记忆文件 %.1f KB · %s", sizeKb, suffix)
        }

        /** 待核对条目的明细，卡片副标题用；没有待核对时返回空串。 */
        fun detail(): String =
            if (staleTitles.isEmpty()) "" else "待核对 ${staleTitles.size} 条：" + staleTitles.take(3).joinToString("、")

        /** 定时任务通知里用的整句（与卡片同样的内容拼在一起）。 */
        fun fullText(): String = summary() + if (staleTitles.isEmpty()) "" else " · " + detail()
    }

    fun build(context: Context): Report {
        // 仓库可能在 :schedule 进程里未初始化；init 内部幂等，主进程调用无副作用。
        AgentMemoryRepository.init(context)
        val snapshot = AgentMemoryRepository.snapshot()
        val memoryContext = AgentMemoryContextBuilder.build(snapshot = snapshot, contextWindow = null)
        return Report(
            enabled = memoryContext.enabled,
            sizeKb = snapshot.byteSize / 1024.0,
            coreChars = memoryContext.coreContent.length,
            coreTruncated = memoryContext.coreTruncated,
            missingCore = memoryContext.enabled && memoryContext.coreContent.isEmpty(),
            staleTitles = staleEntries(snapshot.content),
        )
    }

    /**
     * 找出需要重新核对的条目。只做**事实标记**，内容对不对要靠人判断。
     * `today` 可注入，便于测试不受运行日期影响。
     */
    internal fun staleEntries(content: String, today: LocalDate = LocalDate.now()): List<String> {
        val pattern = Regex("^##\\s*\\[(状态|知识)\\]\\s*(\\d{4}-\\d{2}-\\d{2})")
        return content.lineSequence().mapNotNull { line ->
            val match = pattern.find(line) ?: return@mapNotNull null
            val kind = match.groupValues[1]
            val raw = match.groupValues[2]
            val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(date, today)
            when {
                days < 0 -> "$kind $raw（日期在未来，疑似笔误）"
                days > if (kind == "状态") STALE_STATE_DAYS else STALE_KNOWLEDGE_DAYS ->
                    "$kind $raw（$days 天）"
                else -> null
            }
        }.toList()
    }
}

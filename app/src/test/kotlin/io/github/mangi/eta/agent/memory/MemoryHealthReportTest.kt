package io.github.mangi.eta.agent.memory

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHealthReportTest {

    private val today = LocalDate.of(2026, 11, 4)

    @Test
    fun stateEntriesGoStaleAfterSevenDays() {
        val content = """
            ## [状态] 2026-10-27 刚好 8 天
            ## [状态] 2026-10-29 6 天，还没到期
        """.trimIndent()
        val stale = MemoryHealthReport.staleEntries(content, today)
        assertEquals(1, stale.size)
        assertTrue(stale.single().contains("2026-10-27"))
        assertTrue(stale.single().contains("8 天"))
    }

    @Test
    fun knowledgeEntriesGoStaleAfterThirtyDays() {
        val content = """
            ## [知识] 2026-10-04 31 天
            ## [知识] 2026-10-06 29 天
        """.trimIndent()
        val stale = MemoryHealthReport.staleEntries(content, today)
        assertEquals(1, stale.size)
        assertTrue(stale.single().contains("2026-10-04"))
    }

    @Test
    fun futureDatesAreFlaggedAsTypos() {
        // 真机上出现过把日期写成 11-07 的情况，这类笔误必须能看出来。
        val stale = MemoryHealthReport.staleEntries("## [状态] 2026-12-31 未来的日期", today)
        assertEquals(1, stale.size)
        assertTrue(stale.single().contains("日期在未来"))
    }

    @Test
    fun ignoresOtherHeadingsAndMalformedDates() {
        val content = """
            ## [铁律] 2026-01-01 不是状态也不是知识
            ## [状态] 不是日期
            ## [状态] 2026-13-40 非法日期
            ### 普通标题 2026-01-01
        """.trimIndent()
        assertEquals(emptyList<String>(), MemoryHealthReport.staleEntries(content, today))
    }

    @Test
    fun reportSummaryCoversTheFourStates() {
        val full = MemoryHealthReport.Report(
            enabled = true, sizeKb = 41.3, coreChars = 8500,
            coreTruncated = false, missingCore = false, staleTitles = emptyList(),
        )
        assertTrue(full.summary().contains("完整注入"))
        assertTrue(full.summary().contains("41.3 KB"))
        // 卡片只报体积：核心段是否截断由页面上另一行给出，避免两行说法打架。
        assertTrue(full.sizeText().contains("41.3 KB"))
        assertTrue(!full.sizeText().contains("截断"))
        assertEquals("", full.detail())

        val missing = full.copy(coreChars = 0, missingCore = true)
        assertTrue(missing.summary().contains("缺少"))

        val truncated = full.copy(coreTruncated = true)
        assertTrue(truncated.summary().contains("截断"))

        val disabled = full.copy(enabled = false)
        assertTrue(disabled.summary().contains("未初始化"))
    }

    @Test
    fun detailListsAtMostThreeStaleEntries() {
        val report = MemoryHealthReport.Report(
            enabled = true, sizeKb = 1.0, coreChars = 10,
            coreTruncated = false, missingCore = false,
            staleTitles = listOf("状态 a", "状态 b", "知识 c", "知识 d"),
        )
        val detail = report.detail()
        assertTrue(detail.contains("待核对 4 条"))
        assertTrue(detail.contains("状态 a"))
        assertTrue(detail.contains("知识 c"))
        // 只列前三条，第四条不展开。
        assertTrue(!detail.contains("知识 d"))
    }
}

package io.github.mangi.eta.agent.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「连续失败次数」的回归测试。
 *
 * 这个数字原本只有实现没有调用方（原作者写了计数，UI 没接上）。
 * 2026-11-04 补到任务列表副标题上 —— 定时任务静默失败时，它是最早的信号。
 */
class ScheduledRunLogTest {

    private fun ok(taskId: String) =
        ScheduledRunLog.Record(taskId, atMillis = 1L, ok = true, message = "完成", durationMs = 10L)

    private fun fail(taskId: String, message: String = "执行失败") =
        ScheduledRunLog.Record(taskId, atMillis = 1L, ok = false, message = message, durationMs = 10L)

    @Test
    fun `连续失败从最新往前数到成功为止`() {
        val records = listOf(fail("a"), fail("a"), ok("a"), fail("a"))

        assertEquals(2, ScheduledRunLog.consecutiveFailuresOf(records, "a"))
    }

    @Test
    fun `最新一次成功就是零`() {
        val records = listOf(ok("a"), fail("a"), fail("a"))

        assertEquals(0, ScheduledRunLog.consecutiveFailuresOf(records, "a"))
    }

    @Test
    fun `只统计指定任务别的任务既不打断也不计数`() {
        val records = listOf(fail("a"), ok("b"), fail("a"))

        assertEquals(2, ScheduledRunLog.consecutiveFailuresOf(records, "a"))
    }

    @Test
    fun `没有记录时为零`() {
        assertEquals(0, ScheduledRunLog.consecutiveFailuresOf(emptyList(), "a"))
    }
}

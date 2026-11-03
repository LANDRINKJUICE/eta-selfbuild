package io.github.mangi.eta.agent.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复执行拦截。
 *
 * 核心诉求：挡住「同一任务短时间内被触发两次」，但不能挡住正常日程。
 * 实测背景：11-02 23:49~23:50 一分钟内 9 个任务全被补发，selfcheck/snapshot 还各跑了两次，
 * 导致工作区出现两份同内容的 SYSTEM-SNAPSHOT 文件。
 */
class ScheduledRunGateTest {
    private val min = ScheduledRunGate.MIN_INTERVAL_MILLIS

    @Test
    fun neverRanBeforePasses() {
        assertTrue(ScheduledRunGate.shouldRun(null, 1_000_000L))
    }

    /** 0 或负数时间戳视为「没跑过」，避免损坏数据把任务永久卡死。 */
    @Test
    fun zeroOrNegativeTimestampPasses() {
        assertTrue(ScheduledRunGate.shouldRun(0L, 1_000_000L))
        assertTrue(ScheduledRunGate.shouldRun(-5L, 1_000_000L))
    }

    /** 刚跑完又来一次 —— 就是要挡的重复触发。 */
    @Test
    fun justRanIsBlocked() {
        val now = 10_000_000L
        assertFalse(ScheduledRunGate.shouldRun(now - 1_000L, now))
        assertFalse(ScheduledRunGate.shouldRun(now - (min - 1), now))
    }

    @Test
    fun exactlyAtIntervalPasses() {
        val now = 10_000_000L
        assertTrue(ScheduledRunGate.shouldRun(now - min, now))
    }

    @Test
    fun longAgoPasses() {
        val now = 10_000_000L
        assertTrue(ScheduledRunGate.shouldRun(now - min * 10, now))
    }

    /** 时钟回拨时放行：宁可多跑一次，也不要让任务永久不执行。 */
    @Test
    fun clockGoingBackwardsPasses() {
        val now = 10_000_000L
        assertTrue(ScheduledRunGate.shouldRun(now + 60_000L, now))
    }

    @Test
    fun customIntervalIsRespected() {
        val now = 10_000_000L
        assertTrue(ScheduledRunGate.shouldRun(now - 30_000L, now, minIntervalMillis = 10_000L))
        assertFalse(ScheduledRunGate.shouldRun(now - 5_000L, now, minIntervalMillis = 10_000L))
    }

    /** 默认间隔必须能挡住「重排闹钟的连发」，同时不误杀每小时级任务。 */
    @Test
    fun defaultIntervalIsReasonable() {
        assertTrue(ScheduledRunGate.MIN_INTERVAL_MILLIS in 60_000L..600_000L)
    }
}

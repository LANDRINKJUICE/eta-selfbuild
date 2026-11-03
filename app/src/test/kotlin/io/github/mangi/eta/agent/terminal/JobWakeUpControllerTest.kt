package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobWakeUpControllerTest {

    private fun task(
        exitCode: Int? = 1,
        endReason: String? = null,
        description: String = "构建 Release APK",
    ) = DetachedTask(
        id = "job_1",
        pid = 1L,
        token = "token",
        command = "gradle assembleRelease",
        cwd = "/workspace",
        identity = "root",
        environment = TerminalEnvironment.ANDROID,
        logPath = "/data/local/tmp/eta/daemon/job_1.log",
        startedAt = 0L,
        description = description,
        exitCode = exitCode,
        endReason = endReason,
    )

    @Test
    fun onlyRealFailuresAreWorthWakingUp() {
        assertFalse(JobWakeUpController.isFailure(0, null))
        assertFalse(JobWakeUpController.isFailure(0, "exited"))
        assertTrue(JobWakeUpController.isFailure(1, null))
        assertTrue(JobWakeUpController.isFailure(137, "failed"))
        // 超时算失败：命令没跑完，Agent 需要知道。
        assertTrue(JobWakeUpController.isFailure(null, "timed_out"))
        // 用户主动停止是意图，不是故障。
        assertFalse(JobWakeUpController.isFailure(null, "stopped"))
        assertFalse(JobWakeUpController.isFailure(143, "stopped"))
        // 没有退出码也没有失败原因时保守不动。
        assertFalse(JobWakeUpController.isFailure(null, null))
    }

    @Test
    fun rateLimitAllowsTwoWakeUpsPerWindow() {
        val now = 1_000_000_000L
        assertEquals(2, JobWakeUpController.remainingWakeUps(emptyList(), now))
        assertEquals(1, JobWakeUpController.remainingWakeUps(listOf(now - 1_000), now))
        assertEquals(0, JobWakeUpController.remainingWakeUps(listOf(now - 1_000, now - 2_000), now))
        // 窗口外的历史不再计数，窗口会自然恢复额度。
        val old = now - JobWakeUpController.WINDOW_MS - 1
        assertEquals(2, JobWakeUpController.remainingWakeUps(listOf(old, old), now))
        assertEquals(1, JobWakeUpController.remainingWakeUps(listOf(old, now - 1_000), now))
    }

    @Test
    fun promptCarriesFactsAndForbidsRetryLoop() {
        val prompt = JobWakeUpController.buildPrompt(task(), "line1\nFAILURE: Build failed")
        assertTrue(prompt.contains("gradle assembleRelease"))
        assertTrue(prompt.contains("构建 Release APK"))
        assertTrue(prompt.contains("退出码：1"))
        assertTrue(prompt.contains("FAILURE: Build failed"))
        assertTrue(prompt.contains("不要再起同样的后台任务"))
    }

    @Test
    fun promptTruncatesLongLogTail() {
        val tail = "x".repeat(JobWakeUpController.LOG_TAIL_CHARS * 2)
        val prompt = JobWakeUpController.buildPrompt(task(), tail)
        assertTrue(prompt.length < JobWakeUpController.LOG_TAIL_CHARS * 2)
    }

    @Test
    fun promptOmitsEmptyLogSection() {
        val prompt = JobWakeUpController.buildPrompt(task(), "   ")
        assertFalse(prompt.contains("日志末尾："))
    }

    @Test
    fun repeatWakeUpTellsAgentItAlreadyTriedOnce() {
        // 第一次唤醒不提次数，避免噪音。
        val first = JobWakeUpController.buildPrompt(task(), "log", wakeUpOrdinal = 1)
        assertFalse(first.contains("自动唤醒"))
        // 第二次必须说清楚，否则它不知道自己在同一个坑里试过一轮。
        val second = JobWakeUpController.buildPrompt(task(), "log", wakeUpOrdinal = 2)
        assertTrue(second.contains("第 2 次自动唤醒"))
        assertTrue(second.contains("不要再尝试同样的做法"))
    }
}

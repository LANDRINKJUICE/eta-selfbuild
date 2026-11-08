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
    fun pendingQueueSurvivesRoundTrip() {
        // 2026-11-08：唤醒改持久队列，进程重启后靠这段编码取回待办，坏一条就整条丢失。
        val entry = JobWakeUpController.PendingWakeUp(
            taskId = "job_1",
            description = "盯构建并取包签名",
            exitCode = 1,
            prompt = "后台命令结束了，但看起来是失败的。",
            createdAt = 1_700_000_000_000L,
        )
        val decoded = JobWakeUpController.decodePending(
            JobWakeUpController.encodePending(listOf(entry)),
        )
        assertEquals(listOf(entry), decoded)
    }

    @Test
    fun pendingQueueToleratesBrokenEntries() {
        // Pocket 里可能留着旧版本写的半截数据：解析不出来就当没有，不能让唤醒线程崩。
        assertEquals(emptyList<JobWakeUpController.PendingWakeUp>(), JobWakeUpController.decodePending(null))
        assertEquals(emptyList<JobWakeUpController.PendingWakeUp>(), JobWakeUpController.decodePending("not json"))
        assertEquals(
            emptyList<JobWakeUpController.PendingWakeUp>(),
            JobWakeUpController.decodePending("[{\"task_id\":\"job_1\"}]"),
        )
    }

    @Test
    fun pendingQueueKeepsOneEntryPerTask() {
        val first = JobWakeUpController.PendingWakeUp("job_1", "第一次", 1, "p1", 1L)
        val again = first.copy(prompt = "p2", createdAt = 2L)
        val other = JobWakeUpController.PendingWakeUp("job_2", "别的任务", 2, "p3", 3L)
        val merged = JobWakeUpController.upsertPending(listOf(first), again)
        assertEquals(1, merged.size)
        assertEquals("p2", merged.first().prompt)
        val both = JobWakeUpController.upsertPending(merged, other)
        assertEquals(listOf("job_1", "job_2"), both.map { it.taskId })
    }

    @Test
    fun pendingQueueIsCapped() {
        val queue = (1..8).fold(emptyList<JobWakeUpController.PendingWakeUp>()) { acc, index ->
            JobWakeUpController.upsertPending(
                acc,
                JobWakeUpController.PendingWakeUp("job_$index", "任务 $index", 1, "p$index", index.toLong()),
            )
        }
        assertEquals(4, queue.size)
        assertEquals(listOf("job_5", "job_6", "job_7", "job_8"), queue.map { it.taskId })
    }

    @Test
    fun pendingGivesUpAfterMaxWait() {
        val createdAt = 1_000_000L
        assertFalse(JobWakeUpController.pendingExpired(createdAt, createdAt))
        assertFalse(
            JobWakeUpController.pendingExpired(
                createdAt,
                createdAt + JobWakeUpController.MAX_PENDING_MS - 1,
            ),
        )
        assertTrue(JobWakeUpController.pendingExpired(createdAt, createdAt + JobWakeUpController.MAX_PENDING_MS))
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

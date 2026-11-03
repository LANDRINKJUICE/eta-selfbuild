package io.github.mangi.eta.agent.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闹钟清理集合。
 *
 * 为什么需要这组测试：上一版我把 LEGACY_TASK_IDS 常量写好了，却忘了把它接进 cancelAll，
 * 装机后闹钟仍然是 14 个（7 任务 + 7 孤儿）——**代码看着像修了，实际一点没生效**。
 * 所以把「该取消哪些 id」抽成纯函数并用测试锁住，让这种错误没法再溜过去。
 */
class ScheduledTaskSchedulerIdsTest {

    @Test
    fun legacyIdsAlwaysIncluded() {
        val ids = ScheduledTaskScheduler.idsToCancel(emptyList())

        // 这几个是实测产生过孤儿闹钟的旧 id，必须在清理集合里
        assertTrue("eta-device-1230" in ids)
        assertTrue("eta-perm-0900" in ids)
        assertTrue("eta-selfcheck-1400" in ids)
        assertTrue("eta-backup-2200" in ids)
        assertTrue("eta-clean-2330" in ids)
        assertTrue("eta-memory-2300" in ids)
        assertTrue("eta-weekcheck-2345" in ids)
    }

    @Test
    fun currentTasksAreIncluded() {
        val current = listOf("eta-drift-0830", "eta-snapshot-1800")
        val ids = ScheduledTaskScheduler.idsToCancel(current)

        assertTrue("eta-drift-0830" in ids)
        assertTrue("eta-snapshot-1800" in ids)
    }

    /** 当前任务与遗留 id 有重叠时不能重复取消（虽然幂等，但去重更清楚）。 */
    @Test
    fun resultIsDeduplicated() {
        val ids = ScheduledTaskScheduler.idsToCancel(listOf("eta-device-1230"))

        assertEquals(ids.size, ids.toSet().size)
    }

    /** 空任务列表也要返回完整的遗留 id，否则卸载重装后孤儿清不掉。 */
    @Test
    fun emptyCurrentListStillReturnsLegacy() {
        assertEquals(ScheduledTaskScheduler.idsToCancel(emptyList()), ScheduledTaskScheduler.idsToCancel(listOf()))
        assertTrue(ScheduledTaskScheduler.idsToCancel(emptyList()).isNotEmpty())
    }
}

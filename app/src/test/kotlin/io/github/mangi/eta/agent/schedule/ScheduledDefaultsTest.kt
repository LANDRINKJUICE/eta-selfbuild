package io.github.mangi.eta.agent.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认定时任务的周期取舍。
 *
 * 这些断言锁的是**实测结论**，不是口味：
 * self_check 跑一次 40~64 秒且结论天天一样，所以不该每天；
 * drift_check 0.5 秒且是真正的改动监控，所以该每天。
 * 以后谁想改周期，得先推翻这些数字。
 */
class ScheduledDefaultsTest {

    private val defaults = ScheduledDefaults.build()

    @Test
    fun seedsSevenTasks() {
        assertEquals(7, defaults.size)
    }

    @Test
    fun idsAreUnique() {
        assertEquals(defaults.size, defaults.map { it.id }.toSet().size)
    }

    @Test
    fun allEnabledAndTimeBased() {
        assertTrue(defaults.all { it.enabled })
        assertTrue(defaults.all { it.trigger == ScheduledTrigger.DAILY_TIME })
        assertTrue(defaults.all { it.trigger.usesAlarm })
    }

    /** 每天只留轻任务：drift_check(0.5s)、snapshot(8.3s)、clean_tmp(1.4s)。 */
    @Test
    fun dailyTasksAreTheCheapOnes() {
        val daily = defaults.filter { it.repeat == ScheduledRepeat.DAILY }.map { it.action }.toSet()
        assertEquals(
            setOf(
                ScheduledActionKind.DRIFT_CHECK,
                ScheduledActionKind.SNAPSHOT,
                ScheduledActionKind.CLEAN_TMP,
            ),
            daily,
        )
    }

    /** self_check 是全表最重的（40~64 秒），绝不能是每天。 */
    @Test
    fun heavySelfCheckIsNotDaily() {
        val selfCheck = defaults.first { it.action == ScheduledActionKind.SELF_CHECK }
        assertEquals(ScheduledRepeat.WEEKLY, selfCheck.repeat)
    }

    /** 备份一次 11MB，每天留副本没有意义。 */
    @Test
    fun backupIsNotDaily() {
        val backup = defaults.first { it.action == ScheduledActionKind.BRAIN_BACKUP }
        assertEquals(ScheduledRepeat.WEEKLY, backup.repeat)
    }

    /** memory_status 只报体积数字、没有行动价值，默认不建。 */
    @Test
    fun memoryStatusIsNotSeeded() {
        assertFalse(defaults.any { it.action == ScheduledActionKind.MEMORY_STATUS })
    }

    /** 同一动作不该出现两次（老配置里 self_check 一天跑两遍）。 */
    @Test
    fun noDuplicateActions() {
        val actions = defaults.map { it.action }
        assertEquals(actions.size, actions.toSet().size)
    }

    /** 周任务必须带合法的 dayOfWeek，否则会被算成默认值。 */
    @Test
    fun weeklyTasksHaveValidDayOfWeek() {
        defaults.filter { it.repeat == ScheduledRepeat.WEEKLY }.forEach { task ->
            assertTrue("${task.id} dayOfWeek=${task.dayOfWeek}", task.dayOfWeek in 1..7)
        }
    }

    /**
     * 时间点要避开深夜：本机实测 ColorOS 会压制深夜闹钟，
     * 11-02 那天 9 个任务全挤在 23:49~23:50 一次性补发。
     */
    @Test
    fun noTaskScheduledInDeadOfNight() {
        defaults.forEach { task ->
            assertTrue(
                "${task.id} 排在 ${task.hour}:${task.minute}，深夜容易被省电压制",
                task.hour in 8..22,
            )
        }
    }
}

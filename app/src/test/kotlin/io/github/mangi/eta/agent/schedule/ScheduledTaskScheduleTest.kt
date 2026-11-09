package io.github.mangi.eta.agent.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 周期计算的边界测试。
 *
 * 这里全是纯 JVM 逻辑（不依赖 Android），所以**本机可以跑**，
 * 是改动调度逻辑后唯一能快速自检的手段。
 */
class ScheduledTaskScheduleTest {

    private fun millis(
        year: Int,
        month: Int, // 1-12
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    private fun Calendar.readable(): String =
        "${get(Calendar.YEAR)}-${get(Calendar.MONTH) + 1}-${get(Calendar.DAY_OF_MONTH)} " +
            "${get(Calendar.HOUR_OF_DAY)}:${get(Calendar.MINUTE)}"

    private fun describe(millis: Long): String =
        Calendar.getInstance().apply { timeInMillis = millis }.readable()

    private fun task(
        repeat: ScheduledRepeat,
        hour: Int = 9,
        minute: Int = 0,
        dayOfWeek: Int = Calendar.MONDAY,
        dayOfMonth: Int = 1,
        interval: Int = 1,
    ) = ScheduledTask(
        id = "test",
        trigger = ScheduledTrigger.DAILY_TIME,
        action = ScheduledActionKind.CUSTOM_PROMPT,
        enabled = true,
        hour = hour,
        minute = minute,
        repeat = repeat,
        dayOfWeek = dayOfWeek,
        dayOfMonth = dayOfMonth,
        interval = interval,
    )

    @Test
    fun daily_returnsTodayWhenTimeHasNotPassed() {
        val now = millis(2026, 3, 10, 8, 0)
        val next = ScheduledTaskSchedule.nextTriggerMillis(task(ScheduledRepeat.DAILY, 9, 0), now)
        assertEquals("2026-3-10 9:0", describe(next))
    }

    @Test
    fun daily_rollsToTomorrowWhenTimeAlreadyPassed() {
        val now = millis(2026, 3, 10, 10, 0)
        val next = ScheduledTaskSchedule.nextTriggerMillis(task(ScheduledRepeat.DAILY, 9, 0), now)
        assertEquals("2026-3-11 9:0", describe(next))
    }

    @Test
    fun daily_isStrictlyAfterNowWhenExactlyEqual() {
        val now = millis(2026, 3, 10, 9, 0)
        val next = ScheduledTaskSchedule.nextTriggerMillis(task(ScheduledRepeat.DAILY, 9, 0), now)
        assertTrue("下一次必须严格大于当前时间，否则会原地反复触发", next > now)
        assertEquals("2026-3-11 9:0", describe(next))
    }

    @Test
    fun weekly_picksNearestSameWeekday() {
        // 2026-03-10 是周二；目标周一 9:00 → 应到 2026-03-16（下周一）
        val now = millis(2026, 3, 10, 12, 0)
        val next = ScheduledTaskSchedule.nextTriggerMillis(
            task(ScheduledRepeat.WEEKLY, 9, 0, dayOfWeek = Calendar.MONDAY),
            now,
        )
        assertEquals("2026-3-16 9:0", describe(next))
    }

    @Test
    fun weekly_handlesYearBoundary() {
        // 2026-12-31 是周四；目标周一 → 跨年到 2027-01-04
        val now = millis(2026, 12, 31, 12, 0)
        val next = ScheduledTaskSchedule.nextTriggerMillis(
            task(ScheduledRepeat.WEEKLY, 9, 0, dayOfWeek = Calendar.MONDAY),
            now,
        )
        assertEquals("2027-1-4 9:0", describe(next))
    }

    @Test
    fun monthly_clampsToLastDayWhenMonthIsShort() {
        // 每月 31 日；当前 2026-01-31 之后 → 2 月没有 31 日，应顺延到 2 月最后一天
        val now = millis(2026, 1, 31, 12, 0)
        val next = ScheduledTaskSchedule.nextTriggerMillis(
            task(ScheduledRepeat.MONTHLY, 9, 0, dayOfMonth = 31),
            now,
        )
        val expectedDay = Calendar.getInstance().apply {
            clear(); set(2026, 1, 1) // 2 月
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
        assertEquals("2026-2-$expectedDay 9:0", describe(next))
    }

    @Test
    fun monthly_handlesDecemberToJanuary() {
        val now = millis(2026, 12, 20, 12, 0)
        val next = ScheduledTaskSchedule.nextTriggerMillis(
            task(ScheduledRepeat.MONTHLY, 9, 0, dayOfMonth = 5),
            now,
        )
        assertEquals("2027-1-5 9:0", describe(next))
    }

    @Test
    fun everyNDays_usesInterval() {
        val now = millis(2026, 3, 10, 10, 0) // 已过 9:00
        val next = ScheduledTaskSchedule.nextTriggerMillis(
            task(ScheduledRepeat.EVERY_N_DAYS, 9, 0, interval = 3),
            now,
        )
        assertEquals("2026-3-13 9:0", describe(next))
    }

    @Test
    fun everyNHours_usesInterval() {
        val now = millis(2026, 3, 10, 10, 30)
        val next = ScheduledTaskSchedule.nextTriggerMillis(
            task(ScheduledRepeat.EVERY_N_HOURS, 0, 15, interval = 6),
            now,
        )
        // 10:30 之后的下一个「第 15 分」是 11:15，但间隔为 6 小时点时按 6 小时推进
        assertTrue("必须落在未来", next > now)
    }

    @Test
    fun describe_isHumanReadable() {
        assertEquals("每天 09:00", ScheduledTaskSchedule.describe(task(ScheduledRepeat.DAILY, 9, 0)))
        assertEquals(
            "周一 08:30",
            ScheduledTaskSchedule.describe(task(ScheduledRepeat.WEEKLY, 8, 30, dayOfWeek = Calendar.MONDAY)),
        )
        assertEquals(
            "每月 1 日 09:00",
            ScheduledTaskSchedule.describe(task(ScheduledRepeat.MONTHLY, 9, 0, dayOfMonth = 1)),
        )
        assertEquals(
            "每 3 天 09:00",
            ScheduledTaskSchedule.describe(task(ScheduledRepeat.EVERY_N_DAYS, 9, 0, interval = 3)),
        )
    }
}

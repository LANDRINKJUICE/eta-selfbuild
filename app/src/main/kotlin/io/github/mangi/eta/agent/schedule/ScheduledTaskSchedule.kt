package io.github.mangi.eta.agent.schedule

import java.util.Calendar

/**
 * 定时任务的重复周期。
 *
 * 设计取舍：所有周期都换算成「下一次触发的毫秒时间戳」，
 * 由 [ScheduledTaskSchedule.nextTriggerMillis] 统一计算，
 * 调度层不关心周期类型 —— 这样加新周期时只需要改这一个文件。
 */
internal enum class ScheduledRepeat(val id: String) {
    /** 每天固定时刻。 */
    DAILY("daily"),

    /** 每周固定星期几 + 时刻。 */
    WEEKLY("weekly"),

    /** 每月固定日 + 时刻；该月没有这一天时顺延到月末（不跳过这个月）。 */
    MONTHLY("monthly"),

    /** 每隔 N 天。 */
    EVERY_N_DAYS("every_n_days"),

    /** 每隔 N 小时（分钟字段生效）。 */
    EVERY_N_HOURS("every_n_hours"),
    ;

    companion object {
        fun fromId(value: String?): ScheduledRepeat? = entries.firstOrNull { it.id == value }
    }
}

/**
 * 周期的纯计算逻辑：**没有任何 Android 依赖**，可单独单元测试。
 *
 * 几个必须处理对的边界（都体现在单测里）：
 * 1. **月末**：每月 31 号遇到 2 月/4 月要顺延到当月最后一天，而不是跳过这个月。
 * 2. **跨年**：12 月 31 日的「下一个每周一」要正确跨到下一年。
 * 3. **恰好等于**：下一次触发必须**严格大于** now，否则会在同一时刻反复触发。
 * 4. **夏令时/时区**：用 Calendar 的本地时区计算，不做手写的毫秒加减。
 */
internal object ScheduledTaskSchedule {

    /**
     * 计算下一次触发时间（毫秒）。
     *
     * @param task 任务（用到 repeat / hour / minute / dayOfWeek / dayOfMonth / interval）
     * @param nowMillis 当前时间
     */
    fun nextTriggerMillis(task: ScheduledTask, nowMillis: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return when (task.repeat) {
            ScheduledRepeat.DAILY -> nextDaily(calendar, task)
            ScheduledRepeat.WEEKLY -> nextWeekly(calendar, task)
            ScheduledRepeat.MONTHLY -> nextMonthly(calendar, task)
            ScheduledRepeat.EVERY_N_DAYS -> nextEveryNDays(calendar, task)
            ScheduledRepeat.EVERY_N_HOURS -> nextEveryNHours(calendar, task)
        }
    }

    // ---------------------------------------------------------------- 每天

    private fun nextDaily(calendar: Calendar, task: ScheduledTask): Long =
        advanceToTime(calendar, task, minimumDaysAhead = 0)

    // ---------------------------------------------------------------- 每周

    private fun nextWeekly(calendar: Calendar, task: ScheduledTask): Long {
        val target = task.dayOfWeek.coerceIn(1, 7) // Calendar.MONDAY=2 ... SUNDAY=1
        val result = clone(calendar)
        // 最多试 8 天：覆盖「今天已过点」与「本周已过」两种情况。
        for (step in 0..7) {
            val candidate = clone(calendar)
            candidate.add(Calendar.DAY_OF_YEAR, step)
            if (candidate.get(Calendar.DAY_OF_WEEK) != target) continue
            applyTime(candidate, task)
            if (candidate.timeInMillis > calendar.timeInMillis) return candidate.timeInMillis
        }
        // 理论上不会走到这里；兜底加一周，保证永远返回未来时间。
        return advanceToTime(result, task, minimumDaysAhead = 7)
    }

    // ---------------------------------------------------------------- 每月

    private fun nextMonthly(calendar: Calendar, task: ScheduledTask): Long {
        val desiredDay = task.dayOfMonth.coerceIn(1, 31)
        // 先试本月，再试后续 12 个月（保证跨年也能命中）。
        for (monthOffset in 0..12) {
            val candidate = clone(calendar)
            candidate.add(Calendar.MONTH, monthOffset)
            // 关键：先设成 1 号再加日期，避免「1月31日 + 1个月 = 2月31日」自动滚到 3 月。
            candidate.set(Calendar.DAY_OF_MONTH, 1)
            val lastDay = candidate.getActualMaximum(Calendar.DAY_OF_MONTH)
            candidate.set(Calendar.DAY_OF_MONTH, minOf(desiredDay, lastDay))
            applyTime(candidate, task)
            if (candidate.timeInMillis > calendar.timeInMillis) return candidate.timeInMillis
        }
        return advanceToTime(calendar, task, minimumDaysAhead = 30)
    }

    // ---------------------------------------------------------------- 每 N 天

    private fun nextEveryNDays(calendar: Calendar, task: ScheduledTask): Long {
        val interval = task.interval.coerceAtLeast(1)
        val candidate = clone(calendar)
        applyTime(candidate, task)
        if (candidate.timeInMillis > calendar.timeInMillis) return candidate.timeInMillis
        candidate.add(Calendar.DAY_OF_YEAR, interval)
        return candidate.timeInMillis
    }

    // ---------------------------------------------------------------- 每 N 小时

    private fun nextEveryNHours(calendar: Calendar, task: ScheduledTask): Long {
        val interval = task.interval.coerceAtLeast(1)
        val candidate = clone(calendar)
        // 分钟沿用任务设置，秒与毫秒归零，保证同一小时内只触发一次。
        candidate.set(Calendar.MINUTE, task.minute.coerceIn(0, 59))
        candidate.set(Calendar.SECOND, 0)
        candidate.set(Calendar.MILLISECOND, 0)
        if (candidate.timeInMillis <= calendar.timeInMillis) {
            candidate.add(Calendar.HOUR_OF_DAY, interval)
        }
        return candidate.timeInMillis
    }

    // ---------------------------------------------------------------- 工具

    /** 把候选时间设成任务指定的时分；若结果不在未来，则按天推进直到落在未来。 */
    private fun advanceToTime(calendar: Calendar, task: ScheduledTask, minimumDaysAhead: Int): Long {
        val candidate = clone(calendar)
        if (minimumDaysAhead > 0) candidate.add(Calendar.DAY_OF_YEAR, minimumDaysAhead)
        applyTime(candidate, task)
        while (candidate.timeInMillis <= calendar.timeInMillis) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
            applyTime(candidate, task)
        }
        return candidate.timeInMillis
    }

    private fun applyTime(calendar: Calendar, task: ScheduledTask) {
        calendar.set(Calendar.HOUR_OF_DAY, task.hour.coerceIn(0, 23))
        calendar.set(Calendar.MINUTE, task.minute.coerceIn(0, 59))
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
    }

    private fun clone(source: Calendar): Calendar =
        (source.clone() as Calendar).apply { timeInMillis = source.timeInMillis }

    /** 周期的人类可读描述（用于通知副标题与列表）。 */
    fun describe(task: ScheduledTask): String {
        val time = String.format(java.util.Locale.US, "%02d:%02d", task.hour, task.minute)
        return when (task.repeat) {
            ScheduledRepeat.DAILY -> "每天 $time"
            ScheduledRepeat.WEEKLY -> "${weekdayName(task.dayOfWeek)} $time"
            ScheduledRepeat.MONTHLY -> "每月 ${task.dayOfMonth} 日 $time"
            ScheduledRepeat.EVERY_N_DAYS -> "每 ${task.interval} 天 $time"
            ScheduledRepeat.EVERY_N_HOURS -> "每 ${task.interval} 小时（第 ${task.minute} 分）"
        }
    }

    private fun weekdayName(dayOfWeek: Int): String = when (dayOfWeek) {
        Calendar.MONDAY -> "周一"
        Calendar.TUESDAY -> "周二"
        Calendar.WEDNESDAY -> "周三"
        Calendar.THURSDAY -> "周四"
        Calendar.FRIDAY -> "周五"
        Calendar.SATURDAY -> "周六"
        Calendar.SUNDAY -> "周日"
        else -> "每周"
    }
}

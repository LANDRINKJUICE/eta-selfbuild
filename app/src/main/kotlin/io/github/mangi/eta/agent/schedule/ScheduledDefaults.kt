package io.github.mangi.eta.agent.schedule

/**
 * 首次启用时的默认定时任务。
 *
 * 为什么需要它：此前 9 个任务是手工建的，存在 SharedPreferences 里，和 APK 无关 ——
 * 换机、清数据、或者别人装这个包，就一个任务都没有，什么都不跑。
 *
 * 周期怎么定的（2026-11-03 实测依据，不是拍脑袋）：
 * | 任务 | 实测耗时 | 结论 |
 * |---|---|---|
 * | device_status | 0.0s | 数字每天变，但没人需要每天知道 → 每周 |
 * | permissions_check | 0.2s | 两次执行结论完全一致，权限很少变 → 每周 |
 * | drift_check | 0.5s | 比对快照检测系统改动，价值高 → 每天 |
 * | snapshot | 8.3s | 给 drift_check 做基线 → 每天 |
 * | self_check | 40~64s | 全表最重，且结论天天一样 → 每周 |
 * | brain_backup | 0.4s | 每次 11MB，防丢不需要每日副本 → 每周 |
 * | clean_tmp | 1.4s | 防止 tmp 膨胀 → 每天 |
 * | memory_status | 0.0s | 只报体积数字，无行动价值 → 不建 |
 *
 * 合计每天 3 个轻任务（约 10 秒），每周再加 4 个。
 *
 * 时间点选择：避开深夜。本机 ColorOS 有激进省电，实测深夜的闹钟会被压制，
 * 醒来后一次性补发（11-02 那天 9 个任务全挤在 23:49~23:50 补跑）。
 * 所以重的任务放白天/傍晚，只在用户可能用到手机的时段。
 *
 * 周几用 Calendar 约定：SUNDAY=1、MONDAY=2 … SATURDAY=7。
 */
internal object ScheduledDefaults {

    private const val SUNDAY = 1
    private const val MONDAY = 2

    fun seedIfNeeded(context: android.content.Context) {
        // 已经有任务（老用户手工建的）就只打标记，绝不覆盖用户数据。
        if (ScheduledTaskStore.hasSeeded(context)) return
        if (ScheduledTaskStore.load(context).isNotEmpty()) {
            ScheduledTaskStore.markSeeded(context)
            return
        }
        val defaults = build()
        ScheduledTaskStore.save(context, defaults)
        ScheduledTaskStore.markSeeded(context)
        io.github.mangi.eta.core.AndroidAgentLogger.warn("已写入默认定时任务：${defaults.size} 个")
    }

    internal fun build(): List<ScheduledTask> = listOf(
        // ---- 每天：轻量、每天有信息量 ----
        task(
            id = "eta-drift-0830",
            action = ScheduledActionKind.DRIFT_CHECK,
            hour = 8, minute = 30,
            repeat = ScheduledRepeat.DAILY,
            threshold = 20,
        ),
        task(
            id = "eta-snapshot-1800",
            action = ScheduledActionKind.SNAPSHOT,
            hour = 18, minute = 0,
            repeat = ScheduledRepeat.DAILY,
            threshold = 20,
        ),
        task(
            id = "eta-clean-2130",
            action = ScheduledActionKind.CLEAN_TMP,
            hour = 21, minute = 30,
            repeat = ScheduledRepeat.DAILY,
            threshold = 20,
        ),

        // ---- 每周：重任务或变化慢的信息 ----
        task(
            id = "eta-device-mon1230",
            action = ScheduledActionKind.DEVICE_STATUS,
            hour = 12, minute = 30,
            repeat = ScheduledRepeat.WEEKLY,
            dayOfWeek = MONDAY,
            threshold = 20,
        ),
        task(
            id = "eta-perm-mon0900",
            action = ScheduledActionKind.PERMISSIONS_CHECK,
            hour = 9, minute = 0,
            repeat = ScheduledRepeat.WEEKLY,
            dayOfWeek = MONDAY,
            threshold = 20,
        ),
        task(
            id = "eta-selfcheck-sun1100",
            action = ScheduledActionKind.SELF_CHECK,
            hour = 11, minute = 0,
            repeat = ScheduledRepeat.WEEKLY,
            dayOfWeek = SUNDAY,
            threshold = 20,
        ),
        task(
            id = "eta-backup-sun2200",
            action = ScheduledActionKind.BRAIN_BACKUP,
            hour = 22, minute = 0,
            repeat = ScheduledRepeat.WEEKLY,
            dayOfWeek = SUNDAY,
            threshold = 20,
        ),
    )

    private fun task(
        id: String,
        action: ScheduledActionKind,
        hour: Int,
        minute: Int,
        repeat: ScheduledRepeat,
        dayOfWeek: Int = MONDAY,
        threshold: Int,
    ): ScheduledTask = ScheduledTask(
        id = id,
        trigger = ScheduledTrigger.DAILY_TIME,
        action = action,
        enabled = true,
        hour = hour,
        minute = minute,
        threshold = threshold,
        keyword = "",
        repeat = repeat,
        dayOfWeek = dayOfWeek,
    )
}

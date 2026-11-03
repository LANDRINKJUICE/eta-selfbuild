package io.github.mangi.eta.agent.schedule

/**
 * 同一任务两次执行之间的最小间隔判定。
 *
 * 为什么需要它（2026-11-03 实测）：
 * 11-02 23:49~23:50 那一分钟里，9 个任务全被触发了一次（重启后重排闹钟的补发），
 * 随后 `selfcheck` 与 `snapshot` 又各跑了一次 —— 结果 `05-系统分析-sysanalyze/` 下
 * 出现 `SYSTEM-SNAPSHOT-2026-11-03.md` 和 `SYSTEM-SNAPSHOT-2026-11-03-0750.md` 两个同内容文件。
 *
 * 根因不是调度算错，而是 [ScheduledTaskReceiver] **只看 enabled 就执行**，
 * 不管这次闹钟是不是「同一时刻的第二次」。上游 Eta 原本就是这样设计的，
 * 所以这里**只做去重，不改变「该不该补跑」的语义** —— 保持上游行为不变，
 * 只挡掉「短时间内对同一任务重复执行」。
 *
 * 取舍说明：
 * - 间隔取 [MIN_INTERVAL_MILLIS]（2 分钟）。太小挡不住重排连发，
 *   太大会误杀「每隔 N 小时」这类高频任务的正常触发。
 * - `lastRunAtMillis` 为空（新任务/从没跑过）一律放行。
 * - 时钟回拨（lastRun 比现在晚）也放行：宁可多跑一次，不要因为系统时间异常把任务永久卡死。
 */
internal object ScheduledRunGate {

    /** 同一任务两次执行的最小间隔：2 分钟。 */
    internal const val MIN_INTERVAL_MILLIS = 2 * 60 * 1000L

    /**
     * @param lastRunAtMillis 上次执行完成时间戳；null 表示没跑过
     * @param nowMillis 本次触发时间
     * @return true 表示应该执行；false 表示这是一次重复触发，跳过
     */
    internal fun shouldRun(
        lastRunAtMillis: Long?,
        nowMillis: Long,
        minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
    ): Boolean {
        if (lastRunAtMillis == null || lastRunAtMillis <= 0L) return true
        val elapsed = nowMillis - lastRunAtMillis
        // 时钟回拨或时间戳异常：放行，避免任务永久不执行。
        if (elapsed < 0L) return true
        return elapsed >= minIntervalMillis
    }
}

package io.github.mangi.eta.agent.schedule

/**
 * 一次定时任务执行的结果。
 *
 * 为什么不再「返回摘要字符串、再用前缀猜成败」：
 * 2026-11-04 真机上出现「通知写『定时任务完成』、正文却是『执行超时』」，根因就是猜。
 * 同一个坑后来还露了两次 —— 模型返回 `HTTP 401` / `模型返回为空`、
 * 脚本返回「未执行：找不到脚本」，都不在失败前缀表里，于是全被记成成功，
 * 执行历史打绿勾、连续失败统计和失败自动唤醒一起失灵。
 *
 * 现在由执行方直接给出 [ok]，[summary] 只负责展示。
 */
internal data class ScheduledOutcome(
    val ok: Boolean,
    val summary: String,
) {
    companion object {
        /**
         * 内置动作返回文本里代表「没跑成」的前缀。
         *
         * 只用于把还没改成 [ScheduledOutcome] 的文本结果折成结果（[of]），
         * 以及兜底防漏：即使某个动作忘了给 ok，也不会把明显失败的文本当成功。
         */
        val FAILURE_PREFIXES = listOf(
            "执行失败",
            "执行超时",
            "没有可用的模型配置",
            "指令为空",
            "未执行",
            "跳过",
            // 模型 run 被停止（用户点停止、服务被杀）时 error 就是「已停止」。
            // 真机上这条曾被记成成功（ok=true），历史和通知都显示完成。
            "已停止",
        )

        /** 按文本前缀折成结果：明显失败的文本一律 ok=false。 */
        fun of(summary: String): ScheduledOutcome =
            ScheduledOutcome(
                ok = FAILURE_PREFIXES.none { summary.startsWith(it) },
                summary = summary,
            )
    }
}

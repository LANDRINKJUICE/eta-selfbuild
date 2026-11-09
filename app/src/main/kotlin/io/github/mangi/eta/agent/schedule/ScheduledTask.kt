package io.github.mangi.eta.agent.schedule

/**
 * 定时任务的动作：只保留两条通用路径。
 *
 * 2026-11-09 按用户要求把内置动作全删了 —— device_status / memory_status /
 * permissions_check / self_check / snapshot / brain_backup / clean_tmp / drift_check。
 * 理由：那些改一行就要发版，坏了只能等新包，实际天天一个结论没有信息量。
 * 需要这些能力时用 [PLUGIN] 挂插件，插件改完立刻生效。
 */
internal enum class ScheduledActionKind(val id: String) {
    /** 自定义指令：把 `ScheduledTask.prompt` 交给 Agent 执行（消耗模型额度）。 */
    CUSTOM_PROMPT("custom_prompt"),

    /** 调用插件。**无人值守**：需要确认的插件会被跳过并写明原因。 */
    PLUGIN("plugin"),
    ;

    /** 是否需要调用模型。 */
    val usesModel: Boolean get() = this == CUSTOM_PROMPT

    companion object {
        fun fromId(value: String?): ScheduledActionKind? = entries.firstOrNull { it.id == value }
    }
}

package io.github.mangi.eta.agent.schedule

/**
 * 定时任务的动作：既可以是内置的本地检查，也可以是交给 Agent 执行的自由指令。
 *
 * 数据模型见同包的 `ScheduledTask`（在 ScheduledTrigger.kt 里，与触发条件放在一起）。
 */
internal enum class ScheduledActionKind(val id: String) {
    /** 设备状态摘要：内存 / 存储 / 电量 / 温度 / 运行时长。纯本地，不联网。 */
    DEVICE_STATUS("device_status"),

    /** 记忆状态：MEMORY.md 体积、核心记忆字符数、是否会被截断。纯本地。 */
    MEMORY_STATUS("memory_status"),

    /** 权限体检：通知、悬浮窗、无障碍、精确闹钟、电池优化是否仍然可用。纯本地。 */
    PERMISSIONS_CHECK("permissions_check"),

    /** 自定义指令：把 `ScheduledTask.prompt` 交给 Agent 执行（**会消耗模型额度**）。 */
    CUSTOM_PROMPT("custom_prompt"),
    ;

    /** 是否需要调用模型（决定是否要拉主进程与前台服务）。 */
    val usesModel: Boolean get() = this == CUSTOM_PROMPT

    companion object {
        fun fromId(value: String?): ScheduledActionKind? = entries.firstOrNull { it.id == value }
    }
}

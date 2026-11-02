package io.github.mangi.eta.agent.schedule

import org.json.JSONObject

/**
 * 定时任务能做的动作。
 *
 * 三个内置动作相当于「快捷方式」：纯本地、不联网、不消耗模型额度，无人值守也放心；
 * [CUSTOM_PROMPT] 则完全不限死内容——你写一句话，到点由 Agent 去执行。
 */
internal enum class ScheduledActionKind(val id: String) {
    /** 设备状态摘要：内存 / 存储 / 电量 / 温度 / 运行时长。 */
    DEVICE_STATUS("device_status"),

    /** 记忆状态：MEMORY.md 体积、核心记忆字符数、是否会被截断。 */
    MEMORY_STATUS("memory_status"),

    /** 权限体检：通知、悬浮窗、无障碍、精确闹钟、电池优化是否仍然可用。 */
    PERMISSIONS_CHECK("permissions_check"),

    /** 自定义指令：把 [ScheduledTask.prompt] 交给 Agent 执行（会消耗模型额度）。 */
    CUSTOM_PROMPT("custom_prompt"),
    ;

    companion object {
        fun fromId(value: String?): ScheduledActionKind? = entries.firstOrNull { it.id == value }
    }
}

/** 一条定时任务：每天 [hour]:[minute] 执行一次 [action]。 */
internal data class ScheduledTask(
    val id: String,
    val hour: Int,
    val minute: Int,
    val action: ScheduledActionKind,
    val enabled: Boolean,
    val lastRunAtMillis: Long? = null,
    /** 仅 [ScheduledActionKind.CUSTOM_PROMPT] 使用：到点交给 Agent 执行的指令内容。 */
    val prompt: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hour", hour)
        .put("minute", minute)
        .put("action", action.id)
        .put("enabled", enabled)
        .put("last_run_at", lastRunAtMillis ?: JSONObject.NULL)
        .put("prompt", prompt)

    companion object {
        /** 解析失败返回 null（坏记录直接跳过，不让一条脏数据拖垮整个列表）。 */
        fun fromJson(json: JSONObject): ScheduledTask? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val action = ScheduledActionKind.fromId(json.optString("action")) ?: return null
            val lastRun = if (json.isNull("last_run_at")) {
                null
            } else {
                json.optLong("last_run_at", 0L).takeIf { it > 0L }
            }
            return ScheduledTask(
                id = id,
                hour = json.optInt("hour", 0).coerceIn(0, 23),
                minute = json.optInt("minute", 0).coerceIn(0, 59),
                action = action,
                enabled = json.optBoolean("enabled", true),
                lastRunAtMillis = lastRun,
                prompt = json.optString("prompt", ""),
            )
        }
    }
}

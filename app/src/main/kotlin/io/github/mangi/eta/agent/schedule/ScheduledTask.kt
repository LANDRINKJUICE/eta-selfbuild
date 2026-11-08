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

    /**
     * 自我检查：技能体检、关键路径、各项权限、定时任务闹钟注册、tmp 体积、负载与温度。
     *
     * 与 [PERMISSIONS_CHECK] 的差别：那个只回答「权限还在不在」，这个会跑工作区脚本并逐项给证据。
     */
    SELF_CHECK("self_check"),

    /** 系统快照：跑工作区的 `sys-snapshot.sh`，把结果写到 `05-系统分析-sysanalyze/`。**会写工作区文件**。 */
    SNAPSHOT("snapshot"),

    /** 大脑备份：跑 `eta-brain-backup.sh` 的收集打包部分。**会写工作区 tmp/ 下的 payload**。 */
    BRAIN_BACKUP("brain_backup"),

    /** 清理 tmp：删掉工作区 `tmp/` 里超过 7 天的文件。会删工作区文件，但只限 `tmp/` 里面。 */
    CLEAN_TMP("clean_tmp"),

    /** 系统漂移检测：拿当前状态和最新系统快照逐项比对。纯只读。 */
    DRIFT_CHECK("drift_check"),

    /** 自定义指令：把 `ScheduledTask.prompt` 交给 Agent 执行（**会消耗模型额度**）。 */
    CUSTOM_PROMPT("custom_prompt"),

    /**
     * 调用某个插件（`ScheduledTask.pluginId`）。
     *
     * **无人值守**：需要确认的插件（`confirm: always`、首次确认、危险命令、声明 root）
     * 会被直接跳过并写明原因 —— 见 `PluginToolExecutor(interactive = false)`。
     * 所以这里只适合挂「纯读、不需要确认」的插件（例如读网关状态、读构建状态）。
     */
    PLUGIN("plugin"),
    ;

    /** 是否需要调用模型（决定是否要拉主进程与前台服务）。 */
    val usesModel: Boolean get() = this == CUSTOM_PROMPT

    companion object {
        fun fromId(value: String?): ScheduledActionKind? = entries.firstOrNull { it.id == value }
    }
}

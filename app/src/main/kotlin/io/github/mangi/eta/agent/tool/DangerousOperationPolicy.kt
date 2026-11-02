package io.github.mangi.eta.agent.tool

import org.json.JSONObject

/**
 * 危险操作判定：只拦「**可能让手机变砖或不可逆损坏系统**」的操作。
 *
 * 判定标准（用户 2026-11-02 定）：
 * - **拦**：能破坏启动链、写系统分区、刷机、清空分区、破坏 SELinux 的操作。
 * - **放行**：应用数据、模块配置、settings 修改、卸载应用、临时目录 —— 这些都能恢复，
 *   拦下来只会制造噪音，让人习惯性点"同意"，反而失去警示意义。
 *
 * 判定只看工具名与参数，不联网、不读文件、不依赖运行时状态。
 */
internal object DangerousOperationPolicy {

    internal data class Verdict(val dangerous: Boolean, val reason: String = "") {
        companion object {
            val Safe = Verdict(false)
            fun danger(reason: String) = Verdict(true, reason)
        }
    }

    /**
     * 变砖级命令片段：命中即拦。
     *
     * 分四类：写系统分区、刷机/写块设备、清空分区、破坏安全机制。
     */
    private val BRICK_COMMAND_FRAGMENTS = listOf(
        // ① 写系统分区（挂成可写、或直接往里面写）
        "mount -o rw", "mount -o remount", "/system", "/vendor", "/product", "/my_product", "/odm", "/boot",
        // ② 刷机与写块设备（写错就变砖）
        "fastboot flash", "fastboot oem", "fastboot erase", "mkbootimg", "mkfs",
        "dd if=", "dd of=", "/dev/block/", "by-name",
        // ③ 清空分区/根目录
        "rm -rf /", "rm -rf /*", "rm -rf /data ", "rm -rf /sdcard", "rm -rf /storage",
        "format ", "wipe ",
        // ④ 破坏安全与启动链
        "setenforce 0", "setenforce permissive",
        "/data/adb/service.d", "/data/adb/post-fs-data.d", "/data/adb/modules",
        "init.rc", "magisk --remove", "ksud module",
    )

    /** 文件工具的写操作：只有落到这些分区才算变砖级。 */
    private val BRICK_PATH_PREFIXES = listOf(
        "/system", "/vendor", "/product", "/my_product", "/odm", "/boot",
        "/dev/block", "/data/adb/modules", "/data/adb/service.d", "/data/adb/post-fs-data.d",
    )

    private val WRITE_TOOLS = setOf("write_file", "edit_file")

    fun evaluate(toolName: String, args: JSONObject?): Verdict {
        if (toolName in WRITE_TOOLS) {
            val path = args?.optString("path").orEmpty()
            val hit = BRICK_PATH_PREFIXES.firstOrNull { path.startsWith(it) }
            return if (hit != null) {
                Verdict.danger("会写系统分区或启动链：$hit")
            } else {
                Verdict.Safe
            }
        }

        if (toolName == "terminal" || toolName == "terminal_job" || toolName == "terminal_daemon") {
            val command = buildString {
                append(args?.optString("command").orEmpty())
                append(' ')
                append(args?.optString("cmd").orEmpty())
            }
            if (command.isBlank()) return Verdict.Safe
            val normalized = command.lowercase()
            val hit = BRICK_COMMAND_FRAGMENTS.firstOrNull { normalized.contains(it.lowercase()) }
            if (hit != null) return Verdict.danger("命令包含可能导致手机变砖的片段「$hit」")
        }

        // 其余工具（含 set_setting / app_state_control / 应用数据读写）一律放行：
        // 这些改动都能撤销，拦下来只会打断正常使用。
        return Verdict.Safe
    }
}

package io.github.mangi.eta.agent.tool

import org.json.JSONObject

/**
 * 危险操作判定：判断一次工具调用是否「会造成难以撤销的后果」，需要用户当场确认。
 *
 * 设计原则（用户 2026-11-02 定下的硬规则）：
 * - **只拦「不可逆 / 系统级」**，不拦普通读写。拦太多会变成噪音，用户会习惯性点同意，等于没拦。
 * - 判定只看**工具名 + 参数**，不做网络请求、不读文件。
 * - 拦下来之后**默认拒绝**，必须用户明确同意才继续执行；用户没回应时按拒绝处理。
 *
 * 覆盖范围：
 * 1. 终端命令里的破坏性写法（删除系统路径、写块设备、刷机、改系统设置、卸载/停用应用、格式化）。
 * 2. 文件工具的写操作落到系统分区。
 * 3. 系统设置类工具（set_setting / app_state_control）。
 */
internal object DangerousOperationPolicy {

    /** 判定结果：需要确认时给出「为什么危险」的人类可读理由。 */
    internal data class Verdict(val dangerous: Boolean, val reason: String = "") {
        companion object {
            val Safe = Verdict(false)
            fun danger(reason: String) = Verdict(true, reason)
        }
    }

    /** 会被拦的工具名（这些工具本身就带系统写能力）。 */
    private val RISKY_TOOLS = setOf(
        "set_setting",
        "app_state_control",
    )

    /** 终端命令中出现这些片段就拦（大小写不敏感，只做字面匹配，避免误判）。 */
    private val DANGEROUS_COMMAND_FRAGMENTS = listOf(
        // 系统分区与启动链
        "/system", "/vendor", "/product", "/my_product", "/odm", "/boot",
        // 块设备与分区（写盘=可能变砖）
        "dd if=", "dd of=", "mkfs", "fastboot", "mkbootimg", "flash",
        // 权限与挂载
        "setenforce", "mount -o rw", "mount -o remount",
        // 包状态（会让应用消失或停用）
        "pm uninstall", "pm disable", "pm enable", "pm clear", "pm suspend",
        "dpm set", "dpm remove",
        // 系统设置与属性
        "settings put", "settings delete", "setprop ",
        // 危险删除（递归删根/删数据/sdcard 根）
        "rm -rf /", "rm -rf /*", "rm -rf /data", "rm -rf /sdcard", "rm -rf /storage",
        // 启动链与模块
        "/data/adb/service.d", "/data/adb/post-fs-data.d", "/data/adb/modules",
        // 敏感私有目录
        "/data/data", "/data/user",
    )

    /** 文件工具写操作：只在这些路径才算危险（普通目录随便写）。 */
    private val DANGEROUS_PATH_PREFIXES = listOf(
        "/system", "/vendor", "/product", "/my_product", "/odm", "/boot",
        "/data/adb", "/data/data", "/data/user", "/data/system",
    )

    private val WRITE_TOOLS = setOf("write_file", "edit_file")

    /**
     * 判定一次工具调用。
     *
     * @param toolName 工具名
     * @param args 已解析的参数（拿不到就传空对象，此时只按工具名判定）
     */
    fun evaluate(toolName: String, args: JSONObject?): Verdict {
        if (toolName in RISKY_TOOLS) {
            val target = args?.optString("key").orEmpty().ifBlank {
                args?.optString("package_name").orEmpty()
            }
            return Verdict.danger(
                if (target.isBlank()) "该操作会修改系统设置或应用状态" else "会修改系统设置或应用状态：$target",
            )
        }

        if (toolName in WRITE_TOOLS) {
            val path = args?.optString("path").orEmpty()
            val hit = DANGEROUS_PATH_PREFIXES.firstOrNull { path.startsWith(it) }
            if (hit != null) return Verdict.danger("会写系统分区或私有数据区：$hit")
            return Verdict.Safe
        }

        if (toolName == "terminal" || toolName == "terminal_job" || toolName == "terminal_daemon") {
            // 命令可能在 command 字段，也可能带在 shell 里，两个都看。
            val command = buildString {
                append(args?.optString("command").orEmpty())
                append(' ')
                append(args?.optString("cmd").orEmpty())
            }
            if (command.isBlank()) return Verdict.Safe
            val normalized = command.lowercase()
            val hit = DANGEROUS_COMMAND_FRAGMENTS.firstOrNull { normalized.contains(it.lowercase()) }
            if (hit != null) {
                return Verdict.danger("命令包含高危片段「$hit」")
            }
        }

        return Verdict.Safe
    }
}

package io.github.mangi.eta.agent.tool

import org.json.JSONObject

/**
 * 危险操作判定：只拦「**可能让手机变砖或不可逆损坏系统**」的操作。
 *
 * 判定标准（用户 2026-11-02 定，2026-11-03 放宽）：
 * - **拦**：能破坏启动链、写系统分区、刷机、清空分区、破坏 SELinux 的操作。
 * - **放行**：应用数据、模块配置、settings 修改、卸载应用、临时目录 —— 都能恢复。
 * - **放行一切只读操作**：`ls /system`、`cat /vendor/build.prop`、`grep ... /boot`
 *   这类**不会改变任何状态**，拦下来纯属噪音。
 *
 * 为什么要把"读写"分开判（踩过）：原来只要命令里出现 `/system`、`/vendor` 这些**字符串**
 * 就拦，结果连 `ls /system`、复制文件时路径里带 `/system` 都弹确认框。
 * 用户说"执行复制也会拦截"，根子就在这里 —— 字符串匹配分不清读和写。
 *
 * 判定只看工具名与参数，不联网、不读文件、不依赖运行时状态。
 */
internal object DangerousOperationPolicy {

    /** 危险等级：CRITICAL 可能变砖，WARNING 能恢复但会动系统。 */
    internal enum class Severity { CRITICAL, WARNING }

    internal data class Verdict(
        val dangerous: Boolean,
        /** 命中的规则名，短标签，用于「本会话别问了」的记忆键，也给用户看。 */
        val rule: String = "",
        /** 一句话原因，保留原样（日志与旧调用方仍在用）。 */
        val reason: String = "",
        /** 人话解释：这个操作到底会动到什么。 */
        val impact: String = "",
        /** 万一出事的补救办法；空串表示「不好撤」。 */
        val recovery: String = "",
        val severity: Severity = Severity.WARNING,
    ) {
        companion object {
            val Safe = Verdict(false)

            fun danger(
                rule: String,
                reason: String,
                impact: String,
                recovery: String = "",
                severity: Severity = Severity.WARNING,
            ) = Verdict(true, rule, reason, impact, recovery, severity)
        }
    }

    /**
     * 变砖级命令片段：这些**本身就代表破坏动作**，出现即拦，与路径无关。
     *
     * 注意：这里只放"动词型"片段（刷机、格式化、改 SELinux 等），
     * 不放进裸路径 —— 裸路径应该交给下面的「写动作 + 敏感路径」组合判断。
     */
    private val DESTRUCTIVE_FRAGMENTS = listOf(
        // 刷机与写块设备（写错就变砖）
        "fastboot flash", "fastboot oem", "fastboot erase", "mkbootimg", "mkfs",
        "dd if=", "dd of=",
        // 清空分区 / 根目录。注意不能写 "rm -rf /" —— 那会匹配到 "rm -rf /sdcard/..."，
        // 把用户正常的清理操作也拦下来（实测被这条误伤过）。根目录单独判，见 isRootWipe。
        "format ", "wipe ",
        // 破坏安全与启动链
        "setenforce 0", "setenforce permissive",
        "magisk --remove", "ksud module",
    )

    /** 写入型动作：命令里出现这些动词才算"会改东西"。 */
    private val WRITE_VERBS = listOf(
        "rm ", "rm -", "mv ", "cp ", "dd ", "mkdir ", "touch ", "chmod ", "chown ",
        "ln ", "install ", "tee ", ">", ">>", "truncate ", "sed -i", "patch ",
    )

    /** 只读动作：命中这些、且不含写动词时，一律放行。 */
    private val READ_ONLY_VERBS = listOf(
        "ls", "cat", "head", "tail", "grep", "rg ", "find ", "stat ", "file ",
        "du ", "df ", "wc ", "md5sum", "sha256sum", "readlink", "realpath",
        "getprop", "dumpsys", "pm list", "cmd ", "echo ",
    )

    /**
     * 启动链目录：写坏了会影响开机，但**删掉写入的文件就能恢复**。
     * 这类给 WARNING，措辞上说清「能救」。
     */
    private val BOOT_CHAIN_PATHS = listOf(
        "/data/adb/service.d", "/data/adb/post-fs-data.d", "/data/adb/modules", "init.rc",
    )

    /**
     * 系统分区与块设备：改动不可逆或极难恢复，出错通常只能刷机。这类给 CRITICAL。
     */
    private val SYSTEM_PARTITION_PATHS = listOf(
        "/system", "/vendor", "/product", "/my_product", "/odm", "/boot",
        "/dev/block", "by-name",
    )

    /** 文件工具的写操作：只有落到这些前缀才算变砖级。 */
    private val BRICK_PATH_PREFIXES = SYSTEM_PARTITION_PATHS + BOOT_CHAIN_PATHS

    /** 敏感路径：只有**配合写动词**出现时才拦。 */
    private val SENSITIVE_PATHS = BOOT_CHAIN_PATHS + SYSTEM_PARTITION_PATHS

    /** shell 子命令分隔符（换行、`;`、`&&`、`||`、`|`、`&`）：逐段判定的切分依据。 */
    private val COMMAND_SEGMENT_SEPARATORS = Regex("[\\n;|&]+")

    private val WRITE_TOOLS = setOf("write_file", "edit_file")

    fun evaluate(toolName: String, args: JSONObject?): Verdict {
        if (toolName in WRITE_TOOLS) {
            val path = args?.optString("path").orEmpty()
            val hit = BRICK_PATH_PREFIXES.firstOrNull { path.startsWith(it) }
            return if (hit != null) {
                pathVerdict(hit, "会写入")
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
            return evaluateCommand(command)
        }

        // 其余工具（含 set_setting / app_state_control / 应用数据读写）一律放行。
        return Verdict.Safe
    }

    /**
     * 命令级判定，分三步：
     * 1. 命中「本身就是破坏动作」的片段 → 拦（与路径无关）。
     * 2. **纯只读命令 → 放行**（这是这次放宽的核心：`ls /system` 不该被拦）。
     * 3. 有写动词 **且** 涉及敏感路径 → 拦。
     */
    private fun evaluateCommand(command: String): Verdict {
        val normalized = command.lowercase()

        // ① 破坏型片段：无条件拦
        DESTRUCTIVE_FRAGMENTS.firstOrNull { normalized.contains(it) }?.let { hit ->
            return Verdict.danger(
                rule = "变砖级命令片段",
                reason = "「$hit」本身就是破坏动作",
                impact = "会改写分区或启动链",
                recovery = "撤不了，只能刷机",
                severity = Severity.CRITICAL,
            )
        }

        // ①' 直接删根目录 / 清空整个分区：单独判，避免 "rm -rf /" 误伤 "rm -rf /sdcard/x"
        if (isRootWipe(normalized)) {
            return Verdict.danger(
                rule = "清空根目录或整个分区",
                reason = "会删掉根目录或整个分区",
                impact = "分区会被清空",
                recovery = "数据找不回，得重刷系统",
                severity = Severity.CRITICAL,
            )
        }

        // ② 判断是否为写操作。
        // 先把「>/dev/null」「2>&1」这类纯丢弃输出的重定向去掉，
        // 否则 `ls /system > /dev/null` 会被当成写操作去查敏感路径。
        val withoutNullRedirect = normalized
            .replace("> /dev/null", " ")
            .replace(">/dev/null", " ")
            .replace("2>&1", " ")
            .replace("2> /dev/null", " ")
        val isWrite = WRITE_VERBS.any { withoutNullRedirect.contains(it) }

        // ③ 纯只读：只要命中读动词、又没有任何写动词，就放行
        if (!isWrite && READ_ONLY_VERBS.any { normalized.contains(it) }) {
            return Verdict.Safe
        }

        // ④ 写操作 + 敏感路径 = 危险。这里必须**逐段**判：
        // 整条命令里既有 `rm .../x`，另一段又只是 `grep ... /data/adb/service.d/...`（只读引用）时，
        // 不算「写启动链」。2026-11-07 实测误报过：删自己建的目录 + 末尾只读 grep 启动链脚本被拦。
        if (isWrite) {
            for (segment in COMMAND_SEGMENT_SEPARATORS.split(withoutNullRedirect)) {
                if (WRITE_VERBS.none { segment.contains(it) }) continue
                SENSITIVE_PATHS.firstOrNull { segment.contains(it) }?.let { hit ->
                    return pathVerdict(hit, "命令会写入")
                }
            }
        }

        // ⑤ 其它一律放行（含所有只读命令、以及 /sdcard、/data/data 等可恢复位置）
        return Verdict.Safe
    }

    /**
     * 是否在删根目录 / 整个分区。
     *
     * 只认这几种形态（用正则而不是子串，是因为子串会误伤）：
     * 直接删根目录、以及删整个分区的形式、`rm -rf /system`、`rm -rf /storage`。
     * 注意 `/sdcard`、`/system/app` 这种**带子路径**的不算 —— 那是正常清理。
     */
    private fun isRootWipe(normalized: String): Boolean {
        // 不用正则（Kotlin 字符串里的 $ 转义太容易出错），直接按 token 判断：
        // 找出 `rm -rf X` 里的 X，看它是不是根目录或整个分区。
        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        val rmIndex = tokens.indexOfFirst { it == "rm" }
        if (rmIndex < 0) return false
        // rm 后面紧跟的都是选项（-rf / -r 等），第一个非选项就是目标
        val target = tokens.drop(rmIndex + 1).firstOrNull { !it.startsWith("-") } ?: return false
        // `rm -rf /` 的 target 是 "/"，trimEnd 后会变成空串，所以先判原始值
        if (target == "/" || target == "/*" || target == "*") return true
        val cleaned = target.trimEnd('/')
        return cleaned in WIPE_TARGETS
    }

    /**
     * 按命中的路径生成判定。
     *
     * `hit` 一定来自 [BOOT_CHAIN_PATHS] 或 [SYSTEM_PARTITION_PATHS] 其中之一，
     * 所以直接判归属即可：启动链能靠删文件恢复（WARNING），系统分区不能（CRITICAL）。
     */
    private fun pathVerdict(hit: String, what: String): Verdict =
        if (hit in BOOT_CHAIN_PATHS) {
            Verdict.danger(
                rule = "写入系统启动链目录",
                reason = "$what「$hit」",
                impact = "写坏可能开不了机",
                recovery = "删掉写进去的文件就能恢复",
                severity = Severity.WARNING,
            )
        } else {
            Verdict.danger(
                rule = "写入系统分区",
                reason = "$what「$hit」",
                impact = "改的是系统分区本身",
                recovery = "不好撤，可能要线刷整机",
                severity = Severity.CRITICAL,
            )
        }

    /** 删掉这些目录本身 = 清空整个分区，属于危险操作。 */
    private val WIPE_TARGETS = setOf(
        "/data", "/system", "/vendor", "/product", "/my_product", "/odm", "/boot", "/storage",
    )
}

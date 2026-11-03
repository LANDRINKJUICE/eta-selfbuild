package io.github.mangi.eta.agent.terminal

/**
 * 跨环境路径提示。
 *
 * 为什么需要它（2026-11-03 实测）：Eta 有两个执行环境，命名空间不通用 ——
 *   - `environment=android`：看得到 `/data/data`、`/sdcard`，**看不到 `/workspace`**
 *   - `environment=linux`（Debian chroot）：看得到 `/workspace`、`/root`，**看不到 `/data/data`**
 *
 * 而我在一个会话里来回切。结果是同一个错误反复出现：
 *   `cp: cannot stat '/workspace/...': No such file or directory`（在 android 环境）
 *   `cat: can't open '/data/data/...'`（在 linux 环境）
 * 每踩一次就浪费一轮，因为 stderr 只说「文件不存在」，不说「你走错环境了」。
 *
 * 这个对象只做一件事：从 stderr 里认出这种错，并指出该切到哪个环境。
 * **纯函数、不做 IO**：不真的去 stat，避免每次命令失败都多跑一次检查。
 * 判断依据是路径形态 —— 两个环境的根目录集合完全不重叠，靠这个就够准。
 */
internal object EnvironmentPathHint {

    /** Linux（Debian chroot）环境里才存在的根目录。 */
    private val LINUX_ROOTS = listOf("/workspace", "/root", "/opt/", "/etc/", "/usr/", "/var/")

    /** Android 宿主里才存在的根目录。 */
    private val ANDROID_ROOTS = listOf("/data/data", "/data/adb", "/data/local", "/sdcard", "/storage", "/system/", "/vendor/")

    /** 常见「路径不存在」的 stderr 形态，捕获其中的路径。 */
    private val MISSING_PATH_PATTERNS = listOf(
        Regex("""(?:cp|cannot stat|stat):?\s*(?:cannot stat\s*)?['"]?(\/[^'":\s]+)"""),
        Regex("""can't (?:open|cd to|stat)\s*['"]?(\/[^'":\s]+)"""),
        Regex("""(?:ls|cat|stat|cd):\s*(\/[^'":\s]+):\s*No such file"""),
        Regex("""No such file or directory:\s*['"]?(\/[^'":\s]+)"""),
    )

    /**
     * @param environment 当前执行环境（"android" / "linux"）
     * @param stderr 命令的 stderr
     * @return 给模型看的一行提示；不需要提示时返回 null
     */
    internal fun hint(environment: String, stderr: String): String? {
        if (stderr.isBlank()) return null
        val path = extractMissingPath(stderr) ?: return null
        val env = environment.lowercase()

        return when {
            env == "android" && isLinuxPath(path) ->
                "路径 $path 属于 Linux 环境，当前是 android。改用 environment=\"linux\" 重试（两边的 /workspace 与 /data/local/tmp/eta 是同一物理目录）。"

            env == "linux" && isAndroidPath(path) ->
                "路径 $path 只在 Android 宿主可见，Debian chroot 里看不到。改用 environment=\"android\"，或先把它 cp 到 /workspace/tmp/ 再读。"

            else -> null
        }
    }

    /** 从 stderr 里抽出第一个「看起来是绝对路径且不存在」的路径。 */
    internal fun extractMissingPath(stderr: String): String? {
        for (pattern in MISSING_PATH_PATTERNS) {
            val found = pattern.find(stderr)?.groupValues?.getOrNull(1)
            if (!found.isNullOrBlank()) return found.trimEnd('.', ',', ')')
        }
        return null
    }

    internal fun isLinuxPath(path: String): Boolean =
        LINUX_ROOTS.any { path == it.trimEnd('/') || path.startsWith(it) }

    internal fun isAndroidPath(path: String): Boolean =
        ANDROID_ROOTS.any { path == it.trimEnd('/') || path.startsWith(it) }
}

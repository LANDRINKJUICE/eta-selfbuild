package io.github.mangi.eta.agent.terminal

/**
 * 把终端失败翻译成**有诊断价值的错误码**。
 *
 * 背景（2026-11-04 实测）：`terminal` 失败时信封里只有 `status:"failed"`，没有 `code`，
 * 于是 [io.github.mangi.eta.agent.skill.SkillErrorJournal] 只能记下 `failed` ——
 * 61 条失败记录里 32 条是这种毫无信息的字符串，翻日志学不到任何东西。
 *
 * 这里按退出码 + stderr 关键词给细分码。刻意保持纯函数、无 Android 依赖，方便单测。
 *
 * **只用于诊断，不改变工具行为**：`ok` / `status` / `exit_code` 语义完全不动。
 */
internal object TerminalExitClassifier {

    /** stderr 里出现这些就判成对应类别；顺序即优先级（先匹配更具体的）。 */
    private val STDERR_RULES: List<Pair<String, String>> = listOf(
        "Read-only file system" to "read_only_fs",
        "Permission denied" to "permission_denied",
        "Operation not permitted" to "permission_denied",
        "can't cd to" to "no_such_path",
        "No such file or directory" to "no_such_path",
        "not found" to "command_not_found",
        "command not found" to "command_not_found",
        "Text file busy" to "text_file_busy",
        "No space left on device" to "no_space",
        "Argument list too long" to "argument_too_long",
        "Broken pipe" to "broken_pipe",
    )

    /**
     * @param exitCode 进程退出码；-2 表示本项目的超时约定
     * @param timedOut 是否因超时被终止
     * @param stderr 标准错误（可为空串）
     * @return 成功返回 `ok`，失败返回细分码
     */
    fun classify(exitCode: Int, timedOut: Boolean, stderr: String): String {
        if (timedOut || exitCode == TIMEOUT_EXIT_CODE) return "timeout"
        if (exitCode == 0) return "ok"
        if (exitCode == 127) return "command_not_found"
        if (exitCode == 126) return "permission_denied"
        val lowered = stderr.lowercase()
        STDERR_RULES.firstOrNull { lowered.contains(it.first.lowercase()) }?.let { return it.second }
        return "exit_$exitCode"
    }

    private const val TIMEOUT_EXIT_CODE = -2
}

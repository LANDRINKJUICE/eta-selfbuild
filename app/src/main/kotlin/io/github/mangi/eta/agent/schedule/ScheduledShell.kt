package io.github.mangi.eta.agent.schedule

import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 定时任务里「跑工作区脚本」这一层的公共部分。
 *
 * 为什么单独抽出来：
 * 1. [ScheduledActionRunner] 里的动作越来越多，每个都自己拼 `sh -c` 容易各写各的；这里统一规定
 *    **路径怎么来、超时多长、失败怎么描述**，动作那边只剩业务表达。
 * 2. 工作区根目录不写死：先读 [TerminalRuntime.ROOT_WORKSPACE_PATH]（终端侧认定的 root 工作区），
 *    没有就退回和终端一样的 `/data/local/tmp/eta`。换路径只改这一处。
 *
 * 执行器选择（为什么不用 [io.github.mangi.eta.agent.device.BoundedRootCommandExecutor]）：
 * 那个执行器是给「模型发起的单条命令」用的，硬限制 30 秒、且强制走 `su`；定时任务里的快照、
 * 备份都要跑几十秒到几分钟，放不进去。所以这里用普通 [ProcessBuilder] 起 `/system/bin/sh`，
 * 在需要 root 时才自己加 `su -c`（与执行器同样的 `id -u` 前置检查，避免把「没授权」误判成「脚本失败」）。
 */
internal object ScheduledShell {
    /** 与 [TerminalRuntime.ROOT_WORKSPACE_PATH] 一致；放成常量便于日志与失败文案引用同一个值。 */
    const val WORKSPACE = "/data/local/tmp/eta"

    private const val SYSTEM_SHELL = "/system/bin/sh"
    private const val STDERR_TAIL_CHARS = 120

    /** 工作区里的固定位置，动作里不再重复拼字符串。 */
    object Paths {
        val workspace: File get() = File(TerminalRuntime.ROOT_WORKSPACE_PATH.ifBlank { WORKSPACE })
        val tools: File get() = File(workspace, "04-工具-tools")
        val analysis: File get() = File(workspace, "05-系统分析-sysanalyze")
        val tmp: File get() = File(workspace, "tmp")

        fun tool(name: String): File = File(tools, name)

        fun analysisFile(name: String): File = File(analysis, name)
    }

    fun readonlyHint(reason: String): String = "未执行：$reason"

    fun failure(reason: String): String = "执行失败：$reason"

    /** 一句话说明「为什么没跑」，带上工作区路径，方便用户自己去补文件。 */
    fun missingScript(script: File): String = "未执行：找不到脚本 ${script.absolutePath}"

    /**
     * 同步执行一条 shell 命令，返回完整结果。**不抛异常**：失败一律折成 [Outcome]，由调用方决定文案。
     *
     * @param command 交给 `sh -c` 的脚本体；只能由代码拼接，不得直接拼用户或模型输入。
     * @param asRoot 是否要求 root 身份运行（读 /data/adb、/data/data 的动作必须为 true）。
     */
    fun run(
        command: String,
        timeoutSeconds: Long,
        asRoot: Boolean,
    ): Outcome {
        if (asRoot && !RootAccess.isGranted) {
            return Outcome(error = "ROOT_REQUIRED", stderr = "Root 未授权")
        }
        val argv = if (asRoot) {
            // 与 BoundedRootCommandExecutor 同样的做法：先用 id -u 确认拿到了 root，
            // 否则 su 可能静默降级成 app 身份，脚本会以「路径不存在」这种误导性错误失败。
            arrayOf("su", "-c", "[ \"\$(id -u)\" = 0 ] || exit 77; $command")
        } else {
            arrayOf(SYSTEM_SHELL, "-c", command)
        }
        val startedAt = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(*argv)
                .redirectErrorStream(false)
                .start()
            process.outputStream.close()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                runCatching { process.destroyForcibly() }
                return Outcome(
                    exitCode = -1,
                    timedOut = true,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }
            // 进程已结束，管道里剩下的内容可以直接读，不会再阻塞。
            val stdout = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            val stderr = runCatching { process.errorStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            Outcome(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr,
                durationMs = System.currentTimeMillis() - startedAt,
            )
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("Scheduled shell failed to start: type=${failure.javaClass.simpleName}")
            Outcome(error = "LAUNCH_FAILED", stderr = failure.javaClass.simpleName)
        }
    }

    /** 一次的完整结果；[ok] 只在「没超时、没启动错误、退出码为 0」时为真。 */
    data class Outcome(
        val exitCode: Int = 0,
        val stdout: String = "",
        val stderr: String = "",
        val timedOut: Boolean = false,
        val durationMs: Long = 0L,
        val error: String = "",
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut && error.isBlank()

        /** 失败时能塞进通知的一小段原因：优先 stderr 尾行，其次退出码，最后错误码。 */
        val reason: String
            get() = when {
                timedOut -> "超时（${durationMs / 1000} 秒未结束）"
                error == "ROOT_REQUIRED" -> "Root 未授权"
                error.isNotBlank() -> "无法启动 shell（$error）"
                exitCode == 77 -> "Root 授权被拒"
                else -> {
                    val detail = stderr.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .lastOrNull()
                    if (detail.isNullOrBlank()) "退出码 $exitCode" else detail.take(STDERR_TAIL_CHARS)
                }
            }
    }
}

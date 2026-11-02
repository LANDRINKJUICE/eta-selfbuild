package io.github.mangi.eta.agent.files

import io.github.mangi.eta.agent.device.RootAccess
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * 文件管理器专用的 shell 执行器。
 *
 * 为什么不复用 `BoundedRootCommandExecutor`：它面向 Agent 工具调用，输出上限 30 秒超时，
 * 而文件管理器需要更长超时（大目录、复制大文件）且只在 IO 线程调用，语义更简单。
 *
 * 有 Root 走 `su -c`，没有 Root 就直接 `/system/bin/sh -c`（只能访问有权限的目录）。
 * **不吞异常**：失败时返回空串或带错误信息的字符串，由上层判定。
 */
internal object FileShellRunner {

    private const val TIMEOUT_SECONDS = 30L
    private const val MAX_OUTPUT_CHARS = 2_000_000

    /** 执行一条命令并返回合并后的输出。 */
    fun run(command: String): String {
        val useRoot = runCatching { RootAccess.isGranted }.getOrDefault(false)
        val argv = if (useRoot) {
            arrayOf("su", "-c", command)
        } else {
            arrayOf("/system/bin/sh", "-c", command)
        }
        return runCatching {
            val process = ProcessBuilder(*argv)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader: BufferedReader ->
                var line = reader.readLine()
                while (line != null) {
                    if (output.length < MAX_OUTPUT_CHARS) {
                        output.append(line).append('\n')
                    }
                    line = reader.readLine()
                }
            }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching "命令超时（${TIMEOUT_SECONDS}s）"
            }
            output.toString()
        }.getOrElse { failure ->
            "执行失败：${failure.javaClass.simpleName}"
        }
    }

    /** 当前是否具备 Root（决定能否浏览 /data 等目录）。 */
    fun hasRoot(): Boolean = runCatching { RootAccess.isGranted }.getOrDefault(false)
}

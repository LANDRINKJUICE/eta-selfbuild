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
 * **踩过的坑（不要再退化）**：这里以前直接读取 `RootAccess.isGranted` 缓存。
 * 该缓存只有在别处调用过初始化才有值；文件管理器作为独立入口打开时缓存可能是 false，
 * 于是退化成普通 `sh -c`，结果是 `/data` 等目录显示为空、看起来像"点进去什么都没有"。
 * 现在改为：缓存未初始化时**现场执行一次 `su -c id -u` 判定**，并把结果写回缓存。
 */
internal object FileShellRunner {

    private const val TIMEOUT_SECONDS = 30L
    private const val MAX_OUTPUT_CHARS = 2_000_000

    /** 现场探测的 Root 结果；null 表示还没探测过。 */
    @Volatile
    private var rootProbe: Boolean? = null

    /** 执行一条命令并返回合并后的输出。 */
    fun run(command: String): String = runWith(command, resolveUseRoot(), TIMEOUT_SECONDS)

    /** 复制大文件这类长任务用更长的超时。 */
    fun runLong(command: String): String = runWith(command, resolveUseRoot(), LONG_TIMEOUT_SECONDS)

    fun runWith(command: String, useRoot: Boolean, timeoutSeconds: Long = TIMEOUT_SECONDS): String {
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
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching "命令超时（${timeoutSeconds}s）"
            }
            output.toString()
        }.getOrElse { failure ->
            "执行失败：${failure.javaClass.simpleName}"
        }
    }

    /**
     * 是否需要 Root。
     *
     * 先看缓存；缓存没探测过就现场跑一次 `su -c id -u`：
     * 能拿到 `0` 才算有 Root。这样即便 RootAccess 从未初始化也不会误判成无 Root。
     */
    fun resolveUseRoot(): Boolean {
        runCatching { RootAccess.isGranted }.getOrDefault(false).let { if (it) return true }
        rootProbe?.let { return it }
        val granted = runCatching {
            val process = ProcessBuilder("su", "-c", "id -u")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            text.trim() == "0"
        }.getOrDefault(false)
        rootProbe = granted
        return granted
    }

    /** 当前是否具备 Root（决定能否浏览 /data 等目录）。 */
    fun hasRoot(): Boolean = resolveUseRoot()

    const val LONG_TIMEOUT_SECONDS = 600L
}

package io.github.mangi.eta.agent.files

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件管理器的核心操作：列目录、删除（进回收站）、复制、移动、重命名、新建目录。
 *
 * 三条设计原则：
 * 1. **批量合并 shell**：目录列举一次调用拿完，而不是每个文件 stat 一次
 *    （root 目录下逐文件调用会慢到不可用）。
 * 2. **删除进回收站**：先移到 `.trash/<时间戳>/<原名>`，7 天后才真删。
 * 3. **路径一律转义**：文件名可能含空格、中文、引号，拼接命令时必须安全引用。
 */
internal object FileManagerOps {

    /** 一次目录列举的结果项。 */
    internal data class Entry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
        val sizeBytes: Long,
        val modifiedAtMillis: Long,
        val permissions: String,
    )

    internal data class Listing(
        val path: String,
        val entries: List<Entry>,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    internal data class OpResult(val ok: Boolean, val message: String) {
        companion object {
            fun success(message: String) = OpResult(true, message)
            fun failure(message: String) = OpResult(false, message)
        }
    }

    /** 回收站里的一个条目，用于「恢复」。 */
    internal data class TrashItem(
        val originalPath: String,
        val trashedPath: String,
        val trashedAtMillis: Long,
    )

    // ------------------------------------------------------------ 列目录

    /**
     * 列目录：用 `stat` 逐项输出**固定分隔符**，一次 shell 调用拿完。
     *
     * 为什么不用 `ls -la` 解析（踩过）：
     * 不同实现的日期格式不一样 —— GNU/toybox 是 `Mar 25 17:24`（3 段），
     * 而这台机器的 busybox 是 `2026-03-25 17:24`（2 段）。按固定列号解析在另一种格式下
     * 会整体错位：条目被丢弃、名字被截断（"Payload Dumper" 变成 "Dumper"）。
     * `stat -c '%A|%s|%Y|%n'` 用 `|` 分隔且名字在最后，两种实现下都稳定。
     *
     * 用 for 循环而不是通配符展开，是为了避开命令行长度上限，并顺带覆盖隐藏文件。
     */
    fun list(shell: (String) -> String, path: String): Listing {
        val quoted = shellQuote(path)
        val command = buildString {
            append("cd ").append(quoted).append(" 2>&1 || exit 1; ")
            append("for f in * .[!.]*; do ")
            append("[ -e \"\$f\" ] || continue; ")
            append("stat -c '%A|%s|%Y|%n' \"\$f\" 2>/dev/null; ")
            append("done")
        }
        val output = runCatching { shell(command) }.getOrElse { failure ->
            return Listing(path, emptyList(), "读取失败：${failure.javaClass.simpleName}")
        }
        if (output.contains("No such file or directory")) {
            return Listing(path, emptyList(), "目录不存在")
        }
        if (output.contains("Permission denied")) {
            return Listing(path, emptyList(), "没有权限（需要 Root）")
        }
        val entries = output.lineSequence()
            .mapNotNull { parseStatLine(it, path) }
            .sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .toList()
        return Listing(path, entries)
    }

    /** 解析一行 `权限|大小|mtime|名字`；名字在最后一段，允许含空格与 `|`。 */
    private fun parseStatLine(line: String, parent: String): Entry? {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank() || !trimmed.contains('|')) return null
        val parts = trimmed.split('|', limit = 4)
        if (parts.size < 4) return null
        val permissions = parts[0]
        if (permissions.length < 10) return null
        val name = parts[3].trim()
        if (name.isBlank() || name == "." || name == "..") return null
        val isLink = permissions.startsWith("l")
        return Entry(
            name = name,
            path = if (parent.endsWith("/")) "$parent$name" else "$parent/$name",
            isDirectory = permissions.startsWith("d"),
            isSymlink = isLink,
            sizeBytes = parts[1].toLongOrNull() ?: 0L,
            modifiedAtMillis = (parts[2].toLongOrNull() ?: 0L) * 1000L,
            permissions = permissions,
        )
    }

    // ------------------------------------------------------------ 删除（进回收站）

    /**
     * 删除：**先移到回收站**，不是直接 rm。
     *
     * @return 成功时 message 里带回收站路径，便于「撤销」提示。
     */
    fun moveToTrash(shell: (String) -> String, paths: List<String>): OpResult {
        if (paths.isEmpty()) return OpResult.failure("没有选中任何文件")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val bucket = "${FilePathRisk.TRASH_DIR}/$stamp"
        val failures = mutableListOf<String>()
        paths.forEach { path ->
            val target = "$bucket/${path.trimEnd('/').substringAfterLast('/')}"
            val result = runCatching {
                shell("mkdir -p ${shellQuote(bucket)} && mv -n ${shellQuote(path)} ${shellQuote(target)} 2>&1")
            }
            if (result.isFailure) {
                failures.add("$path（${result.exceptionOrNull()?.javaClass?.simpleName}）")
                return@forEach
            }
            if (result.getOrDefault("").contains("Permission denied") ||
                result.getOrDefault("").contains("Read-only")
            ) {
                failures.add("$path（权限不足）")
            }
        }
        return if (failures.isEmpty()) {
            OpResult.success("已移到回收站：$bucket（${FilePathRisk.TRASH_RETENTION_DAYS} 天后自动清除）")
        } else {
            OpResult.failure("部分失败：${failures.joinToString("、")}")
        }
    }

    /** 清空超过保留期的回收站内容。 */
    fun purgeTrash(shell: (String) -> String): OpResult {
        val cmd = "find ${shellQuote(FilePathRisk.TRASH_DIR)} -mindepth 1 -maxdepth 1 -type d -mtime +${FilePathRisk.TRASH_RETENTION_DAYS} -exec rm -rf {} + 2>&1"
        return runCatching {
            shell(cmd)
            OpResult.success("已清理过期回收站内容（超过 ${FilePathRisk.TRASH_RETENTION_DAYS} 天）")
        }.getOrElse { OpResult.failure("清理失败：${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ 复制 / 移动 / 重命名

    fun copy(shell: (String) -> String, sources: List<String>, targetDir: String): OpResult =
        runBatch(shell, sources) { src ->
            "cp -a ${shellQuote(src)} ${shellQuote("$targetDir/${src.trimEnd('/').substringAfterLast('/')}")} 2>&1"
        }.let { failures ->
            if (failures.isEmpty()) OpResult.success("已复制 ${sources.size} 项到 ${targetDir.takeLast(40)}")
            else OpResult.failure("部分失败：${failures.joinToString("、")}")
        }

    fun move(shell: (String) -> String, sources: List<String>, targetDir: String): OpResult =
        runBatch(shell, sources) { src ->
            "mv -n ${shellQuote(src)} ${shellQuote("$targetDir/${src.trimEnd('/').substringAfterLast('/')}")} 2>&1"
        }.let { failures ->
            if (failures.isEmpty()) OpResult.success("已移动 ${sources.size} 项")
            else OpResult.failure("部分失败：${failures.joinToString("、")}")
        }

    fun rename(shell: (String) -> String, path: String, newName: String): OpResult {
        if (newName.isBlank() || newName.contains('/')) return OpResult.failure("文件名不合法")
        val parent = path.substringBeforeLast('/', "")
        val target = if (parent.isEmpty()) newName else "$parent/$newName"
        return runCatching {
            shell("mv -n ${shellQuote(path)} ${shellQuote(target)} 2>&1")
            OpResult.success("已重命名为「$newName」")
        }.getOrElse { OpResult.failure("重命名失败：${it.javaClass.simpleName}") }
    }

    fun createDirectory(shell: (String) -> String, parent: String, name: String): OpResult {
        if (name.isBlank() || name.contains('/')) return OpResult.failure("目录名不合法")
        val target = if (parent.endsWith("/")) "$parent$name" else "$parent/$name"
        return runCatching {
            shell("mkdir -p ${shellQuote(target)} 2>&1")
            OpResult.success("已创建「$name」")
        }.getOrElse { OpResult.failure("创建失败：${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ 复用工具

    private fun runBatch(
        shell: (String) -> String,
        sources: List<String>,
        commandFor: (String) -> String,
    ): List<String> {
        if (sources.isEmpty()) return listOf("没有选中任何文件")
        val failures = mutableListOf<String>()
        sources.forEach { src ->
            val result = runCatching { shell(commandFor(src)) }
            if (result.isFailure) {
                failures.add(src)
                return@forEach
            }
            val output = result.getOrDefault("")
            if (output.contains("Permission denied") || output.contains("No such file")) failures.add(src)
        }
        return failures
    }

    /** POSIX shell 安全引用：单引号包裹，内部的单引号用 '\'' 断开。 */
    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /** 人类可读大小。 */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

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
     * 列目录：一次 `ls -l` 拿完所有条目。
     *
     * 用 `-1`（每行一个名字）配合两次调用太重，所以用 `ls -la` 解析列格式；
     * toybox 与 GNU 的输出列顺序一致（权限 链接数 属主 属组 大小 月 日 时/年 名字）。
     */
    fun list(shell: (String) -> String, path: String): Listing {
        val quoted = shellQuote(path)
        // 先确认是目录
        val output = runCatching { shell("ls -la $quoted 2>&1") }.getOrElse { failure ->
            return Listing(path, emptyList(), "读取失败：${failure.javaClass.simpleName}")
        }
        if (output.contains("No such file or directory")) {
            return Listing(path, emptyList(), "目录不存在")
        }
        if (output.contains("Permission denied")) {
            return Listing(path, emptyList(), "没有权限（需要 Root）")
        }
        val entries = output.lineSequence()
            .drop(1) // 第一行是 total
            .mapNotNull { parseLsLine(it, path) }
            .filter { it.name != "." && it.name != ".." }
            .toList()
        // 目录在前，然后按名称排序 —— 和常见文件管理器一致。
        return Listing(path, entries.sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() }))
    }

    private fun parseLsLine(line: String, parent: String): Entry? {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank()) return null
        // 权限 链接数 属主 属组 大小 月 日 时间 名字...
        // 拆成 9 段：前 8 段是固定列，第 9 段是「时间列之后的所有内容」——
        // 名字本身可能含空格，所以第 9 段要整段保留，不能再去 substringAfter(" ")。
        val parts = trimmed.split(Regex("\\s+"), limit = 9)
        if (parts.size < 9) return null
        val permissions = parts[0]
        if (permissions.length < 10) return null
        val size = parts[4].toLongOrNull() ?: 0L
        val name = parts[8].trim()
        if (name.isBlank()) return null
        val isDir = permissions.startsWith("d")
        val isLink = permissions.startsWith("l")
        // 符号链接形如 "name -> target"，取箭头前
        val displayName = if (isLink) name.substringBefore(" -> ") else name
        return Entry(
            name = displayName,
            path = if (parent.endsWith("/")) "$parent$displayName" else "$parent/$displayName",
            isDirectory = isDir,
            isSymlink = isLink,
            sizeBytes = size,
            modifiedAtMillis = parseLsTime(parts[5], parts[6], parts[7]),
            permissions = permissions,
        )
    }

    /** `ls` 的时间列：近半年显示 "月 日 时:分"，更早显示 "月 日 年份"。解析不了就返回 0。 */
    private fun parseLsTime(month: String, day: String, timeOrYear: String): Long = runCatching {
        val now = java.util.Calendar.getInstance()
        val year = if (timeOrYear.contains(":")) now.get(java.util.Calendar.YEAR) else timeOrYear.toInt()
        val (hour, minute) = if (timeOrYear.contains(":")) {
            timeOrYear.split(":")[0].toInt() to timeOrYear.split(":")[1].toInt()
        } else {
            0 to 0
        }
        val monthIndex = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        ).indexOf(month)
        if (monthIndex < 0) return 0L
        java.util.Calendar.getInstance().apply {
            clear()
            set(year, monthIndex, day.toIntOrNull() ?: 1, hour, minute)
        }.timeInMillis
    }.getOrDefault(0L)

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

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

    // ------------------------------------------------------------ 打开 / 预览

    /** 文本预览上限：超过就只显示前 256 KiB，避免大文件把界面卡死。 */
    private const val TEXT_PREVIEW_LIMIT = 256 * 1024

    /** 编辑器能打开的上限：再大就不让编辑，避免内存和命令行压力。 */
    const val EDIT_LIMIT_BYTES = 512 * 1024

    /** 判断是不是"能当文本看"的扩展名（白名单，宁可少判也不要乱码）。 */
    fun looksLikeText(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "txt", "md", "markdown", "log", "json", "xml", "yml", "yaml", "ini", "conf", "cfg",
            "prop", "properties", "sh", "bash", "rc", "kt", "kts", "java", "js", "ts", "py",
            "c", "h", "cpp", "hpp", "go", "rs", "html", "htm", "css", "csv", "tsv", "sql",
            "gradle", "toml", "gitignore", "pro", "mk", "env", "uuid", "dat", "list", "lock",
        )
    }

    /** 图片扩展名：交给系统图片查看器的受支持类型。 */
    fun looksLikeImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
        )

    /** 音视频扩展名：交给系统播放器。 */
    fun looksLikeMedia(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf(
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "amr",
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "ts",
        )

    /** 压缩包扩展名：菜单里给"解压到此处"。 */
    fun looksLikeArchive(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("zip", "jar", "apk", "xapk", "kmz")

    /**
     * 用系统 `file` 命令判断是否文本。
     *
     * 为什么需要它（用户真实案例）：`.uuid`、`hosts` 这类**无扩展名**文件，
     * 靠扩展名白名单判断不出来，点了只能弹属性 —— 用户会觉得"看不到内容"。
     * `file -b` 会给出 "ASCII text" / "UTF-8 Unicode text" / "Zip archive data" 这类结论，
     * 比猜扩展名可靠。实测本机 /system/bin/file 可用。
     */
    fun isTextFile(shell: (String) -> String, path: String): Boolean {
        val out = runCatching { shell("file -b ${shellQuote(path)} 2>/dev/null") }.getOrDefault("")
        val lower = out.lowercase()
        if (lower.contains("text") || lower.contains("empty")) return true
        // 无 file 命令时退回按扩展名判断，避免功能直接失效
        return out.isBlank() && looksLikeText(path.substringAfterLast('/'))
    }

    /**
     * 读**完整**文本用于编辑器（不是预览）。
     *
     * 超过 [EDIT_LIMIT_BYTES] 时截断并给出提示，避免把编辑器卡死。
     */
    fun readForEdit(shell: (String) -> String, path: String): Pair<OpResult, Boolean> {
        val out = runCatching {
            shell("head -c ${EDIT_LIMIT_BYTES + 1} ${shellQuote(path)} 2>&1")
        }.getOrElse { return OpResult.failure("读取失败：${it.javaClass.simpleName}") to false }
        if (out.contains("Permission denied")) return OpResult.failure("没有读取权限") to false
        if (out.contains("No such file")) return OpResult.failure("文件不存在") to false
        return if (out.length > EDIT_LIMIT_BYTES) {
            OpResult.success(out.take(EDIT_LIMIT_BYTES)) to true
        } else {
            OpResult.success(out) to false
        }
    }

    /**
     * 保存文本（把编辑内容写回文件）。
     *
     * 为什么走 base64 而不是直接 `printf '%s' '内容'`：
     * 内容里可能有单引号、反引号、`$`、换行 —— 直接拼进命令会被 shell 解释，
     * 轻则内容损坏，重则执行到别的东西（注入）。
     * base64 字符集只有 A-Za-z0-9+/=，拼进命令**无需转义**；再按块追加，
     * 避免单条命令超长（内核单个参数上限约 128KB）。
     */
    fun writeText(shell: (String) -> String, path: String, content: String): OpResult {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val quoted = shellQuote(path)
        // 先清空，避免"新内容比旧内容短"时尾部残留旧字节
        val clearFailed = runCatching { shell(": > $quoted 2>&1") }.isFailure
        if (clearFailed) return OpResult.failure("打开文件失败")

        val chunkRawBytes = 32 * 1024
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkRawBytes, bytes.size)
            // java.util.Base64（不是 android.util.Base64）：JVM 单测里也能跑，不依赖 Android 运行时
            val chunk = java.util.Base64.getEncoder()
                .encodeToString(bytes.copyOfRange(offset, end))
            val result = runCatching {
                shell("printf '%s' '$chunk' | base64 -d >> $quoted 2>&1")
            }.getOrElse { return OpResult.failure("写入失败：${it.javaClass.simpleName}") }
            if (result.contains("Permission denied") || result.contains("Read-only")) {
                return OpResult.failure("没有写入权限")
            }
            if (result.contains("No space left")) return OpResult.failure("存储空间不足")
            offset = end
        }
        return OpResult.success("已保存（${formatSize(bytes.size.toLong())}）")
    }

    /** 读文本用于预览（沿用旧接口，编辑用 [readForEdit]）。 */
    fun readTextPreview(shell: (String) -> String, path: String): OpResult =
        readForEdit(shell, path).first

    /** 新建空文件。 */
    fun createFile(shell: (String) -> String, parent: String, name: String): OpResult {
        if (name.isBlank() || name.contains('/')) return OpResult.failure("文件名不合法")
        val target = if (parent.endsWith("/")) "$parent$name" else "$parent/$name"
        return runCatching {
            shell(": > ${shellQuote(target)} 2>&1")
            OpResult.success("已新建「$name」")
        }.getOrElse { OpResult.failure("创建失败：${it.javaClass.simpleName}") }
    }

    /** 计算 MD5（属性面板用）。 */
    fun md5(shell: (String) -> String, path: String): String =
        runCatching {
            shell("md5sum ${shellQuote(path)} 2>/dev/null").trim().substringBefore(' ').trim()
        }.getOrDefault("")

    // ------------------------------------------------------------ 搜索

    internal data class SearchResult(
        val entries: List<Entry>,
        val truncated: Boolean,
        val error: String? = null,
    )

    /**
     * 按文件名递归搜索。
     *
     * 两个防护（都必要）：
     * 1. `-maxdepth` 限制层数，否则从 `/` 搜会遍历整个系统，几十秒都没结果。
     * 2. `head -n` 限制条数，避免一次返回几万行把界面和内存打爆。
     *
     * 关键词整体用单引号包住后交给 find，`*` 只是 find 的通配符（不是 shell 展开），
     * 所以 `$(...)`、`;`、反引号这些都不会被执行。
     */
    fun search(
        shell: (String) -> String,
        dir: String,
        keyword: String,
        maxResults: Int = 300,
        maxDepth: Int = 6,
    ): SearchResult {
        val kw = keyword.trim()
        if (kw.isEmpty()) return SearchResult(emptyList(), false, "请输入关键词")
        val pattern = shellQuote("*$kw*")
        val command = buildString {
            append("find ").append(shellQuote(dir))
            append(" -maxdepth ").append(maxDepth)
            append(" -iname ").append(pattern)
            append(" 2>/dev/null | head -n ").append(maxResults + 1)
            append(" | while IFS= read -r p; do stat -c '%A|%s|%Y|%n' \"\$p\" 2>/dev/null; done")
        }
        val out = runCatching { shell(command) }.getOrElse {
            return SearchResult(emptyList(), false, "搜索失败：${it.javaClass.simpleName}")
        }
        val all = out.lineSequence().mapNotNull { parseFindLine(it) }.toList()
        val truncated = all.size > maxResults
        return SearchResult(all.take(maxResults), truncated)
    }

    /**
     * 解析 `find` 结果行。
     *
     * 与 [parseStatLine] 的区别：这里 `stat -c '%n'` 拿到的是**完整路径**（find 传的就是全路径），
     * 所以不能把名字当相对名拼到父目录后面 —— 否则会拼出 `//sdcard/...` 这种错路径，
     * 而条目名会变成一整条路径（测试就是这么抓到它的）。
     */
    private fun parseFindLine(line: String): Entry? {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank() || !trimmed.contains('|')) return null
        val parts = trimmed.split('|', limit = 4)
        if (parts.size < 4) return null
        val permissions = parts[0]
        if (permissions.length < 10) return null
        val full = parts[3].trim()
        if (full.isBlank()) return null
        return Entry(
            name = full.trimEnd('/').substringAfterLast('/'),
            path = full,
            isDirectory = permissions.startsWith('d'),
            isSymlink = permissions.startsWith('l'),
            sizeBytes = parts[1].toLongOrNull() ?: 0L,
            modifiedAtMillis = (parts[2].toLongOrNull() ?: 0L) * 1000L,
            permissions = permissions,
        )
    }

    // ------------------------------------------------------------ 压缩包内容

    private val ARCHIVE_LINE = Regex("""^\s*(\d+)\s+\S+\s+\S+\s+(.+)$""")

    /**
     * 列出 zip/apk 内部条目（MT 里"查看 APK"能看包内文件，就是这个）。
     *
     * 用系统自带的 `unzip -l`（实测本机 /system/bin/unzip 可用），
     * 不引第三方库，也不把整个包解出来 —— 看 164MB 的 APK 也秒开。
     */
    fun listArchive(shell: (String) -> String, path: String, maxEntries: Int = 150): OpResult {
        val out = runCatching {
            shell("unzip -l ${shellQuote(path)} 2>&1 | head -n ${maxEntries + 8}")
        }.getOrElse { return OpResult.failure("读取失败：${it.javaClass.simpleName}") }
        if (out.contains("Permission denied")) return OpResult.failure("没有读取权限")
        if (out.contains("not a zip") || out.contains("End-of-central-directory")) {
            return OpResult.failure("不是有效的压缩包")
        }
        val items = out.lineSequence().mapNotNull { line ->
            ARCHIVE_LINE.find(line)?.let { m ->
                val size = m.groupValues[1].toLongOrNull() ?: 0L
                "$size|${m.groupValues[2].trim()}"
            }
        }.toList()
        if (items.isEmpty()) return OpResult.failure("压缩包是空的，或者格式不支持")
        val shown = items.take(maxEntries)
        val body = buildString {
            for (item in shown) {
                val size = item.substringBefore('|').toLongOrNull() ?: 0L
                val name = item.substringAfter('|')
                append(name).append("    ").append(formatSize(size)).append('\n')
            }
            if (items.size > maxEntries) {
                append("…还有 ${items.size - maxEntries} 项未显示")
            }
        }
        return OpResult.success("共 ${items.size} 项\n\n$body")
    }

    /** 属性面板内容：大小、时间、权限、属主、完整路径。 */
    fun describeEntry(entry: Entry): String {
        val time = if (entry.modifiedAtMillis > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(entry.modifiedAtMillis))
        } else {
            "未知"
        }
        return buildString {
            append("路径：").append(entry.path).append('\n')
            append("类型：").append(
                when {
                    entry.isSymlink -> "符号链接"
                    entry.isDirectory -> "目录"
                    else -> "文件"
                }
            ).append('\n')
            if (!entry.isDirectory) append("大小：").append(formatSize(entry.sizeBytes)).append('\n')
            append("修改：").append(time).append('\n')
            append("权限：").append(entry.permissions)
        }
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

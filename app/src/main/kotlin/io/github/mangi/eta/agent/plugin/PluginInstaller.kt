package io.github.mangi.eta.agent.plugin

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 导入结果：成功给出插件 id 与名字，失败给出**能看懂的原因**（直接显示给用户）。 */
internal sealed interface PluginImportResult {
    data class Ok(val id: String, val name: String) : PluginImportResult
    data class Failure(val message: String) : PluginImportResult
}

/**
 * 插件导入（v1.1：只支持 zip）。
 *
 * 安全底线（2026-11-08，四条都不许放松）：
 * 1. **路径穿越**：条目名出现 `..`、绝对路径、反斜杠 → 整个包拒收（zip slip 是这类功能最常见的洞）。
 * 2. **规模上限**：条目数 ≤ [MAX_ENTRIES]、解压总量 ≤ [MAX_TOTAL_BYTES]（防 zip 炸弹）。
 * 3. **先落地后生效**：一律先解到 `plugins/.staging-*`，manifest 解析通过、且 id 不冲突，才挪到正式目录。
 * 4. **导入 ≠ 授权**：授权状态存在偏好里，新 id 天然是"全关"，用户还得自己勾。
 */
internal object PluginInstaller {
    const val MAX_ENTRIES = 200
    const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
    private const val STAGING_PREFIX = ".staging-"
    private const val MAX_NAME_LENGTH = 200

    /** 条目名是否安全。纯函数，便于单测。 */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains('\\')) return false
        // 任何一段是 `..` 就拒 —— 不需要更聪明的判断
        return name.split('/').none { it == ".." }
    }

    fun importZip(context: Context, uri: Uri): PluginImportResult {
        val pluginsDir = PluginRepository.pluginsDir(context)
        if (!pluginsDir.exists() && !pluginsDir.mkdirs()) {
            return PluginImportResult.Failure("建不了插件目录")
        }
        val staging = File(pluginsDir, STAGING_PREFIX + System.currentTimeMillis())
        return try {
            // 2026-11-08 修（阻断级）：以前写成
            //   openInputStream(uri)?.use { extract(stream, staging) } ?: return Failure("打不开这个文件")
            // 而 extract() 的契约是「返回 null 表示成功」——于是**任何正常 zip 都被判成打不开**，
            // 插件永远装不进去。现在把「流打不开」和「解压失败」两种情况分开判。
            val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: return PluginImportResult.Failure("打不开这个文件")
            val extracted = runCatching { stream.use { extract(it, staging) } }.getOrElse { throwable ->
                return PluginImportResult.Failure("解压失败：${throwable.javaClass.simpleName}")
            }
            if (extracted != null) return extracted

            val root = locatePluginRoot(staging)
                ?: return PluginImportResult.Failure("zip 里找不到 plugin.json（要求放在根目录或唯一的一层子目录里）")
            val manifestFile = File(root, PluginRepository.MANIFEST_NAME)
            val raw = runCatching { manifestFile.readText() }.getOrElse {
                return PluginImportResult.Failure("读不到 plugin.json")
            }
            val manifest = when (val outcome = PluginManifestParser.parse(raw, root.absolutePath)) {
                is PluginParseOutcome.Failure -> return PluginImportResult.Failure(outcome.message)
                is PluginParseOutcome.Ok -> outcome.manifest
            }
            val destination = File(pluginsDir, manifest.id)
            if (destination.exists()) {
                return PluginImportResult.Failure("已经有一个叫「${manifest.id}」的插件了，先删掉再导入")
            }
            if (!root.renameTo(destination)) {
                // 同目录 rename 一般不会失败；失败时退回逐项移动，别让用户的包白解一遍。
                if (!root.copyRecursively(destination, overwrite = false)) {
                    // 2026-11-08 修：以前失败后不清理，半成品目录会被 scan() 判成 Broken，
                    // 而 Broken 条目在 UI 里进不去详情页、删不掉 —— 再导入同 id 就永久卡死。
                    runCatching { destination.deleteRecursively() }
                    return PluginImportResult.Failure("装不进去（移动文件失败）")
                }
            }
            // 目录名与 id 不一致时，rename 后的路径仍以 id 为准；这里再确认一次真的是这个插件
            if (!File(destination, PluginRepository.MANIFEST_NAME).isFile) {
                // 2026-11-08 复查：这个分支以前不清残留，正式目录会留下半成品，
                // 下次导入同 id 就永久报「已经有一个叫 X 的插件了」。
                runCatching { destination.deleteRecursively() }
                return PluginImportResult.Failure("装完之后找不到 plugin.json")
            }
            // 用户从界面导入的插件：**保持「装了但没启用」**。
            //
            // 与「Agent 直接放进插件目录」的路径刻意区分开（2026-11-08）：
            //  · 用户导入陌生 zip 时，仍需要自己点一下启用与授权 —— 这是他的信任判断；
            //  · Agent 自己装的插件走另一条路（见 PluginRepository 的默认值说明），
            //    装完即可用，不必让用户为 Agent 的决定再点两下。
            PluginRepository.setEnabled(context, manifest.id, false)
            PluginRepository.setGranted(context, manifest.id, emptySet())
            PluginImportResult.Ok(manifest.id, manifest.name)
        } finally {
            // 成功或失败都把临时目录清掉（成功时它已被 rename 走，exists 为 false）。
            if (staging.exists()) runCatching { staging.deleteRecursively() }
        }
    }

    /** 解压到 [target]；返回非 null 表示**已经失败**并给出原因。 */
    internal fun extract(stream: InputStream, target: File): PluginImportResult? {
        // 目录可能已存在（重试或调用方预建），mkdirs() 在已存在时返回 false ——
        // 不能把「已经有了」当成失败（2026-11-08 单测踩出来的）。
        if (!target.isDirectory && !target.mkdirs()) return PluginImportResult.Failure("建不了临时目录")
        var entries = 0
        var total = 0L
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!isSafeEntryName(name)) {
                    return PluginImportResult.Failure("包里有个不安全的路径：$name")
                }
                entries += 1
                if (entries > MAX_ENTRIES) return PluginImportResult.Failure("包里文件太多（超过 $MAX_ENTRIES 个）")
                val file = File(target, name)
                if (entry.isDirectory) {
                    file.mkdirs()
                    zip.closeEntry()
                    continue
                }
                file.parentFile?.mkdirs()
                // 2026-11-08 修：以前先整条 copyTo 落盘再累加判断，单个 1GB 条目会被完整写进
                // filesDir 之后才报「包太大」。现在按剩余配额限量写，超了立刻停。
                val remaining = MAX_TOTAL_BYTES - total
                if (remaining <= 0) {
                    return PluginImportResult.Failure("包太大（解压后超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB）")
                }
                val written = file.outputStream().use { out -> zip.copyBounded(out, remaining) }
                total += written
                if (written >= remaining && zip.hasMoreBytes()) {
                    return PluginImportResult.Failure("包太大（解压后超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB）")
                }
                zip.closeEntry()
            }
        }
        return null
    }

    /** 限量拷贝：最多写 [limit] 字节，返回实际写入量。 */
    private fun ZipInputStream.copyBounded(out: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(8 * 1024)
        var written = 0L
        while (written < limit) {
            val read = read(buffer)
            if (read < 0) break
            val take = minOf(read.toLong(), limit - written).toInt()
            out.write(buffer, 0, take)
            written += take
        }
        return written
    }

    /**
     * 当前条目是否还有没读的字节。
     *
     * 不能用 `available()`：ZipInputStream 继承 InflaterInputStream，`available()` 只返回 0/1
     * （表示「还能不能读」），读完最后一个字节时 eof 还没置位、它仍然返回 1 ——
     * 于是「解压总量恰好等于上限」的合法包会被误判成超限（2026-11-08 复查发现）。
     * 这里实探一个字节：真读到了才算超限，读到 -1 说明正好结束。
     */
    private fun ZipInputStream.hasMoreBytes(): Boolean {
        val probe = ByteArray(1)
        return read(probe) >= 0
    }

    /**
     * 定位插件根目录：zip 里常见两种形态 —— 清单在根，或清单在唯一的一层子目录里
     * （打包时顺手把文件夹打进去）。两种都认，其它情况按失败处理。
     */
    internal fun locatePluginRoot(staging: File): File? {
        if (File(staging, PluginRepository.MANIFEST_NAME).isFile) return staging
        val children = staging.listFiles()?.filter { it.isDirectory } ?: return null
        if (children.size != 1) return null
        val only = children.first()
        return if (File(only, PluginRepository.MANIFEST_NAME).isFile) only else null
    }
}

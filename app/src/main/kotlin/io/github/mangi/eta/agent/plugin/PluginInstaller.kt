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
            val extracted = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    extract(stream, staging)
                } ?: return PluginImportResult.Failure("打不开这个文件")
            }.getOrElse { throwable ->
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
                    return PluginImportResult.Failure("装不进去（移动文件失败）")
                }
            }
            // 目录名与 id 不一致时，rename 后的路径仍以 id 为准；这里再确认一次真的是这个插件
            if (!File(destination, PluginRepository.MANIFEST_NAME).isFile) {
                return PluginImportResult.Failure("装完之后找不到 plugin.json")
            }
            PluginImportResult.Ok(manifest.id, manifest.name)
        } finally {
            // 成功或失败都把临时目录清掉（成功时它已被 rename 走，exists 为 false）。
            if (staging.exists()) runCatching { staging.deleteRecursively() }
        }
    }

    /** 解压到 [target]；返回非 null 表示**已经失败**并给出原因。 */
    private fun extract(stream: InputStream, target: File): PluginImportResult? {
        if (!target.mkdirs()) return PluginImportResult.Failure("建不了临时目录")
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
                val written = file.outputStream().use { out -> zip.copyTo(out) }
                total += written
                if (total > MAX_TOTAL_BYTES) {
                    return PluginImportResult.Failure("包太大（解压后超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB）")
                }
                zip.closeEntry()
            }
        }
        return null
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

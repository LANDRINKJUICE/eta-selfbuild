package io.github.mangi.eta.agent.plugin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件导入的安全边界测试（2026-11-08）。
 *
 * 重点全在"必须拒绝什么"上 —— 导入是唯一让**外部文件**进入插件目录的入口，
 * 这里的判断错了就是 zip slip。
 */
class PluginInstallerTest {

    // ---------------------------------------------------------------- 条目名校验

    @Test
    fun rejectsPathTraversalEntryNames() {
        assertFalse(PluginInstaller.isSafeEntryName("../evil.sh"))
        assertFalse(PluginInstaller.isSafeEntryName("a/../../evil.sh"))
        assertFalse(PluginInstaller.isSafeEntryName("a/b/../c/evil.sh"))
        assertFalse(PluginInstaller.isSafeEntryName(".."))
    }

    @Test
    fun rejectsAbsoluteAndBackslashEntryNames() {
        assertFalse(PluginInstaller.isSafeEntryName("/etc/passwd"))
        assertFalse(PluginInstaller.isSafeEntryName("\\windows\\x"))
        assertFalse(PluginInstaller.isSafeEntryName("a\\..\\b"))
    }

    @Test
    fun rejectsBlankAndOverlongEntryNames() {
        assertFalse(PluginInstaller.isSafeEntryName(""))
        assertFalse(PluginInstaller.isSafeEntryName(" ".repeat(3)))
        assertFalse(PluginInstaller.isSafeEntryName("a".repeat(201)))
    }

    @Test
    fun acceptsNormalEntryNames() {
        assertTrue(PluginInstaller.isSafeEntryName("plugin.json"))
        assertTrue(PluginInstaller.isSafeEntryName("scripts/gen.py"))
        assertTrue(PluginInstaller.isSafeEntryName("gw-status/README.md"))
        // 名字里带两个点但不是路径段，是合法文件名
        assertTrue(PluginInstaller.isSafeEntryName("notes..md"))
    }

    // ---------------------------------------------------------------- 插件根定位

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "eta-plugin-test-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun locatePluginRootAcceptsManifestAtRoot() {
        val staging = tempDir("root")
        File(staging, PluginRepository.MANIFEST_NAME).writeText("{}")
        File(staging, "run.sh").writeText("echo hi")
        assertEquals(staging, PluginInstaller.locatePluginRoot(staging))
        staging.deleteRecursively()
    }

    @Test
    fun locatePluginRootAcceptsSingleSubdirectory() {
        val staging = tempDir("sub")
        val inner = File(staging, "my-plugin").apply { mkdirs() }
        File(inner, PluginRepository.MANIFEST_NAME).writeText("{}")
        assertEquals(inner, PluginInstaller.locatePluginRoot(staging))
        staging.deleteRecursively()
    }

    @Test
    fun locatePluginRootRejectsAmbiguousLayout() {        val staging = tempDir("multi")
        File(staging, "a").mkdirs()
        File(staging, "b").mkdirs()
        File(File(staging, "a"), PluginRepository.MANIFEST_NAME).writeText("{}")
        File(File(staging, "b"), PluginRepository.MANIFEST_NAME).writeText("{}")
        assertTrue(PluginInstaller.locatePluginRoot(staging) == null)

        val empty = tempDir("empty")
        assertTrue(PluginInstaller.locatePluginRoot(empty) == null)
        staging.deleteRecursively()
        empty.deleteRecursively()
    }

    // ---------------------------------------------------------------- 解压（2026-11-08 回归）

    /**
     * extract 的契约是「返回 null = 成功」。
     *
     * 这条测试盯的是那个阻断级 bug：importZip 里曾经写成
     * `openInputStream(uri)?.use { extract(...) } ?: return Failure("打不开这个文件")`，
     * 把「解压成功（返回 null）」也当成打不开，导致任何 zip 都装不进去。
     * 这里直接锁住 extract 的返回值语义，importZip 才不会又把它用错。
     */
    @Test
    fun extractReturnsNullOnSuccess() {
        val staging = tempDir("extract-ok")
        val zip = buildZip(
            "plugin.json" to """{"id":"demo","type":"shell","permissions":["shell:android"],"exec":{"command":"echo hi"}}""",
            "run.sh" to "#!/bin/sh\necho ok\n",
        )
        val outcome = zip.inputStream().use { PluginInstaller.extract(it, staging) }
        assertTrue("解压成功必须返回 null，实际是 $outcome", outcome == null)
        assertTrue(File(staging, "plugin.json").isFile)
        assertEquals("#!/bin/sh\necho ok\n", File(staging, "run.sh").readText())
        staging.deleteRecursively()
    }

    @Test
    fun extractRejectsTraversalEntry() {
        val staging = tempDir("extract-evil")
        val zip = buildZip("../escape.txt" to "x")
        val outcome = zip.inputStream().use { PluginInstaller.extract(it, staging) }
        assertTrue("路径穿越必须被拒，实际是 $outcome", outcome is PluginImportResult.Failure)
        assertFalse(File(staging.parentFile, "escape.txt").exists())
        staging.deleteRecursively()
    }

    @Test
    fun extractRejectsTooManyEntries() {
        val staging = tempDir("extract-many")
        val entries = (1..PluginInstaller.MAX_ENTRIES + 1).map { "f$it.txt" to "x" }
        val zip = buildZip(*entries.toTypedArray())
        val outcome = zip.inputStream().use { PluginInstaller.extract(it, staging) }
        assertTrue("条目数超限必须被拒，实际是 $outcome", outcome is PluginImportResult.Failure)
        staging.deleteRecursively()
    }

    /** 内存里造一个 zip（不碰 Context，纯 JVM 可跑）。 */
    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }
}

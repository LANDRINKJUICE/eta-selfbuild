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
    fun locatePluginRootRejectsAmbiguousLayout() {
        val staging = tempDir("multi")
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
}

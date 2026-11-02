package io.github.mangi.eta.agent.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件操作层单测。
 *
 * 重点在两处最易出错的地方：
 * 1. **shell 引用**：文件名带空格 / 单引号 / 中文 / 换行时，拼出来的命令不能被拆散，
 *    更不能因为引号未闭合导致后面的命令被当参数执行（那是安全漏洞）。
 * 2. **ls 输出解析**：不同文件的权限、大小、软链接、名字带空格都要解析对。
 *
 * 所有 shell 调用都用假的 lambda 注入，不碰真实文件系统，因此本机能跑。
 */
class FileManagerOpsTest {

    // ---------------------------------------------------------------- shell 引用

    @Test
    fun quoteWrapsPlainName() {
        assertEquals("'/sdcard/a.txt'", FileManagerOps.shellQuote("/sdcard/a.txt"))
    }

    @Test
    fun quoteEscapesSingleQuote() {
        // ' 变成 '\'' ：单引号引用里嵌单引号的标准写法
        assertEquals("'/sdcard/it'\\''s.txt'", FileManagerOps.shellQuote("/sdcard/it's.txt"))
    }

    @Test
    fun quoteKeepsSpacesAndChineseInsideQuotes() {
        assertEquals("'/sdcard/中文 文件.txt'", FileManagerOps.shellQuote("/sdcard/中文 文件.txt"))
    }

    @Test
    fun quoteNeutralisesShellMetacharacters() {
        // 分号与反引号在单引号内失去特殊含义，不会变成第二条命令
        assertEquals("'/sdcard/a;rm -rf /'", FileManagerOps.shellQuote("/sdcard/a;rm -rf /"))
        assertEquals("'/sdcard/\$(reboot)'", FileManagerOps.shellQuote("/sdcard/\$(reboot)"))
    }

    // ---------------------------------------------------------------- ls 解析

    @Test
    fun listParsesRegularFileAndDirectory() {
        val fake = """
            total 8
            drwxr-xr-x 2 root root 4096 Nov  2 10:00 sub
            -rw-r--r-- 1 root root  123 Nov  2 09:30 a.txt
        """.trimIndent().lines().joinToString("\n")
        val listing = FileManagerOps.list({ fake }, "/sdcard")
        assertTrue(listing.ok)
        assertEquals(2, listing.entries.size)
        // 目录排在前面
        assertTrue(listing.entries[0].isDirectory)
        assertEquals("sub", listing.entries[0].name)
        assertEquals("/sdcard/sub", listing.entries[0].path)
        assertFalse(listing.entries[1].isDirectory)
        assertEquals(123L, listing.entries[1].sizeBytes)
    }

    @Test
    fun listSkipsDotEntries() {
        val fake = """
            total 8
            drwxr-xr-x 2 root root 4096 Nov  2 10:00 .
            drwxr-xr-x 2 root root 4096 Nov  2 10:00 ..
            -rw-r--r-- 1 root root   10 Nov  2 09:30 keep.txt
        """.trimIndent()
        val listing = FileManagerOps.list({ fake }, "/sdcard")
        assertEquals(1, listing.entries.size)
        assertEquals("keep.txt", listing.entries[0].name)
    }

    @Test
    fun listHandlesNameWithSpaces() {
        val fake = """
            total 4
            -rw-r--r-- 1 root root  10 Nov  2 09:30 my long name.txt
        """.trimIndent()
        val listing = FileManagerOps.list({ fake }, "/sdcard")
        assertEquals("my long name.txt", listing.entries[0].name)
        assertEquals("/sdcard/my long name.txt", listing.entries[0].path)
    }

    @Test
    fun listResolvesSymlinkTargetSuffix() {
        val fake = """
            total 4
            lrwxrwxrwx 1 root root  20 Nov  2 09:30 link -> /system/bin/sh
        """.trimIndent()
        val listing = FileManagerOps.list({ fake }, "/sdcard")
        assertEquals("link", listing.entries[0].name)
        assertTrue(listing.entries[0].isSymlink)
    }

    @Test
    fun listReportsMissingDirectory() {
        val listing = FileManagerOps.list({ "ls: /nope: No such file or directory" }, "/nope")
        assertFalse(listing.ok)
        assertEquals("目录不存在", listing.error)
    }

    @Test
    fun listReportsPermissionDenied() {
        val listing = FileManagerOps.list({ "ls: /data/data: Permission denied" }, "/data/data")
        assertFalse(listing.ok)
        assertTrue(listing.error?.contains("权限") == true)
    }

    @Test
    fun emptyDirectoryReturnsNoEntries() {
        val listing = FileManagerOps.list({ "total 0" }, "/sdcard/empty")
        assertTrue(listing.ok)
        assertTrue(listing.entries.isEmpty())
    }

    // ---------------------------------------------------------------- 操作

    @Test
    fun moveToTrashUsesMvNotRm() {
        val commands = mutableListOf<String>()
        val result = FileManagerOps.moveToTrash({ cmd -> commands.add(cmd); "" }, listOf("/sdcard/a.txt"))
        assertTrue(result.ok)
        assertTrue("必须走 mv 到回收站，不能直接删除", commands.any { it.contains("mv ") })
        assertFalse("不能出现 rm", commands.any { it.contains("rm ") })
        assertTrue(result.message.contains("回收站"))
    }

    @Test
    fun moveToTrashReportsPartialFailure() {
        val result = FileManagerOps.moveToTrash(
            { cmd -> if (cmd.contains("bad")) "mv: Permission denied" else "" },
            listOf("/sdcard/good.txt", "/sdcard/bad.txt"),
        )
        assertFalse(result.ok)
        assertTrue(result.message.contains("bad.txt"))
    }

    @Test
    fun renameRejectsSlashInName() {
        val result = FileManagerOps.rename({ "" }, "/sdcard/a.txt", "x/y.txt")
        assertFalse(result.ok)
    }

    @Test
    fun createDirectoryRejectsEmptyName() {
        assertFalse(FileManagerOps.createDirectory({ "" }, "/sdcard", "  ").ok)
    }

    @Test
    fun copyQuotesBothSourceAndTarget() {
        val commands = mutableListOf<String>()
        FileManagerOps.copy({ cmd -> commands.add(cmd); "" }, listOf("/sdcard/中文 名.txt"), "/sdcard/dst")
        assertTrue(commands.single().contains("'/sdcard/中文 名.txt'"))
        assertTrue(commands.single().contains("'/sdcard/dst/中文 名.txt'"))
    }

    @Test
    fun formatSizeIsHumanReadable() {
        assertEquals("512 B", FileManagerOps.formatSize(512))
        assertEquals("1.0 KB", FileManagerOps.formatSize(1024))
        assertEquals("1.0 MB", FileManagerOps.formatSize(1024L * 1024))
    }
}

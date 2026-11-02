package io.github.mangi.eta.agent.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件操作层单测。
 *
 * 重点覆盖三处最容易出错的地方（都踩过或差点踩到）：
 * 1. **shell 引用**：文件名带空格 / 单引号 / 分号时，拼出的命令不能被拆散。
 * 2. **stat 输出解析**：这台机器的 busybox 用 `2026-03-25 17:24` 两段日期，
 *    曾按 `ls -la` 固定列号解析导致名字被截断（"Payload Dumper" → "Dumper"）——
 *    所以现在解析固定分隔符格式，这里要守住"名字完整"这条线。
 * 3. **回收站**：删除必须走 mv，不能直接 rm。
 *
 * 所有 shell 调用都用假 lambda 注入，不碰真实文件系统，因此本机能跑。
 */
class FileManagerOpsTest {

    // ---------------------------------------------------------------- shell 引用

    @Test
    fun quoteWrapsPlainName() {
        assertEquals("'/sdcard/a.txt'", FileManagerOps.shellQuote("/sdcard/a.txt"))
    }

    @Test
    fun quoteEscapesSingleQuote() {
        assertEquals("'/sdcard/it'\\''s.txt'", FileManagerOps.shellQuote("/sdcard/it's.txt"))
    }

    @Test
    fun quoteKeepsSpacesAndChineseInsideQuotes() {
        assertEquals("'/sdcard/中文 文件.txt'", FileManagerOps.shellQuote("/sdcard/中文 文件.txt"))
    }

    @Test
    fun quoteNeutralisesShellMetacharacters() {
        assertEquals("'/sdcard/a;rm -rf /'", FileManagerOps.shellQuote("/sdcard/a;rm -rf /"))
        assertEquals("'/sdcard/\$(reboot)'", FileManagerOps.shellQuote("/sdcard/\$(reboot)"))
    }

    // ---------------------------------------------------------------- 目录列举解析

    @Test
    fun listParsesStatFormat() {
        val fake = """
            drwxrws---|4096|1792144837|sub
            -rw-r--r--|123|1792144800|a.txt
        """.trimIndent()
        val listing = FileManagerOps.list({ fake }, "/sdcard")
        assertTrue(listing.ok)
        assertEquals(2, listing.entries.size)
        // 目录排在前面
        assertTrue(listing.entries[0].isDirectory)
        assertEquals("sub", listing.entries[0].name)
        assertEquals("/sdcard/sub", listing.entries[0].path)
        assertFalse(listing.entries[1].isDirectory)
        assertEquals(123L, listing.entries[1].sizeBytes)
        // 时间戳按秒转毫秒
        assertEquals(1792144800000L, listing.entries[1].modifiedAtMillis)
    }

    /** 这是本轮修的核心 bug：名字必须完整保留，不能被空格截断。 */
    @Test
    fun listKeepsFullNameWithSpaces() {
        val fake = "drwxrws---|3440|1792144837|Payload Dumper"
        val listing = FileManagerOps.list({ fake }, "/sdcard/Download")
        assertTrue(listing.ok)
        assertEquals(1, listing.entries.size)
        assertEquals("Payload Dumper", listing.entries[0].name)
        assertEquals("/sdcard/Download/Payload Dumper", listing.entries[0].path)
    }

    @Test
    fun listKeepsNameContainingPipe() {
        val fake = "-rw-r--r--|10|1792144837|weird|name.txt"
        val listing = FileManagerOps.list({ fake }, "/sdcard")
        assertEquals("weird|name.txt", listing.entries[0].name)
    }

    @Test
    fun listParsesChineseName() {
        val fake = "drwxrws---|3440|1792144837|123云盘"
        val listing = FileManagerOps.list({ fake }, "/sdcard/Download")
        assertEquals("123云盘", listing.entries[0].name)
    }

    @Test
    fun listMarksSymlink() {
        val fake = "lrwxrwxrwx|20|1792144837|link"
        val listing = FileManagerOps.list({ fake }, "/sdcard")
        assertTrue(listing.entries[0].isSymlink)
        assertFalse("软链接不应被当成目录", listing.entries[0].isDirectory)
    }

    @Test
    fun listSkipsJunkLines() {
        val fake = """
            drwxrws---|4096|1792144837|keep
            some random warning line
            |||
            -rw-r--r--|5|1792144837|also-keep
        """.trimIndent()
        val listing = FileManagerOps.list({ fake }, "/sdcard")
        assertEquals("无法解析的行应跳过", 2, listing.entries.size)
    }

    @Test
    fun listReportsMissingDirectory() {
        val listing = FileManagerOps.list({ "cd /nope: No such file or directory" }, "/nope")
        assertFalse(listing.ok)
        assertEquals("目录不存在", listing.error)
    }

    @Test
    fun listReportsPermissionDenied() {
        val listing = FileManagerOps.list({ "cd /data/data: Permission denied" }, "/data/data")
        assertFalse(listing.ok)
        assertTrue(listing.error?.contains("权限") == true)
    }

    @Test
    fun emptyDirectoryReturnsNoEntries() {
        val listing = FileManagerOps.list({ "" }, "/sdcard/empty")
        assertTrue(listing.ok)
        assertTrue(listing.entries.isEmpty())
    }

    @Test
    fun listSortsDirectoriesFirstThenByName() {
        val fake = """
            -rw-r--r--|1|1792144837|zeta.txt
            drwxrws---|1|1792144837|beta
            -rw-r--r--|1|1792144837|alpha.txt
            drwxrws---|1|1792144837|Apple
        """.trimIndent()
        val listing = FileManagerOps.list({ fake }, "/sdcard")
        assertEquals(listOf("Apple", "beta", "alpha.txt", "zeta.txt"), listing.entries.map { it.name })
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
        assertFalse(FileManagerOps.rename({ "" }, "/sdcard/a.txt", "x/y.txt").ok)
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

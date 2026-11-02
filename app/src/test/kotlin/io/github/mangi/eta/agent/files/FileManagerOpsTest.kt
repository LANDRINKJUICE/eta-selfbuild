package io.github.mangi.eta.agent.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---------------------------------------------------------------- 打开文件的分流

    /**
     * 点文件要能"看见内容"，所以按扩展名分流。
     * 这里守住三类边界：文本白名单、图片白名单、以及**无扩展名不能被当成文本或图片**。
     */
    @Test
    fun textExtensionsAreRecognised() {
        assertTrue(FileManagerOps.looksLikeText("README.md"))
        assertTrue(FileManagerOps.looksLikeText("a.LOG"))
        assertTrue(FileManagerOps.looksLikeText("config.prop"))
        assertTrue(FileManagerOps.looksLikeText("build.gradle.kts"))
        assertFalse(FileManagerOps.looksLikeText("video.mp4"))
        assertFalse(FileManagerOps.looksLikeText("noext"))
        assertFalse(FileManagerOps.looksLikeText("archive.zip"))
    }

    @Test
    fun imageExtensionsAreRecognised() {
        assertTrue(FileManagerOps.looksLikeImage("a.jpg"))
        assertTrue(FileManagerOps.looksLikeImage("B.JPEG"))
        assertTrue(FileManagerOps.looksLikeImage("c.heic"))
        assertFalse(FileManagerOps.looksLikeImage("a.apk"))
        assertFalse(FileManagerOps.looksLikeImage("noext"))
    }

    @Test
    fun mediaAndArchiveExtensionsAreRecognised() {
        assertTrue(FileManagerOps.looksLikeMedia("a.mp4"))
        assertTrue(FileManagerOps.looksLikeMedia("b.MP3"))
        assertFalse(FileManagerOps.looksLikeMedia("c.txt"))
        assertTrue(FileManagerOps.looksLikeArchive("d.zip"))
        assertTrue(FileManagerOps.looksLikeArchive("e.apk"))
        assertFalse(FileManagerOps.looksLikeArchive("f.txt"))
    }

    // ---------------------------------------------------------------- 编辑保存

    /**
     * 保存文本必须走 base64 分块：内容里的引号、反引号、`$`、换行都不能被 shell 解释。
     * 这里守住三条：① 清空旧内容 ② 用 base64 -d 追加 ③ 大内容分多块。
     */
    @Test
    fun writeTextUsesBase64AndClearsFirst() {
        val commands = mutableListOf<String>()
        val result = FileManagerOps.writeText(
            { cmd -> commands.add(cmd); "" },
            "/sdcard/中文 名.txt",
            "hello 'quoted' \$(reboot) `id`\n中文",
        )
        assertTrue(result.ok)
        assertTrue(commands.first().startsWith(": > "))
        assertTrue(commands.first().contains("'/sdcard/中文 名.txt'"))
        assertTrue(commands[1].contains("base64 -d"))
        assertTrue(commands[1].contains(">> '/sdcard/中文 名.txt'"))
        // 关键：原始危险内容绝不能出现在命令里（否则会被 shell 执行）
        assertFalse(commands.any { it.contains("reboot") })
        assertFalse(commands.any { it.contains("`id`") })
    }

    @Test
    fun writeTextChunksLargeContent() {
        val commands = mutableListOf<String>()
        // 100 KB > 32 KB 分块，应该至少 4 条写命令
        val big = "x".repeat(100 * 1024)
        val result = FileManagerOps.writeText({ cmd -> commands.add(cmd); "" }, "/sdcard/big.txt", big)
        assertTrue(result.ok)
        val writes = commands.filter { it.contains("base64 -d") }
        assertTrue("应分块写入，实际 ${writes.size} 条", writes.size >= 4)
        // 每块 base64 文本不应超过 64 KB，避免单参数超内核上限
        assertTrue(writes.all { it.length < 64 * 1024 })
    }

    @Test
    fun writeTextReportsPermissionDenied() {
        var call = 0
        val result = FileManagerOps.writeText({ call++; if (call == 1) "" else "Permission denied" }, "/data/x", "y")
        assertFalse(result.ok)
        assertTrue(result.message.contains("权限"))
    }

    // ---------------------------------------------------------------- 搜索与压缩包

    @Test
    fun searchQuotesKeywordAndLimitsDepth() {
        val commands = mutableListOf<String>()
        val fake = "drwxrws---|3440|1792144837|/sdcard/abc/def dir"
        val result = FileManagerOps.search({ cmd -> commands.add(cmd); fake }, "/sdcard", "abc")
        assertTrue(result.error == null)
        val cmd = commands.single()
        assertTrue(cmd.contains("-maxdepth"))
        assertTrue(cmd.contains("-iname '*abc*'"))
        assertEquals(1, result.entries.size)
        // find 输出的是完整路径：名字要取最后一段，路径要保留全路径（不能被拼成 //sdcard/...）
        assertEquals("def dir", result.entries[0].name)
        assertEquals("/sdcard/abc/def dir", result.entries[0].path)
        assertTrue(result.entries[0].isDirectory)
    }

    @Test
    fun searchRejectsEmptyKeyword() {
        val result = FileManagerOps.search({ "" }, "/sdcard", "   ")
        assertTrue(result.entries.isEmpty())
        assertEquals("请输入关键词", result.error)
    }

    @Test
    fun listArchiveParsesUnzipOutput() {
        val fake = """
            Archive:  /sdcard/a.apk
              Length      Date    Time    Name
            ---------  ---------- -----   ----
                  512  2020-01-01 00:00   AndroidManifest.xml
                 1024  2020-01-01 00:00   classes.dex
            ---------                     -------
                 1536                     2 files
        """.trimIndent()
        val result = FileManagerOps.listArchive({ fake }, "/sdcard/a.apk")
        assertTrue(result.ok)
        assertTrue(result.message.contains("AndroidManifest.xml"))
        assertTrue(result.message.contains("classes.dex"))
        // 分隔线与表头不能被当成条目
        assertFalse(result.message.contains("---------"))
    }

    @Test
    fun listArchiveRejectsNonZip() {
        val result = FileManagerOps.listArchive({ "unzip: not a zip archive" }, "/sdcard/a.txt")
        assertFalse(result.ok)
    }

    /** 无扩展名文件（如 .uuid）要能判定成文本，否则用户点了只看到属性框。 */
    @Test
    fun isTextFileUsesFileCommand() {
        assertTrue(FileManagerOps.isTextFile({ "ASCII text" }, "/sdcard/x/.uuid"))
        assertTrue(FileManagerOps.isTextFile({ "UTF-8 Unicode text" }, "/data/hosts"))
        assertTrue(FileManagerOps.isTextFile({ "empty" }, "/sdcard/empty"))
        assertFalse(FileManagerOps.isTextFile({ "Zip archive data" }, "/sdcard/a.apk"))
        assertFalse(FileManagerOps.isTextFile({ "data" }, "/sdcard/bin.dat"))
    }

    /** 文本预览用 head -c 截断，不能被大文件拖死；同时路径必须正确引用。 */
    @Test
    fun readTextPreviewTruncatesAndQuotes() {
        val commands = mutableListOf<String>()
        val result = FileManagerOps.readTextPreview(
            { cmd -> commands.add(cmd); "第一行\n第二行" },
            "/sdcard/中文 名.txt",
        )
        assertTrue(result.ok)
        assertEquals("第一行\n第二行", result.message)
        assertTrue(commands.single().contains("head -c"))
        assertTrue(commands.single().contains("'/sdcard/中文 名.txt'"))
    }

    @Test
    fun readTextPreviewReportsPermissionDenied() {
        val result = FileManagerOps.readTextPreview({ "head: Permission denied" }, "/data/x")
        assertFalse(result.ok)
        assertTrue(result.message.contains("权限"))
    }

    /** 属性面板要能看见"它是什么"，至少包含路径、类型、权限三项。 */
    @Test
    fun describeEntryShowsPathTypePermissions() {
        val entry = FileManagerOps.Entry(
            name = "a.txt",
            path = "/sdcard/a.txt",
            isDirectory = false,
            isSymlink = false,
            sizeBytes = 2048,
            modifiedAtMillis = 1792144800000L,
            permissions = "-rw-r--r--",
        )
        val text = FileManagerOps.describeEntry(entry)
        assertTrue(text.contains("/sdcard/a.txt"))
        assertTrue(text.contains("文件"))
        assertTrue(text.contains("-rw-r--r--"))
        assertTrue(text.contains("2.0 KB"))
    }

    // ---------------------------------------------------------------- 打开方式

    /**
     * 图片走内置查看器，靠 readBytesDirect 直接读字节。
     * 守住两条边界：① 不存在/不可读 → null ② 超过上限 → null（不能把内存打爆）。
     */
    @Test
    fun readBytesDirectRejectsMissingAndOversized() {
        assertNull(FileManagerOps.readBytesDirect("/definitely/not/here.png"))
        val small = java.io.File.createTempFile("eta-test", ".txt")
        try {
            small.writeText("hi")
            assertEquals("hi", String(FileManagerOps.readBytesDirect(small.absolutePath)!!))
            // 上限设成 1 字节，2 字节的文件必须被拒
            assertNull(FileManagerOps.readBytesDirect(small.absolutePath, maxBytes = 1L))
        } finally {
            small.delete()
        }
    }

    /** 交给外部应用打开时，MIME 要按扩展名给对，否则系统找不到接收方。 */
    @Test
    fun mimeOfCoversCommonTypes() {
        assertEquals("image/jpeg", FileManagerOps.mimeOf("a.JPG"))
        assertEquals("image/png", FileManagerOps.mimeOf("b.png"))
        assertEquals("video/mp4", FileManagerOps.mimeOf("c.mp4"))
        assertEquals("audio/mpeg", FileManagerOps.mimeOf("d.mp3"))
        assertEquals("application/pdf", FileManagerOps.mimeOf("e.pdf"))
        assertEquals("*/*", FileManagerOps.mimeOf("noext"))
    }

    /**
     * 关键回归：targetSdk 37 下应用进程内用 file:// 打开会被系统拒绝（FileUriExposedException），
     * 所以必须走 shell 的 am start。这里守住命令形状：VIEW + file:// + 正确引用。
     */
    @Test
    fun openExternalUsesAmStartWithQuotedUri() {
        val commands = mutableListOf<String>()
        val result = FileManagerOps.openExternal(
            { cmd -> commands.add(cmd); "Starting: Intent" },
            "/sdcard/中文 图.jpg",
            "image/jpeg",
        )
        assertTrue(result.ok)
        val cmd = commands.single()
        assertTrue(cmd.contains("am start"))
        assertTrue(cmd.contains("android.intent.action.VIEW"))
        assertTrue(cmd.contains("'file:///sdcard/中文 图.jpg'"))
        assertTrue(cmd.contains("'image/jpeg'"))
    }

    @Test
    fun openExternalReportsFailure() {
        val result = FileManagerOps.openExternal({ "Error: Activity not started" }, "/sdcard/a.xyz", "*/*")
        assertFalse(result.ok)
    }

    // ---------------------------------------------------------------- 回收站 / 解压 / 权限

    /** 回收站列表要能解析出桶名、文件名、大小、时间。 */
    @Test
    fun listTrashParsesBuckets() {
        val fake = """
            2048|1792144837|/sdcard/Download/Eta/.trash/20261103-011330/a.txt
            4096|1792144900|/sdcard/Download/Eta/.trash/20261103-011330/sub dir
        """.trimIndent()
        val list = FileManagerOps.listTrash { fake }
        assertEquals(2, list.size)
        // 按时间降序：后者更新，排在前
        assertEquals("sub dir", list[0].name)
        assertEquals("20261103-011330", list[0].bucket)
        assertEquals("a.txt", list[1].name)
        assertEquals(2048L, list[1].sizeBytes)
    }

    @Test
    fun emptyTrashUsesFindDelete() {
        val cmds = mutableListOf<String>()
        FileManagerOps.emptyTrash { cmd -> cmds.add(cmd); "" }
        assertTrue(cmds.single().contains("rm -rf"))
        assertTrue(cmds.single().contains(".trash"))
    }

    /** 恢复只能放到明确目录（回收站没记原路径，不能假装能还原到原地）。 */
    @Test
    fun restoreMovesToRecoveredDir() {
        val cmds = mutableListOf<String>()
        val items = listOf(
            FileManagerOps.TrashEntry("b1", "x.txt", "/sdcard/Download/Eta/.trash/b1/x.txt", 1, 0),
        )
        val result = FileManagerOps.restoreTrash({ cmd -> cmds.add(cmd); "" }, items)
        assertTrue(result.ok)
        assertTrue(result.message.contains("recovered"))
        assertTrue(cmds.any { it.contains("mv -n") && it.contains("recovered/x.txt") })
    }

    /** 解压要建独立目录，不能把一堆文件散到当前目录。 */
    @Test
    fun extractCreatesTargetDir() {
        val cmds = mutableListOf<String>()
        val result = FileManagerOps.extractArchive({ cmd -> cmds.add(cmd); "  inflating: a" }, "/sdcard/a.zip")
        assertTrue(result.ok)
        assertTrue(result.message.contains("a"))
        val cmd = cmds.single()
        assertTrue(cmd.contains("mkdir -p '/sdcard/a'"))
        assertTrue(cmd.contains("unzip -o '/sdcard/a.zip' -d '/sdcard/a'"))
    }

    @Test
    fun extractRejectsNonZip() {
        assertFalse(FileManagerOps.extractArchive({ "unzip: not a zip archive" }, "/sdcard/a.txt").ok)
    }

    @Test
    fun chmodValidatesMode() {
        assertFalse(FileManagerOps.chmod({ "" }, listOf("/sdcard/a"), "999").ok)
        assertFalse(FileManagerOps.chmod({ "" }, listOf("/sdcard/a"), "abc").ok)
        val cmds = mutableListOf<String>()
        assertTrue(FileManagerOps.chmod({ cmd -> cmds.add(cmd); "" }, listOf("/sdcard/a"), "755").ok)
        assertTrue(cmds.single().contains("chmod 755"))
    }

    @Test
    fun chmodReportsPermissionDenied() {
        val result = FileManagerOps.chmod({ "chmod: Permission denied" }, listOf("/system/x"), "755")
        assertFalse(result.ok)
    }
}

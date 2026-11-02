package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块解码的字符边界契约。
 *
 * Android 的 CharsetDecoder 由 ICU 实现：endOfInput=false 时末尾残缺的多字节序列会被算成已消费并
 * 返回 underflow，因此分块读取必须在进入解码器之前按字符边界收尾，否则大文件会随机误报 NOT_UTF8_TEXT；
 * 同时真正的非法字节序列（含文件末尾被截断的字符）仍必须报错。
 */
class FileToolTextReaderTest {
    @Test
    fun chunkEndingInsideMultibyteCharacterStopsAtCharacterBoundary() {
        val text = "a".repeat(15_999) + "汉字😀"
        val backend = BytesBackend(text.toByteArray(Charsets.UTF_8))
        val stat = backend.stat("memory")
        val reader = FileToolTextReader(backend)

        val first = reader.read(stat, 0, 16_000)
        assertEquals(15_999, first.bytesConsumed)
        assertEquals("a".repeat(15_999), first.text)
        assertEquals(false, first.eof)

        val second = reader.read(stat, first.bytesConsumed.toLong(), 16_000)
        assertEquals("汉字😀", second.text)
        assertEquals(true, second.eof)
        assertEquals(stat.sizeBytes, first.bytesConsumed.toLong() + second.bytesConsumed)
    }

    /** 任意分块长度都要能完整还原文本，且游标始终落在字符边界上。 */
    @Test
    fun everyChunkSizeRebuildsTheWholeText() {
        val text = "汉字 a😀b é中".repeat(7) + "尾"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val backend = BytesBackend(bytes)
        val stat = backend.stat("memory")
        for (chunkSize in 4..17) {
            val reader = FileToolTextReader(backend)
            val rebuilt = StringBuilder()
            var offset = 0L
            while (offset < stat.sizeBytes) {
                val part = reader.read(stat, offset, chunkSize)
                assertTrue("chunk=$chunkSize 的游标没有前进", part.bytesConsumed > 0)
                rebuilt.append(part.text)
                offset += part.bytesConsumed
            }
            assertEquals("chunk=$chunkSize", text, rebuilt.toString())
        }
    }

    @Test
    fun fileEndingInsideMultibyteCharacterIsInvalid() {
        val complete = "汉字".toByteArray(Charsets.UTF_8)
        listOf(1, 2, 4, 5).forEach { length ->
            val backend = BytesBackend(complete.copyOfRange(0, length))
            assertFailure("NOT_UTF8_TEXT") { FileToolTextReader(backend).read(backend.stat("memory"), 0, 16_000) }
        }
    }

    @Test
    fun malformedAndMidCharacterBytesStillFail() {
        val cases = mapOf(
            "孤立续字节" to byteArrayOf('a'.code.toByte(), 0x80.toByte(), 'b'.code.toByte()),
            "非法前导 0xff" to byteArrayOf(0xff.toByte(), 'b'.code.toByte()),
            "overlong" to byteArrayOf(0xe0.toByte(), 0x80.toByte(), 0x80.toByte()),
            "代理区" to byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
        )
        cases.forEach { (name, bytes) ->
            val backend = BytesBackend(bytes)
            assertFailure(name, "NOT_UTF8_TEXT") { FileToolTextReader(backend).read(backend.stat("memory"), 0, 16_000) }
        }
        val text = BytesBackend("中文".toByteArray(Charsets.UTF_8))
        assertFailure("字节游标位于字符中间", "NOT_UTF8_TEXT") { FileToolTextReader(text).read(text.stat("memory"), 1, 16_000) }
    }

    @Test
    fun binaryContentIsRejectedBeforeDecoding() {
        val backend = BytesBackend(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))
        assertFailure("BINARY_FILE") { FileToolTextReader(backend).read(backend.stat("memory"), 0, 16_000) }
    }

    private fun assertFailure(code: String, block: () -> Unit) = assertFailure(code, code, block)

    private fun assertFailure(name: String, code: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("$name: Expected $code")
        } catch (failure: FileToolException) {
            assertEquals(name, code, failure.code)
        }
    }

    /** 内存后端：按 limit 返回字节窗口，与真实后端的读取语义一致。 */
    private class BytesBackend(private val bytes: ByteArray) : FileToolBackend {
        override val environment = "android"
        override val identity = "user"
        override fun resolve(path: String) = path
        override fun stat(path: String) = FileToolStat(path, path, FileToolKind.FILE, bytes.size.toLong(), "revision")
        override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
            if (offset >= bytes.size) return ByteArray(0)
            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + limit).toInt())
        }
        override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?) =
            FileToolDirectoryPage(emptyList(), 0, false, "revision")
        override fun write(
            path: String,
            bytes: ByteArray,
            append: Boolean,
            expectedRevision: String?,
            expectedSha256: String?,
        ) = FileToolWriteResult(stat(path), atomic = false)
    }
}

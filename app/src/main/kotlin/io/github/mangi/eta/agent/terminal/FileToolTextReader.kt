package io.github.mangi.eta.agent.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal data class FileToolTextChunk(
    val text: String,
    val bytesConsumed: Int,
    val eof: Boolean,
)

/**
 * 字节游标只能越过已完整解码的字符，不把半个 UTF-8 字符记为已读。
 *
 * Android 的解码器是 com.android.icu.charset.CharsetICU，不是 JDK 的 sun.nio.cs.UTF_8：
 * endOfInput=false 时它会把末尾残缺的多字节序列一并算成已消费并返回 underflow（JDK 会退回到序列
 * 起点）。直接采信 input.position() 会让下一块从字符中间开始，那块开头的 MALFORMED 于是变成对合法
 * UTF-8 文件的 NOT_UTF8_TEXT 误报——按 16000 字节分块读大文件时随机踩到，与文件内容有关而与编码无关。
 * 所以这里自己按字符边界收尾：末尾残缺序列只在文件确实结束时才算非法，否则留给下一块与后随字节一起
 * 解码，真正的非法字节序列仍然照旧报错。
 */
internal class FileToolTextReader(private val backend: FileToolBackend) {
    fun read(stat: FileToolStat, offset: Long, maxBytes: Int, maxChars: Int = 16_000): FileToolTextChunk =
        decode(stat, offset, backend.readBytes(stat.path, offset, maxBytes.coerceIn(1, 16_000)), maxChars)

    /**
     * 解码已经读到的字节区间：批量读取路径（见 FileToolSearch）拿到字节后直接调用这里，
     * 与 [read] 共用同一套字符边界与二进制校验，保证两条路的错误码与切分完全一致。
     */
    fun decode(stat: FileToolStat, offset: Long, bytes: ByteArray, maxChars: Int = 16_000): FileToolTextChunk {
        if (bytes.any { it == 0.toByte() }) throw FileToolException("BINARY_FILE", "文件包含二进制内容，请使用文件导出或终端工具处理")
        val eof = offset + bytes.size >= stat.sizeBytes
        val incomplete = trailingIncompleteLength(bytes)
        // 文件到此为止，末尾残缺的多字节序列就是真的非法文本（Android 的解码器会把它当作已读而不报错，
        // 必须在进入解码器之前挡住）。
        if (eof && incomplete > 0) throw FileToolException("NOT_UTF8_TEXT", "文件末尾的多字节字符不完整，内容不是有效 UTF-8 文本")
        val end = if (eof) bytes.size else bytes.size - incomplete
        val input = ByteBuffer.wrap(bytes, 0, end)
        val output = CharBuffer.allocate(maxChars.coerceIn(2, 16_000))
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(input, output, eof)
        if (result.isError) throw FileToolException("NOT_UTF8_TEXT", "内容不是有效 UTF-8 文本，或字节游标位于字符中间")
        if (bytes.isNotEmpty() && input.position() == 0) {
            throw FileToolException("READ_LIMIT_TOO_SMALL", "字节上限不足以读取一个完整字符，请使用至少 4 字节")
        }
        if (bytes.isEmpty() && offset < stat.sizeBytes) throw FileToolException("FILE_CHANGED", "文件在读取期间已变化")
        output.flip()
        return FileToolTextChunk(output.toString(), input.position(), eof && input.position() == end)
    }

    /** 末尾残缺多字节序列的长度，0 表示末尾正好落在字符边界上。只认 2/3/4 字节序列的合法前导。 */
    private fun trailingIncompleteLength(bytes: ByteArray): Int {
        var index = bytes.size - 1
        var continuations = 0
        while (index >= 0 && continuations < 3 && (bytes[index].toInt() and 0xff) in 0x80..0xbf) {
            index -= 1
            continuations += 1
        }
        if (index < 0) return 0
        val expected = when (bytes[index].toInt() and 0xff) {
            in 0xc2..0xdf -> 2
            in 0xe0..0xef -> 3
            in 0xf0..0xf4 -> 4
            // 非法前导字节（0x80..0xc1、0xf5..0xff）不在这里兜底，交给解码器报错。
            else -> return 0
        }
        val available = bytes.size - index
        return if (available < expected) available else 0
    }
}

package io.github.mangi.eta.agent.terminal

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** 游标保留有限深度的目录栈和当前文本位置；每轮只扫描有界条目与字节。 */
internal class FileToolSearch(
    private val backend: FileToolBackend,
    private val nanoTime: () -> Long = System::nanoTime,
    // 内层搜索墙钟上限：这是本轮扫描的软时间预算，需 ≤ 外层 fileTool deadline 的一半左右，
    // 否则内层早早停下、外层剩下的预算被白白浪费，实测只扫到一两个文件就报 time_limit。
    private val softTimeLimitNanos: Long = 15_000_000_000L,
) {
    fun glob(path: String, pattern: String, limit: Int, cursor: String?, includeHidden: Boolean): String =
        search(path, pattern, null, limit, cursor, includeHidden, true)

    fun grep(path: String, query: String, glob: String, limit: Int, cursor: String?, includeHidden: Boolean, caseSensitive: Boolean): String =
        search(path, glob, query, limit, cursor, includeHidden, caseSensitive)

    private fun search(
        path: String,
        pattern: String,
        query: String?,
        limit: Int,
        cursor: String?,
        includeHidden: Boolean,
        caseSensitive: Boolean,
    ): String = fileToolOperation(if (query == null) "glob_files" else "grep_files") {
        require(limit > 0)
        if (query != null && (query.isEmpty() || query.length > 512 || query.contains('\n') || query.contains('\r'))) {
            throw FileToolException("INVALID_QUERY", "搜索词必须为 1 到 512 个字符的单行普通文本")
        }
        val root = backend.resolve(path)
        val rootStat = backend.stat(root)
        if (rootStat.kind != FileToolKind.DIRECTORY) throw FileToolException("NOT_DIRECTORY", "搜索范围必须是目录")
        val matcher = FileToolGlob(pattern)
        val signature = digest(JSONObject().put("tool", if (query == null) "glob_files" else "grep_files")
            .put("root", root).put("pattern", pattern).put("query", query ?: JSONObject.NULL)
            .put("include_hidden", includeHidden).put("case_sensitive", caseSensitive)
            .put("environment", backend.environment).put("identity", backend.identity).toString())
        val state = if (cursor.isNullOrBlank()) State(mutableListOf(Frame("", 0, rootStat.revision))) else decodeCursor(cursor, signature)
        state.frames.forEach { frame ->
            if (backend.stat(join(root, frame.relative)).revision != frame.revision) {
                throw FileToolException("STALE_CURSOR", "搜索目录已变化，请重新开始搜索")
            }
        }
        val startedAt = nanoTime()
        val timeExpired = { nanoTime() - startedAt >= softTimeLimitNanos }
        val matches = JSONArray()
        val skipped = JSONArray()
        val skippedBefore = state.skipped
        var entryBudget = MAX_ENTRIES
        var byteBudget = MAX_BYTES
        var outputChars = 0
        val maxResults = limit.coerceAtMost(200)
        var stopReason = "complete"
        val reader = FileToolTextReader(backend)
        val directoryPages = mutableMapOf<String, CachedPage>()
        // 批量读取预取队列：只在本次调用内有效（游标不保存它），元素按「文件 + 起始字节」精确匹配。
        val batchQueue = ArrayDeque<FileToolBatchRead>()
        while (matches.length() < maxResults && outputChars < MAX_MATCH_CHARS) {
            backend.checkActive()
            if (timeExpired() && (entryBudget < MAX_ENTRIES || byteBudget < MAX_BYTES)) { stopReason = "time_limit"; break }
            if (state.pending == null) {
                if (entryBudget <= 0) { stopReason = "entry_limit"; break }
                val next = nextFile(root, state, matcher, includeHidden, entryBudget, skipped, directoryPages, timeExpired)
                entryBudget -= next.visited
                state.pending = next.file
                if (next.file == null) {
                    if (state.frames.isNotEmpty()) stopReason = if (next.timeLimited) "time_limit" else "entry_limit"
                    break
                }
            }
            val pending = state.pending!!
            if (query == null) {
                val item = JSONObject().put("path", join(root, pending.relative)).put("relative_path", pending.relative)
                val cost = item.toString().length
                if (matches.length() > 0 && outputChars + cost > MAX_MATCH_CHARS) { stopReason = "output_limit"; break }
                matches.put(item)
                outputChars += cost
                state.pending = null
                continue
            }
            if (timeExpired() && entryBudget < MAX_ENTRIES) { stopReason = "time_limit"; break }
            if (byteBudget < 4) { stopReason = "byte_limit"; break }
            val filePath = join(root, pending.relative)
            try {
                // 批量读取：命中本轮预取直接用；未命中（例如同一文件需要下一块）就再预取一批，
                // 未用到的预取项留在队列里，本轮结束时随游标一起丢弃。
                val queued = takeQueuedBatch(batchQueue, filePath, pending.offset)
                val fetched = if (queued != null) {
                    queued
                } else {
                    val batch = prefetchBatch(root, state, matcher, includeHidden, entryBudget, byteBudget, directoryPages, pending)
                    batch.drop(1).forEach { item -> batchQueue.addLast(item) }
                    batch.first()
                }
                val readError = fetched.error
                if (readError != null) {
                    // 逐文件失败：与原来的 stat/readBytes 异常路径给出同样的跳过原因。
                    backend.checkActive()
                    when (readError) {
                        is FileToolException -> {
                            if (readError.isGlobalFileFailure()) throw readError
                            skip(state, skipped, pending.relative, readError.code)
                        }
                        is IOException -> skip(state, skipped, pending.relative, "FILE_UNREADABLE")
                        is SecurityException -> skip(state, skipped, pending.relative, "FILE_ACCESS_DENIED")
                        else -> throw readError
                    }
                    state.pending = null
                    continue
                }
                // 读取前后元数据校验由后端在同一批里完成，等价于原来的二次 stat。
                if (fetched.changed) throw FileToolException("STALE_CURSOR", "搜索期间文件已变化，请重新开始搜索")
                val stat = fetched.stat ?: throw FileToolException("FILE_METADATA_INVALID", "系统返回的文件版本信息无效")
                if (stat.revision != pending.revision) throw FileToolException("STALE_CURSOR", "搜索中的文件已变化，请重新开始搜索")
                val chunk = reader.decode(stat, pending.offset, fetched.bytes)
                if (chunk.text.isEmpty()) { state.pending = null; continue }
                var position = 0
                var paused = false
                while (position < chunk.text.length) {
                    backend.checkActive()
                    if (position > 0 && timeExpired()) { stopReason = "time_limit"; paused = true; break }
                    val newline = chunk.text.indexOf('\n', position)
                    val end = if (newline >= 0) newline else chunk.text.length
                    val segment = chunk.text.substring(position, end)
                    val combined = pending.tail + segment
                    val found = if (pending.lineMatched) -1 else combined.indexOf(query, ignoreCase = !caseSensitive)
                    if (found >= 0) {
                        val snippetStart = (found - 100).coerceAtLeast(0)
                        val snippetEnd = (found + query.length + 100).coerceAtMost(combined.length)
                        val item = JSONObject().put("path", filePath).put("relative_path", pending.relative)
                            .put("line", pending.line).put("text", safeSubstring(combined, snippetStart, snippetEnd))
                            .put("match_offset_bytes", pending.offset - pending.tail.toByteArray(Charsets.UTF_8).size + combined.substring(0, found).toByteArray(Charsets.UTF_8).size)
                            .put("line_truncated", pending.tail.isNotEmpty() || snippetStart > 0 || snippetEnd < combined.length || (newline < 0 && !chunk.eof))
                        val cost = item.toString().length
                        if (matches.length() > 0 && outputChars + cost > MAX_MATCH_CHARS) {
                            stopReason = "output_limit"
                            paused = true
                            break
                        }
                        matches.put(item)
                        outputChars += cost
                        pending.lineMatched = true
                    }
                    val consumedEnd = if (newline >= 0) newline + 1 else end
                    val consumedBytes = chunk.text.substring(position, consumedEnd).toByteArray(Charsets.UTF_8).size
                    pending.offset += consumedBytes
                    byteBudget -= consumedBytes
                    position = consumedEnd
                    if (newline >= 0) {
                        pending.line++
                        pending.tail = ""
                        pending.lineMatched = false
                    } else {
                        pending.tail = safeSuffix(combined, (query.length - 1).coerceAtLeast(0))
                    }
                    if (matches.length() >= maxResults) { stopReason = "result_limit"; paused = true; break }
                }
                if (pending.offset >= stat.sizeBytes) state.pending = null
                if (paused) break
            } catch (error: FileToolException) {
                backend.checkActive()
                if (error.isGlobalFileFailure()) throw error
                skip(state, skipped, pending.relative, error.code)
                state.pending = null
            } catch (_: IOException) {
                backend.checkActive()
                skip(state, skipped, pending.relative, "FILE_UNREADABLE")
                state.pending = null
            } catch (_: SecurityException) {
                backend.checkActive()
                skip(state, skipped, pending.relative, "FILE_ACCESS_DENIED")
                state.pending = null
            }
        }
        val hasMore = state.pending != null || state.frames.isNotEmpty()
        if (hasMore && stopReason == "complete") stopReason = "result_limit"
        // partial/stop_reason 只描述这一轮扫描：scanned_root 说明本次实际扫的根路径，
        // 与 next_cursor 配合即可判断「还有哪里没扫到」。
        JSONObject().put("ok", true).put("tool", if (query == null) "glob_files" else "grep_files")
            .put("environment", backend.environment).put("identity", backend.identity).put("path", root)
            .put("scanned_root", root)
            .put("matches", matches).put("count", matches.length()).put("truncated", hasMore)
            .put("partial", hasMore || state.skipped > 0)
            .put("complete", !hasMore && state.skipped == 0).put("stop_reason", stopReason)
            .put("entries_scanned", MAX_ENTRIES - entryBudget).put("bytes_scanned", MAX_BYTES - byteBudget)
            .put("skipped_files", state.skipped - skippedBefore).put("total_skipped", state.skipped).put("skipped", skipped)
            .put("next_cursor", if (hasMore) encodeCursor(state, signature) else JSONObject.NULL)
            .withPathAlias(path, root, backend.environment)
    }

    private fun nextFile(root: String, state: State, matcher: FileToolGlob, includeHidden: Boolean, budget: Int, skipped: JSONArray, pages: MutableMap<String, CachedPage>, timeExpired: () -> Boolean): NextFile {
        var visited = 0
        while (state.frames.isNotEmpty() && visited < budget) {
            backend.checkActive()
            // 一次列表返回后至少消费一个条目，避免慢文件系统让同一游标反复停在原地。
            if (visited > 0 && timeExpired()) return NextFile(null, visited, timeLimited = true)
            val frame = state.frames.last()
            val cached = pages[frame.relative]
            if (cached != null && frame.offset == cached.offset + cached.page.entries.size && !cached.page.hasMore) {
                pages.remove(frame.relative)
                state.frames.removeAt(state.frames.lastIndex)
                continue
            }
            val page = try {
                if (cached != null && frame.offset >= cached.offset && frame.offset < cached.offset + cached.page.entries.size) cached
                else CachedPage(frame.offset, backend.list(join(root, frame.relative), frame.offset, minOf(64, budget - visited), frame.revision))
                    .also { pages[frame.relative] = it }
            } catch (error: FileToolException) {
                backend.checkActive()
                if (error.isGlobalFileFailure()) throw error
                skip(state, skipped, frame.relative, error.code)
                state.frames.removeAt(state.frames.lastIndex)
                continue
            } catch (_: IOException) {
                backend.checkActive()
                skip(state, skipped, frame.relative, "DIRECTORY_UNREADABLE")
                state.frames.removeAt(state.frames.lastIndex)
                continue
            }
            if (page.page.entries.isEmpty()) { state.frames.removeAt(state.frames.lastIndex); continue }
            val entry = page.page.entries[frame.offset - page.offset]
            frame.offset++
            visited++
            if (!includeHidden && entry.name.startsWith('.')) continue
            val relative = if (frame.relative.isEmpty()) entry.name else "${frame.relative}/${entry.name}"
            validateRelative(relative)
            if (entry.kind == FileToolKind.DIRECTORY) {
                if (state.frames.size >= MAX_DEPTH) { skip(state, skipped, relative, "DEPTH_LIMIT"); continue }
                state.frames += Frame(relative, 0, entry.revision)
            } else if (entry.kind == FileToolKind.FILE && matcher.matches(relative)) {
                return NextFile(Pending(relative, 0, 1, entry.revision), visited)
            } else if (entry.kind == FileToolKind.SYMLINK) {
                skip(state, skipped, relative, "SYMLINK_NOT_FOLLOWED")
            }
        }
        return NextFile(null, visited)
    }

    private fun skip(state: State, skipped: JSONArray, relative: String, reason: String) {
        state.skipped++
        if (skipped.length() < 10) skipped.put(JSONObject().put("relative_path", relative).put("reason", reason))
    }

    /** 队列里按「文件 + 起始字节」精确匹配：只有同一位置的预取结果才能复用。 */
    private fun takeQueuedBatch(queue: ArrayDeque<FileToolBatchRead>, path: String, offset: Long): FileToolBatchRead? {
        val index = queue.indexOfFirst { it.request.path == path && it.request.offset == offset }
        return if (index < 0) null else queue.removeAt(index)
    }

    /**
     * 预取一批待读文件：首项就是当前待读请求，其余是目录副本前瞻到的后续文件。
     *
     * 批量后端（见 ShellFileToolBackend.readBatch）用一次 shell 调用完成整批读取与读取前后校验，
     * 把逐文件的 3 次 exec 压成每批 1 次；本地后端默认逐文件回退，行为不变。
     *
     * 前瞻走的是游标状态的副本：不动真实条目预算、跳过计数与帧位置，因此
     * entries_scanned / skipped / partial / next_cursor 的含义与逐文件路径完全一致，
     * 只是「读了哪些文件」提前了一点（未用到的预取结果在下一轮重新读，不会跳过任何文件）。
     */
    private fun prefetchBatch(
        root: String,
        state: State,
        matcher: FileToolGlob,
        includeHidden: Boolean,
        entryBudget: Int,
        byteBudget: Int,
        pages: MutableMap<String, CachedPage>,
        current: Pending,
    ): List<FileToolBatchRead> {
        // 与逐文件路径同样的每文件上限：当前请求用当前字节预算，后续文件按同样上限预读。
        val limit = minOf(16_000, byteBudget)
        val requests = ArrayList<FileToolReadRequest>(BATCH_FILES)
        requests += FileToolReadRequest(join(root, current.relative), current.offset, limit)
        val shadow = State(state.frames.map { Frame(it.relative, it.offset, it.revision) }.toMutableList())
        val shadowSkipped = JSONArray()
        var shadowBudget = minOf(entryBudget, LOOK_AHEAD_ENTRIES)
        while (requests.size < BATCH_FILES && shadowBudget > 0) {
            val next = try {
                // 前瞻不做时间判断：它只影响批量大小，超时判定仍由真实循环负责。
                nextFile(root, shadow, matcher, includeHidden, shadowBudget, shadowSkipped, pages) { false }
            } catch (_: FileToolException) {
                // 副本上的目录变化/不可读只意味着「不再前瞻」；真实循环走到同一处时照常报错。
                backend.checkActive()
                break
            } catch (_: IOException) {
                break
            } catch (_: SecurityException) {
                break
            }
            shadowBudget -= next.visited
            val file = next.file ?: break
            requests += FileToolReadRequest(join(root, file.relative), 0, limit)
        }
        return fetchBatch(requests)
    }

    /**
     * 批量还是逐文件由后端能力决定（见 [FileToolBackend.supportsBatchRead]）：两条路的结果结构
     * 与错误分类完全一致，差别只是「一批 1 次调用」还是「每文件 3 次调用」。
     */
    private fun fetchBatch(requests: List<FileToolReadRequest>): List<FileToolBatchRead> =
        if (backend.supportsBatchRead) backend.readBatch(requests) else requests.map { backend.readOne(it) }

    private fun encodeCursor(state: State, signature: String): String {
        val frames = JSONArray()
        state.frames.forEach { frames.put(JSONObject().put("path", it.relative).put("offset", it.offset).put("revision", it.revision)) }
        val json = JSONObject().put("version", 1).put("signature", signature).put("frames", frames).put("skipped", state.skipped)
        state.pending?.let { json.put("pending", JSONObject().put("path", it.relative).put("offset", it.offset).put("line", it.line)
            .put("revision", it.revision).put("tail", it.tail).put("matched", it.lineMatched)) }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        if (encoded.length > MAX_CURSOR_CHARS) throw FileToolException("CURSOR_LIMIT", "搜索路径过深或过长，请选择更小的搜索目录")
        return encoded
    }

    private fun decodeCursor(cursor: String, signature: String): State = try {
        require(cursor.length <= MAX_CURSOR_CHARS)
        val json = JSONObject(String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8))
        require(json.getInt("version") == 1 && json.getString("signature") == signature)
        val array = json.getJSONArray("frames")
        require(array.length() <= MAX_DEPTH)
        val frames = mutableListOf<Frame>()
        for (index in 0 until array.length()) {
            val frame = array.getJSONObject(index)
            val relative = frame.getString("path").also(::validateRelative)
            val offset = frame.getInt("offset").also { require(it >= 0) }
            frames += Frame(relative, offset, frame.getString("revision"))
        }
        val pending = json.optJSONObject("pending")?.let {
            Pending(it.getString("path").also(::validateRelative), it.getLong("offset").also { offset -> require(offset >= 0) },
                it.getLong("line").also { line -> require(line > 0) }, it.getString("revision"),
                it.getString("tail").also { tail -> require(tail.length <= 512) }, it.getBoolean("matched"))
        }
        State(frames, pending, json.getInt("skipped").also { require(it >= 0) })
    } catch (_: IllegalArgumentException) {
        throw FileToolException("INVALID_CURSOR", "搜索游标无效或不属于当前搜索条件")
    } catch (_: org.json.JSONException) {
        throw FileToolException("INVALID_CURSOR", "搜索游标无效")
    }

    private fun validateRelative(value: String) {
        require(!value.startsWith('/') && value.indexOf('\u0000') < 0 && value.split('/').none { it == ".." || it == "." })
    }

    private fun join(root: String, relative: String): String = if (relative.isEmpty()) root else "${root.trimEnd('/')}/$relative"
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun safeSubstring(text: String, start: Int, end: Int): String {
        val safeStart = if (start > 0 && text[start].isLowSurrogate()) start - 1 else start
        val safeEnd = if (end < text.length && end > safeStart && text[end - 1].isHighSurrogate()) end - 1 else end
        return text.substring(safeStart, safeEnd)
    }
    private fun safeSuffix(text: String, count: Int): String = if (count == 0) "" else safeSubstring(text, (text.length - count).coerceAtLeast(0), text.length)

    private data class Frame(val relative: String, var offset: Int, val revision: String)
    private data class Pending(val relative: String, var offset: Long, var line: Long, val revision: String, var tail: String = "", var lineMatched: Boolean = false)
    private data class State(val frames: MutableList<Frame>, var pending: Pending? = null, var skipped: Int = 0)
    private data class NextFile(val file: Pending?, val visited: Int, val timeLimited: Boolean = false)
    private data class CachedPage(val offset: Int, val page: FileToolDirectoryPage)

    companion object {
        const val MAX_ENTRIES = 2_000
        const val MAX_BYTES = 256 * 1024
        const val MAX_MATCH_CHARS = 12_000
        const val MAX_CURSOR_CHARS = 16_384
        const val MAX_DEPTH = 24
        /** 每批预读的文件数：按 16KB/文件算，一批最多 128KB，仍在单轮字节预算之内。 */
        const val BATCH_FILES = 8
        /** 前瞻扫描的条目上限：只用来凑满一批文件，不该为批量把整棵树走完。 */
        const val LOOK_AHEAD_ENTRIES = 512
    }
}

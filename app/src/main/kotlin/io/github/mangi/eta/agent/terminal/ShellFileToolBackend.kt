package io.github.mangi.eta.agent.terminal

import java.io.File
import java.security.MessageDigest

/** 文件操作在所选命名空间内执行，Linux 的链接不能交给 Android 宿主解析。 */
internal class ShellFileToolBackend(
    override val environment: String,
    override val identity: String,
    private val cwd: String,
    private val home: String,
    private val ensureActive: () -> Unit = {},
    private val execute: (command: String, stdin: ByteArray?, maxOutputBytes: Int) -> OneShotShellResult,
) : FileToolBackend {
    override fun checkActive() = ensureActive()

    /** 一次 exec 能读完一批文件，因此对搜索开放批量读取（见 [readBatch]）。 */
    override val supportsBatchRead = true

    override fun resolve(path: String): String {
        checkActive()
        if ('\u0000' in path || path.length > 4096) fail("INVALID_PATH", "文件路径无效或过长")
        val raw = path.ifEmpty { cwd }
        // 双环境别名只改写绝对路径（见 FileToolPathAlias）；翻译发生在解析链接之前，
        // 翻译后的路径与直接写对名字时走完全相同的校验与链接解析。
        val aliased = FileToolPathAlias.translate(raw, environment)
        val absolute = when {
            aliased == "~" -> home
            aliased.startsWith("~/") -> "$home/${aliased.removePrefix("~/")}"
            aliased.startsWith('/') -> aliased
            else -> "$cwd/$aliased"
        }
        // 先让目标命名空间解析链接；提前折叠 link/.. 会改变内核实际访问的目录。
        val result = run("readlink -f -- ${shellQuote(absolute)}", maxOutputBytes = 8192, allowFailure = true)
        return if (result.exitCode == 0) {
            decode(result.output).removeSuffix("\n").takeIf { it.startsWith('/') }
                ?: fail("FILE_METADATA_INVALID", "系统返回的规范路径无效")
        } else absolute
    }

    override fun stat(path: String): FileToolStat {
        val result = run(statScript(shellQuote(path)), maxOutputBytes = MAX_STAT_BYTES)
        return parseStat(path, decode(result.output))
    }

    private fun statScript(quotedPath: String): String {
        return """
            if [ -L $quotedPath ]; then printf 'symlink\n'
            elif [ -f $quotedPath ]; then printf 'file\n'
            elif [ -d $quotedPath ]; then printf 'directory\n'
            elif [ -e $quotedPath ]; then printf 'other\n'
            else exit 41; fi
            LC_ALL=C stat -c $STAT_FORMAT -- $quotedPath
        """.trimIndent()
    }

    private fun parseStat(path: String, output: String): FileToolStat {
        val fields = output.split('\n', limit = 3)
        val kind = FileToolKind.entries.firstOrNull { it.wireName == fields.firstOrNull() }
            ?: fail("FILE_METADATA_INVALID", "无法识别系统返回的文件类型")
        val size = fields.getOrNull(1)?.toLongOrNull()
            ?: fail("FILE_METADATA_INVALID", "无法识别系统返回的文件大小")
        val metadata = fields.getOrNull(2)?.trimEnd('\n').orEmpty()
        val versionFields = metadata.split(':', limit = 6)
        if (size < 0 || versionFields.size != 6 ||
            versionFields.take(2).any { !it.matches(Regex("[0-9]+")) } ||
            versionFields[2].toLongOrNull() != size ||
            versionFields[3].toLongOrNull() == null || versionFields[4].toLongOrNull() == null ||
            versionFields[5].isBlank() || '?' in metadata || '%' in metadata || '\n' in metadata
        ) fail("FILE_METADATA_INVALID", "系统返回的文件版本信息无效")
        return FileToolStat(path, File(path).name.ifEmpty { "/" }, kind, size, digest(metadata.toByteArray()))
    }

    override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
        if (offset < 0 || limit !in 1..MAX_READ_BYTES) fail("INVALID_ARGUMENT", "读取范围超出限制")
        val blockOffset = offset / BLOCK_BYTES
        val prefix = (offset % BLOCK_BYTES).toInt()
        val blocks = (prefix + limit + BLOCK_BYTES - 1) / BLOCK_BYTES
        val quoted = shellQuote(path)
        val result = run(
            "[ -f $quoted ] || exit 42; [ -r $quoted ] || exit 43; " +
                "dd if=$quoted bs=$BLOCK_BYTES skip=$blockOffset count=$blocks",
            maxOutputBytes = blocks * BLOCK_BYTES,
        )
        val from = prefix.coerceAtMost(result.output.size)
        return result.output.copyOfRange(from, (from + limit).coerceAtMost(result.output.size))
    }

    /**
     * 批量读取：一次 exec 读完一批文件，顺带给出每个文件的读取前/后元数据。
     *
     * 逐文件路径需要 3 次 exec（stat → dd → 再次 stat 校验），大仓库里每次往返都是真实开销；
     * 这里把整批压成 1 次 shell 调用，并且**结果结构与逐文件路径完全一致**（同一套元数据格式与错误码），
     * 只是把「是否有变化」的判断放到同一个脚本里完成。
     */
    override fun readBatch(requests: List<FileToolReadRequest>): List<FileToolBatchRead> {
        if (requests.isEmpty()) return emptyList()
        if (requests.any { it.offset < 0 || it.limit !in 1..MAX_READ_BYTES }) fail("INVALID_ARGUMENT", "读取范围超出限制")
        val script = batchReadScript(requests).toByteArray(Charsets.UTF_8)
        // 输出预算 = 每请求的块对齐字节（上限 + 一块余量）+ 两段元数据 + 少量帧头；
        // 预算不足会让 run() 报 FILE_OUTPUT_LIMIT，所以这里必须覆盖最坏情况。
        val maxOutputBytes = requests.sumOf { it.limit + BLOCK_BYTES } + requests.size * (MAX_STAT_BYTES * 2 + 64) + 1024
        val result = run("sh -s", stdin = script, maxOutputBytes = maxOutputBytes)
        // 帧错位（写入期间文件被替换、大小变化等）时退回逐文件读取：慢，但结果与逐文件路径一致。
        return parseBatchOutput(result.output, requests) ?: requests.map { readOne(it) }
    }

    /**
     * 批量读取脚本。帧全部以 NUL 结尾，字节段按长度取用，因此文件名与内容里的空格、换行、
     * 单引号、中文都不会破坏分帧（路径经 shellQuote 单引号包裹后交给函数，不拼接引号字符串）。
     *
     * 每个文件的帧格式：
     *   ok       : "ok" 0 want 0 读取前元数据 0 字节[want] 0 读取后元数据 0
     *   notfound : "notfound" 0
     *   notfile  : "notfile" 0
     *   denied   : "denied" 0
     * 元数据格式与 [statScript] 完全一致，因此 [parseStat] 与逐文件路径共用同一套校验。
     */
    private fun batchReadScript(requests: List<FileToolReadRequest>): String = buildString {
        // POSIX sh 里没有直接可用的换行字面量：从临时变量里取出单个换行符，避免为此再起一个进程。
        append("eta_nl=$(printf 'x\\nx')\n")
        append("eta_nl=${'$'}{eta_nl%x}\n")
        append("eta_nl=${'$'}{eta_nl#x}\n")
        append("eta_read_stat() {\n")
        // 与 statScript 的唯一差别：文件在检查期间消失时 return 而不是 exit，一个文件的问题不中断整批。
        append("  if [ -L \"${'$'}1\" ]; then printf 'symlink\\n'\n")
        append("  elif [ -f \"${'$'}1\" ]; then printf 'file\\n'\n")
        append("  elif [ -d \"${'$'}1\" ]; then printf 'directory\\n'\n")
        append("  elif [ -e \"${'$'}1\" ]; then printf 'other\\n'\n")
        append("  else return 41; fi\n")
        append("  LC_ALL=C stat -c $STAT_FORMAT -- \"${'$'}1\"\n")
        append("}\n")
        append("eta_read_range() {\n")
        append("  if [ -L \"${'$'}1\" ] || [ ! -f \"${'$'}1\" ]; then\n")
        append("    if [ -e \"${'$'}1\" ] || [ -L \"${'$'}1\" ]; then printf 'notfile\\000'; else printf 'notfound\\000'; fi\n")
        append("    return 0\n")
        append("  fi\n")
        append("  if [ ! -r \"${'$'}1\" ]; then printf 'denied\\000'; return 0; fi\n")
        append("  eta_meta=$(eta_read_stat \"${'$'}1\" 2>/dev/null) || { printf 'notfile\\000'; return 0; }\n")
        append("  eta_size=${'$'}{eta_meta#*${'$'}eta_nl}\n")
        append("  eta_size=${'$'}{eta_size%%${'$'}eta_nl*}\n")
        append("  case ${'$'}eta_size in ''|*[!0-9]*) printf 'notfile\\000'; return 0;; esac\n")
        // 与 readBytes 完全相同的块对齐计算，保证批量路径返回的字节区间逐字节一致。
        append("  eta_block=$(( ${'$'}2 / $BLOCK_BYTES ))\n")
        append("  eta_prefix=$(( ${'$'}2 - eta_block * $BLOCK_BYTES ))\n")
        append("  eta_blocks=$(( (eta_prefix + ${'$'}3 + $BLOCK_BYTES - 1) / $BLOCK_BYTES ))\n")
        append("  eta_avail=$(( eta_size - eta_block * $BLOCK_BYTES ))\n")
        append("  [ \"${'$'}eta_avail\" -lt 0 ] && eta_avail=0\n")
        append("  eta_want=$(( eta_blocks * $BLOCK_BYTES ))\n")
        append("  [ \"${'$'}eta_want\" -gt \"${'$'}eta_avail\" ] && eta_want=${'$'}eta_avail\n")
        append("  printf 'ok\\000%s\\000%s\\000' \"${'$'}eta_want\" \"${'$'}eta_meta\"\n")
        append("  dd if=\"${'$'}1\" bs=$BLOCK_BYTES skip=${'$'}eta_block count=${'$'}eta_blocks 2>/dev/null\n")
        append("  printf '\\000'\n")
        append("  eta_read_stat \"${'$'}1\" 2>/dev/null\n")
        append("  printf '\\000'\n")
        append("}\n")
        requests.forEach { request ->
            append("eta_read_range ").append(shellQuote(request.path)).append(' ')
                .append(request.offset).append(' ').append(request.limit).append('\n')
        }
    }

    /** 解析批量脚本输出；返回 null 表示帧不完整（调用方退回逐文件读取），不在这里猜结果。 */
    private fun parseBatchOutput(output: ByteArray, requests: List<FileToolReadRequest>): List<FileToolBatchRead>? {
        var index = 0
        val results = ArrayList<FileToolBatchRead>(requests.size)
        for (request in requests) {
            val status = readBatchField(output, index) ?: return null
            index = status.second
            when (status.first) {
                "notfound" -> results += FileToolBatchRead(request, error = FileToolException("FILE_NOT_FOUND", "文件不存在或当前身份无法访问"))
                "notfile" -> results += FileToolBatchRead(request, error = FileToolException("NOT_REGULAR_FILE", "目标不是普通文件"))
                "denied" -> results += FileToolBatchRead(request, error = FileToolException("FILE_ACCESS_DENIED", "当前身份没有文件访问权限"))
                "ok" -> {
                    val wantField = readBatchField(output, index) ?: return null
                    index = wantField.second
                    val want = wantField.first.toIntOrNull()?.takeIf { it in 0..MAX_READ_BYTES } ?: return null
                    val beforeField = readBatchField(output, index) ?: return null
                    index = beforeField.second
                    if (want > output.size - index) return null
                    val payload = output.copyOfRange(index, index + want)
                    index += want
                    if (index >= output.size || output[index] != 0.toByte()) return null
                    index += 1
                    val afterField = readBatchField(output, index) ?: return null
                    index = afterField.second
                    val before = parseBatchStat(request.path, beforeField.first) ?: return null
                    val after = parseBatchStat(request.path, afterField.first)
                    results += FileToolBatchRead(
                        request = request,
                        stat = before,
                        bytes = sliceReadRange(payload, request),
                        // 读取后元数据缺失（文件在读取期间消失）同样按「已变化」处理，与二次 stat 的结论一致。
                        changed = after == null || after.revision != before.revision,
                    )
                }
                else -> return null
            }
        }
        return if (index == output.size) results else null
    }

    /** 读一个 NUL 结尾字段，返回（文本，下一个字段起点）；缺 NUL 或不是 UTF-8 都视为帧损坏。 */
    private fun readBatchField(bytes: ByteArray, from: Int): Pair<String, Int>? {
        var index = from
        while (index < bytes.size && bytes[index] != 0.toByte()) index += 1
        if (index >= bytes.size) return null
        val text = runCatching { decode(bytes.copyOfRange(from, index)) }.getOrNull() ?: return null
        return text to index + 1
    }

    private fun parseBatchStat(path: String, metadata: String): FileToolStat? =
        runCatching { parseStat(path, metadata) }.getOrNull()

    /** 与 [readBytes] 的收尾一致：payload 按块对齐读出，这里取回请求的 [prefix, prefix+limit) 区间。 */
    private fun sliceReadRange(payload: ByteArray, request: FileToolReadRequest): ByteArray {
        val from = (request.offset % BLOCK_BYTES).toInt().coerceAtMost(payload.size)
        return payload.copyOfRange(from, (from + request.limit).coerceAtMost(payload.size))
    }

    override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?): FileToolDirectoryPage {
        if (offset < 0 || limit !in 1..200) fail("INVALID_ARGUMENT", "目录分页范围无效")
        val before = stat(path)
        if (before.kind != FileToolKind.DIRECTORY) fail("NOT_DIRECTORY", "目标不是目录")
        if (expectedRevision != null && expectedRevision != before.revision) {
            fail("STALE_CURSOR", "目录已变化，请从第一页重新读取")
        }
        val quoted = shellQuote(path)
        val result = run(
            "[ -r $quoted ] && [ -x $quoted ] || exit 43; " +
                "find $quoted -mindepth 1 -maxdepth 1 -print0",
            maxOutputBytes = MAX_DIRECTORY_BYTES,
        )
        if (result.output.isNotEmpty() && result.output.last() != 0.toByte()) {
            fail("DIRECTORY_TOO_LARGE", "目录枚举超过预算，请缩小目录范围")
        }
        val names = decode(result.output).split('\u0000').filter(String::isNotEmpty)
        if (names.size > MAX_DIRECTORY_ENTRIES) fail("DIRECTORY_TOO_LARGE", "目录条目过多，请缩小范围")
        val sorted = names.sorted()
        val selected = statPage(sorted.drop(offset).take(limit))
        if (stat(path).revision != before.revision) fail("STALE_CURSOR", "目录读取期间已变化，请重新读取")
        val next = (offset.toLong() + selected.size).coerceAtMost(sorted.size.toLong()).toInt()
        return FileToolDirectoryPage(selected, next, next < sorted.size, before.revision)
    }

    /** 一页条目的元数据共用一次命名空间；路径经 stdin 传入，避免撑满 exec 参数。 */
    private fun statPage(paths: List<String>): List<FileToolStat> {
        if (paths.isEmpty()) return emptyList()
        val script = buildString {
            append("eta_file_stat() {\n")
            append(statScript("\"${'$'}1\""))
            append("\n}\n")
            paths.forEach { path ->
                append("eta_file_stat ").append(shellQuote(path)).append(" || exit ${'$'}?\n")
                append("printf '\\000'\n")
            }
        }
        val result = run(
            "sh -s",
            stdin = script.toByteArray(Charsets.UTF_8),
            maxOutputBytes = minOf(paths.size * MAX_STAT_BYTES, MAX_DIRECTORY_BYTES),
        )
        val records = decode(result.output).split('\u0000')
        if (records.size != paths.size + 1 || records.last().isNotEmpty()) {
            fail("FILE_METADATA_INVALID", "目录条目元数据不完整，请重新读取")
        }
        return paths.mapIndexed { index, path -> parseStat(path, records[index]) }
    }

    override fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String?,
        expectedSha256: String?,
    ): FileToolWriteResult {
        if (bytes.size > MAX_READ_BYTES) fail("FILE_TOO_LARGE", "单次写入内容超过限制")
        if (expectedSha256 != null && !expectedSha256.matches(Regex("[a-f0-9]{64}"))) {
            fail("INVALID_ARGUMENT", "内容版本格式无效")
        }
        val quoted = shellQuote(path)
        val parent = shellQuote(File(path).parent ?: "/")
        expectedRevision?.let {
            if (stat(path).revision != it) fail("FILE_CHANGED", "文件已变化，请重新读取后编辑")
        }
        val hashCheck = expectedSha256?.let {
            "eta_hash=${'$'}(sha256sum < $quoted) || exit 45; " +
                "[ \"${'$'}{eta_hash%% *}\" = ${shellQuote(it)} ] || exit 44; "
        }.orEmpty()
        // 保留既有 inode 的属主、模式和 SELinux 标签；如实报告非原子写入。
        run(
            "mkdir -p -- $parent || exit 43; " +
                "if [ -e $quoted ] && [ ! -f $quoted ]; then exit 42; fi; " +
                hashCheck + "cat ${if (append) ">>" else ">"} $quoted",
            stdin = bytes,
            maxOutputBytes = 8192,
        )
        return FileToolWriteResult(stat(path), atomic = false)
    }

    private fun run(
        command: String,
        stdin: ByteArray? = null,
        maxOutputBytes: Int,
        allowFailure: Boolean = false,
    ): OneShotShellResult {
        checkActive()
        val result = execute(command, stdin, maxOutputBytes)
        checkActive()
        when (result.exitCode) {
            SHELL_STDIN_FAILED_EXIT_CODE -> fail("FILE_INPUT_FAILED", "文件写入内容未能完整传输，请重新读取并确认文件状态")
            -3 -> fail("CANCELLED", "文件操作已取消")
            -2 -> fail("FILE_TIMEOUT", "文件操作超时")
            -1 -> fail("FILE_PROCESS_FAILED", "无法启动文件操作")
            41 -> fail("FILE_NOT_FOUND", "文件不存在或当前身份无法访问")
            42 -> fail("NOT_REGULAR_FILE", "目标不是普通文件")
            43 -> fail("FILE_ACCESS_DENIED", "当前身份没有文件访问权限")
            44 -> fail("FILE_CHANGED", "文件内容已变化，请重新读取后编辑")
            45 -> fail("FILE_HASH_UNAVAILABLE", "当前环境无法验证文件内容版本")
            0 -> Unit
            else -> if (!allowFailure || result.exitCode != 1) fail("FILE_COMMAND_FAILED", "文件操作失败（exit=${result.exitCode}）")
        }
        if (result.outputTruncated) fail("FILE_OUTPUT_LIMIT", "文件操作结果超过预算，请缩小范围")
        return result
    }

    private fun decode(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        fail("UNSUPPORTED_ENCODING", "文件名或元数据不是有效 UTF-8")
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fail(code: String, message: String): Nothing = throw FileToolException(code, message)

    private companion object {
        const val BLOCK_BYTES = 4096
        const val MAX_READ_BYTES = 512 * 1024
        const val MAX_DIRECTORY_BYTES = 1024 * 1024
        const val MAX_DIRECTORY_ENTRIES = 20_000
        const val MAX_STAT_BYTES = 8192

        /** 逐文件 stat 与批量读取共用同一段格式串，保证 revision 在两条路上可比。 */
        val STAT_FORMAT = shellQuote("%s\n%d:%i:%s:%Y:%Z:%y:%z")
    }
}

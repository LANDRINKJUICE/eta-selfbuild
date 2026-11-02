package io.github.mangi.eta.agent.terminal

import java.io.IOException
import org.json.JSONObject

/**
 * android 侧 [TerminalRuntime.ROOT_WORKSPACE_PATH] 与 linux 侧 [TerminalRuntime.LINUX_WORKSPACE_PATH]
 * 是同一物理目录的两个名字（后者是前者的 bind mount）。模型要在两种写法之间手算，很容易读写到错误的一侧，
 * 所以文件工具按本次调用的 environment 做单向翻译：
 *
 * - 只翻译绝对路径；相对路径仍按各自后端的 cwd 拼接，行为不变。
 * - 严格只认这两个 workspace 名字：`/data/local/tmp` 的兄弟目录（含 SharedFolderMounts.FORBIDDEN_ROOTS
 *   里那些禁区根）与工作区之外的路径一律不改写，别名不能把其他目录通过另一个名字露出来。
 * - 只翻译词法规范的写法（复用 SharedFolderMounts 的归一化）：带 `.` `..` `//` 的路径在替换后可能指向
 *   别的目录，宁可不翻译，交给原来的行为处理。
 *
 * 另外，调用方必须在后端自身的校验（readlink 解析、canonicalFile、以及调用方对结果的根目录检查）之前
 * 调用这里，翻译后的路径才会走与手写正确名字完全相同的校验，别名不会成为绕过访问边界的路径。
 */
internal object FileToolPathAlias {
    fun translate(path: String, environment: String): String {
        if (environment != "linux" && environment != "android") return path
        if (!path.startsWith('/')) return path
        val normalized = SharedFolderMounts.normalizeSourcePath(path) ?: return path
        if (normalized != path.trimEnd('/')) return path
        val translated = when (environment) {
            "linux" -> replaceRoot(normalized, TerminalRuntime.ROOT_WORKSPACE_PATH, TerminalRuntime.LINUX_WORKSPACE_PATH)
            else -> replaceRoot(normalized, TerminalRuntime.LINUX_WORKSPACE_PATH, TerminalRuntime.ROOT_WORKSPACE_PATH)
        }
        return if (translated == normalized) path else translated
    }

    private fun replaceRoot(path: String, from: String, to: String): String = when {
        path == from -> to
        path.startsWith("$from/") -> to + path.removePrefix(from)
        else -> path
    }
}

/** 发生了别名翻译时，结果里补上 requested_path（调用方原样传入）与 path（实际解析），否则保持既有结构。 */
internal fun JSONObject.withPathAlias(requested: String, resolved: String, environment: String): JSONObject =
    if (FileToolPathAlias.translate(requested, environment) == requested) this
    else put("requested_path", requested).put("path", resolved)

/** revision 是后端生成的不透明值，只在同一后端内比较；目录内容变化必须使其失效。 */
internal data class FileToolStat(
    val path: String,
    val name: String,
    val kind: FileToolKind,
    val sizeBytes: Long,
    val revision: String,
)

internal enum class FileToolKind(val wireName: String) {
    FILE("file"), DIRECTORY("directory"), SYMLINK("symlink"), OTHER("other"),
}

internal data class FileToolDirectoryPage(
    val entries: List<FileToolStat>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val revision: String,
)

internal data class FileToolWriteResult(
    val stat: FileToolStat,
    val atomic: Boolean,
)

internal class FileToolException(val code: String, message: String) : Exception(message)

/**
 * 这些错误说明整个后端不可用（取消、超时、身份失效），不能降级成「跳过这个文件」。
 * 逐文件读取与批量读取共用同一份判定，避免两条路的错误分类出现分歧。
 */
internal fun FileToolException.isGlobalFileFailure(): Boolean = code in GLOBAL_FILE_FAILURE_CODES

internal val GLOBAL_FILE_FAILURE_CODES = setOf(
    "STALE_CURSOR", "FILE_CHANGED", "ROOT_REQUIRED", "CANCELLED", "TERMINAL_CLOSED",
    "LINUX_ENVIRONMENT_NOT_READY", "LINUX_ENVIRONMENT_REQUIRES_ROOT", "INVALID_IDENTITY",
    "FILE_TIMEOUT", "FILE_PROCESS_FAILED",
)

/** 批量读取的一次请求：文件 + 起始字节 + 上限，语义与 [FileToolBackend.readBytes] 的参数一致。 */
internal data class FileToolReadRequest(val path: String, val offset: Long, val limit: Int)

/**
 * 批量读取的单文件结果。[stat] 是读取前元数据（失败时为 null），[bytes] 对应请求区间；
 * [changed] 表示读取前后元数据不一致（调用方按 STALE_CURSOR 处理）；
 * [error] 表示该文件单独失败，调用方按逐文件跳过处理；全局失败由实现直接抛出。
 */
internal data class FileToolBatchRead(
    val request: FileToolReadRequest,
    val stat: FileToolStat? = null,
    val bytes: ByteArray = ByteArray(0),
    val changed: Boolean = false,
    val error: Throwable? = null,
)

/**
 * 逐文件读取：等价于 stat → read → stat，语义与批量读取保持一致。
 * 默认批量实现与批量脚本帧错位时的退路都走这里，因此两条路返回同一种结果结构。
 */
internal fun FileToolBackend.readOne(request: FileToolReadRequest): FileToolBatchRead = try {
    val before = stat(request.path)
    val bytes = readBytes(request.path, request.offset, request.limit)
    val after = stat(request.path)
    FileToolBatchRead(request, before, bytes, changed = after.revision != before.revision)
} catch (error: FileToolException) {
    if (error.isGlobalFileFailure()) throw error
    FileToolBatchRead(request, error = error)
} catch (error: IOException) {
    FileToolBatchRead(request, error = error)
} catch (error: SecurityException) {
    FileToolBatchRead(request, error = error)
}

/** 参数中的路径均属于后端自身的命名空间；实现必须在每次操作时重新检查访问边界。 */
internal interface FileToolBackend {
    val environment: String
    val identity: String
    fun checkActive() = Unit
    fun resolve(path: String): String
    fun stat(path: String): FileToolStat
    fun readBytes(path: String, offset: Long, limit: Int): ByteArray

    /**
     * 批量读取能力开关。默认 false 时调用方逐文件读取：每次 stat/readBytes 都是对本对象的虚调用，
     * 逐文件覆写（计时、统计、故障注入类的测试替身，以及外层的委托包装）不会被绕过。
     * 只有真正把一批文件压成一次系统调用的后端（[ShellFileToolBackend]）才置 true 并覆写 [readBatch]。
     */
    val supportsBatchRead: Boolean get() = false

    /**
     * 批量读取：一次命名空间往返里读完多个文件并做读取前后校验。
     *
     * 默认实现逐文件回退到 [readOne]（本地后端一次调用就是一次系统调用，没有 shell 往返开销）；
     * shell 后端覆写成「每批 1 次 exec」，把逐文件 3 次 shell 调用压成每批 1 次。
     */
    fun readBatch(requests: List<FileToolReadRequest>): List<FileToolBatchRead> = requests.map { readOne(it) }

    /** offset 为目录原始枚举位置，不能在分页前过滤。顺序由后端保持，变动通过 revision 检测。 */
    fun list(path: String, offset: Int, limit: Int, expectedRevision: String? = null): FileToolDirectoryPage

    /**
     * expectedSha256 用于编辑时校验读到的完整旧内容；不匹配必须返回 FILE_CHANGED 且不写入。
     * expectedRevision 为可选元数据前置条件；atomic 只描述替换可见性，不承诺跨进程互斥。
     */
    fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String? = null,
        expectedSha256: String? = null,
    ): FileToolWriteResult
}

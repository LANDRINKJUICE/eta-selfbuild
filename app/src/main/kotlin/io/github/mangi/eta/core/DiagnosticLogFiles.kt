package io.github.mangi.eta.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把诊断日志落到文件，供事后排查。
 *
 * **为什么需要**：logcat 的环形缓冲在真机上只留一分钟左右（实测 3201 行 ≈ 56 秒）。
 * 出了问题回头去看，证据早就被冲掉了 —— 只能靠 `dumpsys` 里的快照猜，或者读源码猜路径。
 * 2026-11-04 排查「划掉卡片任务中断」「定时任务没调模型」两件事时，全都卡在这个点上。
 *
 * **设计取舍**：
 * - **异步写**：单线程 + 有界队列（[QUEUE_CAPACITY] 行），满了丢最旧的。日志绝不能拖慢业务。
 * - **按天 + 按大小滚动**：`eta-2026-11-04.log`、`eta-2026-11-04.1.log`，最多保留 [MAX_FILES] 份。
 * - **写入前脱敏**：密钥常见形态（`sk-`、`Bearer`、`api_key=`）替换成 `***`。
 * - 只记 info / warn / error：`debug` 在 Release 构建里本来就被内联删掉了。
 * - 多进程各写各的：文件名带进程名后缀，`:schedule` 不会和主进程抢同一个文件。
 */
internal object DiagnosticLogFiles {
    private const val DIR_NAME = "logs"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 6
    private const val QUEUE_CAPACITY = 2000
    private const val CRASH_TRACE_CHARS = 4000

    private val started = AtomicBoolean(false)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var directory: File? = null
    @Volatile private var processTag: String = "main"
    @Volatile private var day: String = ""
    @Volatile private var current: File? = null
    private var bytes: Long = 0L
    private var index: Int = 0

    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "eta-log-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    private val secretPatterns = listOf(
        Regex("sk-[A-Za-z0-9_-]{8,}") to "sk-***",
        Regex("(?i)bearer\\s+[A-Za-z0-9._-]{8,}") to "Bearer ***",
        // JSON 里值前面还多一个引号（`"api_key":"xxx"`），所以 [=:] 后面也要允许一个引号。
        Regex("(?i)((?:api[_-]?key|token|secret|password)[\"']?\\s*[=:]\\s*[\"']?)[A-Za-z0-9._-]{8,}") to "$1***",
    )

    /** 在 `Application.onCreate()` 里调一次；重复调用无副作用。 */
    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            val app = context.applicationContext
            processTag = app.getProcessNameTag()
            val dir = File(app.filesDir, DIR_NAME).apply { mkdirs() }
            directory = dir
            pruneOldFiles(dir)
        }.onFailure { failure ->
            AndroidAgentLogger.warn("DiagLog init failed: ${failure.javaClass.simpleName}")
        }
    }

    /** 日志目录（未初始化时为「未启用」）。 */
    fun directoryPath(): String = directory?.absolutePath.orEmpty()

    /** 当前进程已落盘的文件，按时间从新到旧。 */
    fun files(): List<File> {
        val dir = directory ?: return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.startsWith("eta-") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun append(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (directory == null) return
        val at = System.currentTimeMillis()
        val line = buildString {
            append(formatTime(at))
            append(' ')
            append(level)
            append(' ')
            append(tag)
            append(": ")
            append(redact(message))
            throwable?.let { error ->
                append(" | ")
                append(error.javaClass.simpleName)
                append(": ")
                append(redact(error.message.orEmpty()))
            }
        }
        runCatching { writer.execute { writeLine(line) } }
    }

    /**
     * 崩溃兜底：把堆栈同步写进日志，再交回系统默认处理器。
     *
     * 同步写是刻意的 —— 崩溃时进程随时会死，异步队列里的东西来不及落盘。
     */
    fun installCrashHandler() {
        if (directory == null) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeLine(
                    "${formatTime(System.currentTimeMillis())} F ${ModuleConfig.TAG}: " +
                        "uncaught in ${thread.name}: ${throwable.javaClass.name}: " +
                        redact(throwable.message.orEmpty()),
                )
                throwable.stackTraceToString().take(CRASH_TRACE_CHARS)
                    .lineSequence()
                    .forEach { writeLine("    $it") }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeLine(line: String) {
        val dir = directory ?: return
        val today = formatDay(System.currentTimeMillis())
        var target = current
        var rolled = false
        if (target == null || today != day) {
            day = today
            index = 0
            target = File(dir, "eta-$processTag-$today.log")
            current = target
            bytes = if (target.exists()) target.length() else 0L
            rolled = true
        } else if (bytes >= MAX_BYTES) {
            index += 1
            target = File(dir, "eta-$processTag-$day.$index.log")
            current = target
            bytes = 0L
            rolled = true
        }
        runCatching {
            target.appendText(line + "\n")
            bytes += line.length + 1L
        }
        if (rolled) pruneOldFiles(dir)
    }

    /** 只保留最近 [MAX_FILES] 份，避免长期运行把手机塞满。 */
    private fun pruneOldFiles(dir: File) {
        runCatching {
            val all = dir.listFiles { file -> file.isFile && file.name.startsWith("eta-") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            all.drop(MAX_FILES).forEach { stale -> stale.delete() }
        }
    }

    private fun formatTime(millis: Long): String = synchronized(timeFormat) {
        timeFormat.format(Date(millis))
    }

    private fun formatDay(millis: Long): String = synchronized(dayFormat) {
        dayFormat.format(Date(millis))
    }

    /** 抹掉常见密钥形态；宁可多抹，也不要让 token 躺在手机文件里。internal 便于单测。 */
    internal fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        secretPatterns.forEach { (pattern, replacement) -> out = pattern.replace(out, replacement) }
        return out
    }

    /** 进程名末段：`io.github.mangi.eta:schedule` → `schedule`，主进程 → `main`。 */
    private fun Context.getProcessNameTag(): String {
        val name = runCatching { android.app.Application.getProcessName() }.getOrNull().orEmpty()
        if (name.isBlank()) return "main"
        val suffix = name.substringAfterLast(':')
        return if (suffix == name) "main" else suffix
    }
}

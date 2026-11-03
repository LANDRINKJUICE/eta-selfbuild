package io.github.mangi.eta.agent.skill

import android.content.Context
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 把工具失败写进 `self-improving-agent` 技能的 `data/ERRORS.md`。
 *
 * 为什么做这个：`self-improving-agent/SKILL.md` 里写着「运行时会在工具失败后自动记录」，
 * 但代码里从来没有这段实现 —— 实测该技能目录下连 `data/` 都没有。
 * 结果就是「每次踩坑都要靠我当场记住」，跨会话必然丢。
 *
 * 设计约束（都踩过或考虑过）：
 * - **只记有诊断价值的失败**：`NO_OBSERVATION`、`ACCESSIBILITY_*` 这类是流程提示，不是坑，
 *   记进去会把真正有用的教训淹没。用 [IGNORED_CODES] 白名单挡掉。
 * - **不记敏感内容**：参数里可能含路径、消息正文。只记工具名 + 错误码 + 截断后的消息。
 * - **限流**：同一个工具 + 同一个错误码在 [DEDUP_WINDOW_MS] 内只记一次，
 *   避免循环重试时把文件刷爆（实测过连续失败会把 4MB 日志占满）。
 * - **绝不影响主流程**：任何异常都吞掉。记录失败不该让工具调用失败。
 * - **单文件上限**：超过 [MAX_BYTES] 时轮转成 `ERRORS.1.md`，只留最近一份。
 */
internal object SkillErrorJournal {
    private const val SKILL_ID = "self-improving-agent"
    private const val MAX_BYTES = 256 * 1024L
    private const val DEDUP_WINDOW_MS = 60_000L
    private const val MAX_MESSAGE_CHARS = 300
    /** 超过这个长度的返回一律不解析：错误信封都很短，长内容只可能是正常结果。 */
    private const val MAX_PARSE_CHARS = 8_192

    /** 这些错误码表示「先按流程做什么」，不是踩坑，不记录。 */
    internal val IGNORED_CODES = setOf(
        "NO_OBSERVATION",
        "ACCESSIBILITY_UNAVAILABLE",
        "ACCESSIBILITY_PROTECTION_UNAVAILABLE",
        "ACCESSIBILITY_REPAIR_TIMEOUT",
        "DANGEROUS_OPERATION_DENIED",
        "ROOT_REQUIRED",
        "PERMISSION_DENIED",
        "USER_CANCELLED",
    )

    /** 依赖这些源码才能判断的字段，解析失败时按「未知错误」处理。 */
    private const val OK_FIELD = "ok"
    private const val CODE_FIELD = "code"
    private const val MESSAGE_FIELD = "message"

    private val lastLoggedAt = HashMap<String, Long>()
    private val writeLock = Any()
    private val counter = AtomicLong()

    /**
     * 记录一次失败。`rawResult` 是工具返回的 content 原文（通常是错误 JSON）。
     * 返回是否真的写入，便于测试断言。
     */
    fun record(
        context: Context,
        logger: AgentLogger?,
        toolName: String,
        argumentsJson: String,
        rawResult: String,
    ): Boolean = runCatching {
        val parsed = parseError(rawResult) ?: return false
        val code = parsed.first
        val message = parsed.second

        if (code in IGNORED_CODES) return false

        val key = "$toolName|$code"
        val now = System.currentTimeMillis()
        synchronized(writeLock) {
            val last = lastLoggedAt[key] ?: 0L
            if (now - last < DEDUP_WINDOW_MS) return false
            lastLoggedAt[key] = now
            // 防止长期运行后 map 无限增长
            if (lastLoggedAt.size > 200) {
                lastLoggedAt.entries.removeAll { now - it.value > DEDUP_WINDOW_MS }
            }
        }

        val file = journalFile(context) ?: return false
        val entry = buildEntry(toolName, code, message, argumentsJson)
        synchronized(writeLock) {
            // mkdirs 必须先成功；此前没检查返回值，目录属主不对时静默失败，
            // 表现为「功能装了但从不工作」（实测：self-improving-agent 目录属主是 root，
            // Eta 以 u0_a435 运行，ENOENT 一直重试一直失败）。
            val dir = file.parentFile
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                runCatching {
                    logger?.warn("SkillErrorJournal 无法创建目录 ${dir.absolutePath}（属主或权限不对，Eta uid 需要写权限）")
                }
                return false
            }
            rotateIfNeeded(file)
            file.appendText(entry)
        }
        counter.incrementAndGet()
        true
    }.getOrElse { throwable ->
        // 记录失败绝不能影响工具调用本身。
        runCatching { logger?.warn("SkillErrorJournal 写入失败：${throwable.message}") }
        false
    }

    /** 已记录条数，供健康检查展示。 */
    fun recordedCount(): Long = counter.get()

    private fun journalFile(context: Context): File? = runCatching {
        File(SkillRuntime.skillsRoot(context), "$SKILL_ID/data/ERRORS.md")
    }.getOrNull()

    /**
     * 解析错误 JSON。只有 `ok == false` 才算失败；
     * 非 JSON（例如终端原始文本）或 `ok` 缺失时，只在内容明显是错误码形态时才记录，
     * 否则普通文本输出会被误判为失败（踩过：`ls` 的正常输出被当成错误）。
     *
     * 提成 internal 是为了能在纯 JVM 单测里覆盖（本机 aarch64 跑不了 Robolectric）。
     */
    internal fun parseError(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        // 大结果（读文件、长日志）不可能是错误信封，跳过解析，避免每次成功调用都白白解析几 MB。
        if (trimmed.length > MAX_PARSE_CHARS) return null
        return runCatching {
            val json = org.json.JSONObject(trimmed)
            if (json.optBoolean(OK_FIELD, true)) return null
            val code = json.optString(CODE_FIELD).ifBlank { "UNKNOWN" }
            val message = json.optString(MESSAGE_FIELD).ifBlank { trimmed }
            code to message
        }.getOrNull()
    }

    private fun buildEntry(
        toolName: String,
        code: String,
        message: String,
        argumentsJson: String,
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(java.util.Date())
        val shortArgs = argumentsJson
            .replace('\n', ' ')
            .take(80)
        return buildString {
            appendLine()
            appendLine("## $stamp · $toolName · $code")
            appendLine("- 工具：$toolName")
            appendLine("- 错误码：$code")
            appendLine("- 消息：${message.replace('\n', ' ').take(MAX_MESSAGE_CHARS)}")
            if (shortArgs.isNotBlank() && shortArgs != "{}") {
                appendLine("- 参数：`$shortArgs`")
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val rotated = File(file.parentFile, "ERRORS.1.md")
        runCatching {
            if (rotated.exists()) rotated.delete()
            file.renameTo(rotated)
        }
    }
}

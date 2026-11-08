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
    /** 有些工具用 error 字段放错误码/文本，一并兜底。 */
    private const val ERROR_FIELD = "error"
    private const val ERROR_MESSAGE_FIELD = "error_message"
    private const val STATUS_FIELD = "status"
    /** terminal 类工具把真正的失败原因放在这里 */
    private const val STDERR_FIELD = "stderr"
    private const val EXIT_CODE_FIELD = "exit_code"

    /** 同一「工具 + 错误码」累计到这个次数就提炼成一条规则。 */
    private const val PROMOTE_THRESHOLD = 3

    private val LESSONS_HEADER = buildString {
        appendLine("# 高频失败模式（自动提炼）")
        appendLine()
        appendLine("> 同一「工具 + 错误码」累计出现 ≥$PROMOTE_THRESHOLD 次时自动写入本文件。")
        appendLine("> 这里只有**观察到的事实**（次数、典型消息）；应对办法要结合当时上下文判断，不要当结论照抄。")
        appendLine()
    }

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
        maybePromote(context, logger, toolName, code, message)
        counter.incrementAndGet()
        true
    }.getOrElse { throwable ->
        // 记录失败绝不能影响工具调用本身。
        runCatching { logger?.warn("SkillErrorJournal 写入失败：${throwable.message}") }
        false
    }

    /** 已记录条数，供健康检查展示。 */
    fun recordedCount(): Long = counter.get()

    /**
     * 失败升级：同一「工具 + 错误码」反复出现时，从流水账里提炼成一条规则。
     *
     * 动机（2026-11-04）：ERRORS.md 是**流水账**，越写越长、没人会从头读；真正该被下一次
     * 开工看到的，是「这个坑踩过 N 次、典型长什么样」。所以到阈值就往 LESSONS.md 写一条。
     *
     * 只写**观察到的事实**（次数 + 典型消息），不编造应对办法 —— 那是模型该结合上下文判断的。
     */
    private fun maybePromote(
        context: Context,
        logger: AgentLogger?,
        toolName: String,
        code: String,
        message: String,
    ) {
        runCatching {
            // 没有信息量的码不值得提炼，否则 LESSONS.md 会变成第二个流水账。
            if (code.isBlank() || code == "UNKNOWN" || code == "failed") return
            val journal = journalFile(context) ?: return
            if (!journal.exists()) return
            val count = countOccurrences(journal.readText(), "· $toolName · $code")
            if (count < PROMOTE_THRESHOLD) return

            val lessons = lessonsFile(context) ?: return
            val dir = lessons.parentFile
            if (dir != null && !dir.exists() && !dir.mkdirs()) return
            val existing = if (lessons.exists()) lessons.readText() else LESSONS_HEADER
            val heading = "## $toolName · $code"
            if (existing.contains(heading)) return

            val entry = buildString {
                appendLine()
                appendLine("$heading（累计 $count 次）")
                appendLine("- 典型消息：${message.take(200)}")
            }
            lessons.writeText(existing + entry)
        }.onFailure { throwable ->
            runCatching { logger?.warn("SkillErrorJournal 提炼规则失败：${throwable.message}") }
        }
    }

    /** 子串出现次数。用 indexOf 循环而不是 windowed：后者会为长文件造一堆临时字符串。 */
    internal fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun lessonsFile(context: Context): File? = runCatching {
        File(SkillRuntime.skillsRoot(context), "$SKILL_ID/data/LESSONS.md")
    }.getOrNull()

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
            if (json.optBoolean(OK_FIELD, true)) {
                // terminal 的语义是 ok=true 表示「命令跑起来了」，成不成要看 exit_code。
                // 之前只看 ok，导致「cd 到不存在的目录」这类失败完全记不进来（实测漏了 11 条）。
                val exitCode = json.optInt(EXIT_CODE_FIELD, 0)
                if (exitCode == 0) return null
            }
            // 错误码优先取顶层的 code；取不到就用原始 envelope 的 code（如 terminal 用 tool/action 定位），
            // 都没有时才用 UNKNOWN —— 全塞 UNKNOWN 的话这条记录没有诊断价值。
            val code = json.optString(CODE_FIELD)
                .ifBlank { json.optString(ERROR_FIELD) }
                .ifBlank { json.optString(STATUS_FIELD) }
                .ifBlank { "UNKNOWN" }
            // 消息绝不 fallback 到整段 JSON：实测那样会把几 KB 原始 envelope 抄进日志，把文件搞脏。
            // 优先取 stderr —— terminal 类工具失败时真正的线索在这里（「can't cd to /workspace」
            // 这类），只记 code 会得到一串毫无用处的 "failed"（实测 15 条记录里 11 条是这样）。
            val stderr = json.optString(STDERR_FIELD).trim()
            val message = when {
                stderr.isNotBlank() -> stderr
                json.optString(MESSAGE_FIELD).isNotBlank() -> json.optString(MESSAGE_FIELD)
                json.optString(ERROR_MESSAGE_FIELD).isNotBlank() -> json.optString(ERROR_MESSAGE_FIELD)
                else -> code
            }
            code to message.replace(Regex("\\s+"), " ").take(MAX_MESSAGE_CHARS)
        }.getOrNull()
    }

    private fun buildEntry(
        toolName: String,
        code: String,
        message: String,
        argumentsJson: String,
    ): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(java.util.Date())
        // 参数里的凭据必须脱敏（2026-11-08 审查发现）：manage_provider / manage_mcp 的
        // 参数里会带 api_key / bearer_token 原文，而 ERRORS.md 是可被 skills_read_resource
        // 读回、也会进备份的 markdown 文件。宁可少记信息，不能落明文。
        val shortArgs = redactSecrets(argumentsJson)
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

    /** 把 JSON 里形如 "key":"value" 的凭据值换成 <redacted>。 */
    private fun redactSecrets(raw: String): String {
        if (raw.isBlank()) return raw
        return SECRET_FIELD.replace(raw) { match ->
            val name = match.groupValues[1]
            "\"$name\":\"<redacted>\""
        }
    }

    /** 匹配 "api_key":"..." / "bearer_token":"..." 这类键值对（值里允许转义引号）。 */
    private val SECRET_FIELD = Regex(
        "\"(api_key|bearer_token|token|secret|password|apiKey)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"",
        RegexOption.IGNORE_CASE,
    )

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val rotated = File(file.parentFile, "ERRORS.1.md")
        runCatching {
            if (rotated.exists()) rotated.delete()
            file.renameTo(rotated)
        }
    }
}

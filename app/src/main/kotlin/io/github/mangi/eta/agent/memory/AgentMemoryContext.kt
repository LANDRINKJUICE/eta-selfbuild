package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
    /**
     * 从核心段里识别出的「可能已过期」提示，单独注入。
     * 为什么单独放：核心段原文要保持逐字节不变（有测试锁死，也避免破坏已写好的记忆），
     * 而新鲜度是我们算出来的、不属于用户写的内容，附加在核心里容易被误当成原文。
     */
    val staleHints: List<String> = emptyList(),
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val core = extractCore(snapshot.content)
        val headings = snapshot.content.lineSequence()
            .filter { line -> HEADING.matches(line.trimEnd()) }
            .joinToString("\n")
            .take(MAX_HEADING_INDEX_CHARS)
        val fitted = fitCore(core, coreBudget)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = fitted,
            coreTruncated = fitted.length < core.length,
            headingIndex = headings,
            coreBudgetChars = coreBudget,
            staleHints = staleHints(core),
        )
    }

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    private fun extractCore(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val start = lines.indexOfFirst { it.trim() == CORE_HEADING }
        if (start < 0) return ""
        val end = ((start + 1) until lines.size)
            .firstOrNull { index -> lines[index].startsWith("# ") }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    /**
     * 超预算时的取舍：不再从尾部一刀切，而是按块的优先级保留。
     *
     * 为什么改：原来的 `take(budget)` 是纯截断，谁排在前面谁留下。
     * 但记忆里最要紧的是「用户的长期约定」和「绝对不能违反的铁律」，
     * 这些如果因为写在后面就被砍掉，机制就等于没起作用。
     *
     * 优先级：[铁律] > [偏好] > 其余（保持原顺序）。
     * 不超预算时原样返回，一个字符都不动——保证已有记忆的注入结果完全兼容。
     */
    private fun fitCore(core: String, budget: Int): String {
        if (core.length <= budget) return core
        val blocks = splitBlocks(core)
        if (blocks.isEmpty()) return core.take(budget)

        // 只有最前面那块（`# 核心记忆` 标题区）算「前言」，无条件保留。
        // 其余无标签块参与正常竞争（优先级最低）——否则一个巨大的普通块会把
        // preamble 撑爆，连带把排在后面的铁律挤掉。
        val preambleText = blocks.firstOrNull()?.takeIf { it.tag == null }?.text ?: ""
        val sections = blocks.drop(if (preambleText.isEmpty()) 0 else 1)

        val ordered = sections.sortedBy { block ->
            when (block.tag) {
                TAG_RULE -> 0
                TAG_PREF -> 1
                else -> 2
            }
        }
        val kept = LinkedHashSet<Block>()
        var used = preambleText.length
        for (block in ordered) {
            if (used + block.text.length > budget) continue
            kept += block
            used += block.text.length
        }
        // 极端情况：最高优先级的块自己就超过预算。至少保住它，宁可让它被截断，也不能整段丢掉。
        if (kept.isEmpty() && ordered.isNotEmpty()) {
            val head = ordered.first()
            val room = (budget - preambleText.length).coerceAtLeast(0)
            return (preambleText + head.text.take(room)).take(budget)
        }
        if (kept.isEmpty()) return core.take(budget)

        // 按原始顺序拼回去，保持可读性；被舍弃的块给一行提示，避免我误以为记忆就这么多。
        val builder = StringBuilder(preambleText)
        var dropped = 0
        for (block in sections) {
            if (block in kept) {
                builder.append(block.text)
            } else {
                dropped++
            }
        }
        if (dropped > 0) {
            builder.append("\n[另有 $dropped 段核心记忆超出预算未注入，可用 memory_get 按标题取回]")
        }
        return builder.toString().take(budget + DROP_NOTICE_ALLOWANCE)
    }

    /**
     * 把核心段切成「标题块」。只认 `## [xxx]` 这种带方括号的块；
     * 不带方括号的内容整体归为一个普通块，保持原行为。
     *
     * 块边界：遇到任意 `##` 标题都结束当前块。
     * 为什么不能只看带标签的标题：`## 普通记录` 这种不带方括号的标题如果不切断，
     * 它下面的内容会被算进上一个带标签的块里，导致整块超预算时连铁律一起被丢掉。
     */
    private fun splitBlocks(core: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val current = StringBuilder()
        var currentTag: String? = null
        fun flush() {
            if (current.isNotEmpty()) blocks += Block(currentTag, current.toString())
            current.setLength(0)
        }
        for (line in core.lineSequence()) {
            val tag = TAGGED_BLOCK.find(line)?.groupValues?.get(1)?.lowercase()
            if (tag != null) {
                flush()
                currentTag = tag
                current.append(line).append('\n')
            } else if (ANY_BLOCK_HEADING.matches(line)) {
                // 不带标签的二级标题：它自己是普通块的开始，同时切断上一个带标签块。
                flush()
                currentTag = null
                current.append(line).append('\n')
            } else {
                current.append(line).append('\n')
            }
        }
        flush()
        return blocks.filter { it.text.isNotBlank() }
    }

    /**
     * 找出「会过期」的块，按里面的日期算年龄。
     *
     * 为什么非做不可：记忆里写着「设备装的是 X 版本」，我当时是对的，
     * 一周后就是错的，而我会把它当事实去推理。靠我自律记得核实不现实，
     * 只能让系统把「这条放旧了」直接写进我的上下文。
     */
    private fun staleHints(core: String): List<String> {
        val hints = mutableListOf<String>()
        for (block in splitBlocks(core)) {
            val tag = block.tag ?: continue
            val limit = when (tag) {
                TAG_STATUS -> STATUS_STALE_DAYS
                TAG_KNOWLEDGE -> KNOWLEDGE_STALE_DAYS
                else -> continue
            }
            val dates = DATE.find(block.text)?.let { it.groupValues[1] } ?: continue
            val days = daysSince(dates) ?: continue
            if (days >= limit) {
                val title = block.text.lineSequence().firstOrNull()
                    ?.removePrefix("##")?.trim()?.take(40) ?: tag
                hints += "「$title」里的日期是 $dates，距今 $days 天，可能已过期，用前先核实"
            }
        }
        return hints
    }

    /** 只用 java.time，避免引入 Android API（单测在纯 JVM 上跑）。 */
    private fun daysSince(date: String): Long? = runCatching {
        val parsed = java.time.LocalDate.parse(date)
        val now = java.time.LocalDate.now()
        java.time.temporal.ChronoUnit.DAYS.between(parsed, now)
    }.getOrNull()

    private data class Block(val tag: String?, val text: String)

    /** 索引到三级标题：更细的结构也能被主 Agent 看到，才能用 memory_get 精确取回，而不是整份重读。 */
    private val HEADING = Regex("^#{1,3}\\s+.+$")
    /** 带标签的块标题，例如 `## [铁律] 安装前必须问`。方括号里只允许中英文短词。 */
    private val TAGGED_BLOCK = Regex("^##\\s*\\[([A-Za-z\\u4e00-\\u9fa5]{1,12})\\]")
    /** 任意二级标题，用来切断上一个块的归属范围。 */
    private val ANY_BLOCK_HEADING = Regex("^##\\s+.*$")
    private val DATE = Regex("(20\\d{2}-\\d{2}-\\d{2})")
    private const val CORE_HEADING = "# 核心记忆"
    private const val TAG_RULE = "铁律"
    private const val TAG_PREF = "偏好"
    private const val TAG_STATUS = "状态"
    private const val TAG_KNOWLEDGE = "知识"
    /** 状态类 7 天：项目进度变化快，一周以上就是旧消息。 */
    private const val STATUS_STALE_DAYS = 7L
    /** 知识类 30 天：技术结论相对稳定，但一个月也该复核。 */
    private const val KNOWLEDGE_STALE_DAYS = 30L
    /** 允许「未注入提示」那一行略微超出预算，免得它把正文挤掉。 */
    private const val DROP_NOTICE_ALLOWANCE = 120
    /** 窗口未知时用于按比例分配记忆、世界书等注入预算的保守基准，不参与压缩判定。 */
    const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 64_000
    /** 索引本身只占很小一部分上下文，放宽到 16_000 让长记忆的标题目录不至于被腰斩。 */
    private const val MAX_HEADING_INDEX_CHARS = 16_000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}

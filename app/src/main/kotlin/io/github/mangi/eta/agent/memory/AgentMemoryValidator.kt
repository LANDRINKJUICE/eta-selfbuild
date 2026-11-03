package io.github.mangi.eta.agent.memory

/**
 * 记忆写入前的格式校验。
 *
 * 为什么需要它：`[状态]` 块的价值全靠「带日期」撑着 —— 没有日期，
 * 过期提示（`<memory_stale>`）永远不会触发，机制等于空转。
 * 而"每次写记忆都记得加日期"是做不到的（实测：我自己就漏过），
 * 所以必须在写入时强制，而不是靠事后自觉。
 *
 * **只校验新增内容，不回溯已存在的正文**：
 * `replace_range` 可能只改中间几行，拿全文去校验会把历史遗留的格式问题变成写不进去。
 *
 * 校验规则（宽松，不制造麻烦）：
 * - `## [状态]` 块内必须出现 `YYYY-MM-DD`，且日期在标题行或块内任意位置都算。
 * - 其他标签不校验。
 * - 无标签内容不校验。
 * - 空内容（删除操作）不校验。
 */
internal object AgentMemoryValidator {

    internal data class Violation(val message: String, val hint: String)

    /** 校验将要写入的片段。返回 null 表示通过。 */
    internal fun validate(segment: String): Violation? {
        if (segment.isBlank()) return null
        val statusBlocks = extractStatusBlocks(segment)
        if (statusBlocks.isEmpty()) return null

        val missing = statusBlocks.filterNot { DATE.containsMatchIn(it) }
        if (missing.isEmpty()) return null

        val title = missing.first().lineSequence().firstOrNull()
            ?.removePrefix("##")?.trim()?.take(40)
            ?: "[状态]"
        return Violation(
            message = "「$title」这个 [状态] 块没有日期",
            hint = "[状态] 是会变的信息，必须带 YYYY-MM-DD（今天写作 ${today()}），" +
                "否则过期提示不会生效。改写成类似「## [状态] ${today()} 项目概况」，或改用 [知识]/[偏好]。",
        )
    }

    /** 只取 `## [状态]` 开头的块，遇到下一个 `##` 标题即结束。 */
    private fun extractStatusBlocks(content: String): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var inStatus = false
        fun flush() {
            if (inStatus && current.isNotEmpty()) blocks += current.toString()
            current.setLength(0)
        }
        for (line in content.lineSequence()) {
            val isStatusHeading = STATUS_HEADING.find(line) != null
            val isAnyHeading = ANY_HEADING.matches(line)
            if (isStatusHeading) {
                flush()
                inStatus = true
                current.append(line).append('\n')
            } else if (isAnyHeading) {
                flush()
                inStatus = false
                current.append(line).append('\n')
            } else if (inStatus) {
                current.append(line).append('\n')
            }
        }
        flush()
        return blocks
    }

    internal fun today(): String = java.time.LocalDate.now().toString()

    private val STATUS_HEADING = Regex("^##\\s*\\[状态\\]")
    private val ANY_HEADING = Regex("^##\\s+.*$")
    private val DATE = Regex("20\\d{2}-\\d{2}-\\d{2}")
}

package io.github.mangi.eta.agent.style

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 让模型给「自动分类没命中」的技能起一个分组名。
 *
 * 为什么需要：本地关键词表能覆盖常见的几类，剩下的（比如 `bilibili-download`）没有规律，
 * 硬编码只会全塞进「其他」。交给模型起 2~4 字的中文组名更实用，成本也低：
 * **只在出现新技能时问一次**，结果缓存下来，之后不再请求。
 *
 * 三条安全边界：
 * 1. 关掉所有工具，只让它输出文本（不给它动手机的机会）
 * 2. 超时 45 秒，失败就回落「其他」
 * 3. 严格解析 JSON，解析不出来也回落，绝不让模型输出直接当组名用（防注入奇怪的字符）
 */
internal object SkillGroupClassifier {
    const val FALLBACK_GROUP = "其他"

    /** 允许的图标类别，UI 侧按这个映射图标与颜色。 */
    val ICON_KEYS = listOf("dev", "system", "network", "media", "doc", "self", "other")

    private const val TIMEOUT_SECONDS = 45L

    /** 一条待分类的技能（只取会进 prompt 的字段，避免把整个 UI 模型传进来）。 */
    internal data class Candidate(val id: String, val name: String, val description: String)

    internal data class Result(val group: String, val icon: String)

    /**
     * 给一批技能分类。
     *
     * @return 技能 id → 分组；空 map 表示这次没拿到（调用方回落「其他」）
     */
    suspend fun classify(
        context: android.content.Context,
        candidates: List<Candidate>,
    ): Map<String, Result> {
        if (candidates.isEmpty()) return emptyMap()
        val config = runCatching { RuntimeConfigRepository.currentRuntimeConfig() }
            .onFailure { failure ->
                AndroidAgentLogger.warn(
                    "Skill group classify: config unavailable (${failure.javaClass.simpleName})",
                )
            }
            .getOrNull()
            ?: return emptyMap()

        val client = AgentRuntimeClient(context.applicationContext, AndroidAgentLogger)
        val request = AgentRuntimeWire.RunRequest(
            runId = "skill-group-${UUID.randomUUID()}",
            prompt = buildPrompt(candidates),
            config = config.copy(
                // 分类只需要文本，不给工具：避免它顺手去打开应用或改文件。
                terminalTools = false,
                browserTools = false,
                deviceDirectTools = false,
                deviceSensitiveReadTools = false,
                deviceSensitiveActionTools = false,
                subagentTools = false,
                interactionTools = false,
            ),
            images = emptyList(),
        )
        val result = runCatching {
            client.run(request = request, onEvent = { }, timeoutSeconds = TIMEOUT_SECONDS)
        }.onFailure { failure ->
            AndroidAgentLogger.warn(
                "Skill group classify failed: ${failure.javaClass.simpleName}",
            )
        }.getOrNull() ?: return emptyMap()

        if (!result.ok) {
            AndroidAgentLogger.warn("Skill group classify: model returned not ok")
            return emptyMap()
        }
        return parse(result.content, candidates)
    }

    private fun buildPrompt(candidates: List<Candidate>): String = buildString {
        appendLine("给下面每个技能起一个 2~4 个汉字的**中文分组名**，并从这些类别里选一个图标：")
        appendLine(ICON_KEYS.joinToString("、"))
        appendLine("类别含义：dev=开发构建，system=系统设备，network=网络代理，media=音视频图像，")
        appendLine("doc=写作文档，self=自我维护与技能管理，other=其他。")
        appendLine()
        appendLine("规则：")
        appendLine("1. 同一领域的技能尽量用同一个分组名（例如两个都跟视频下载有关，就都用「视频下载」）。")
        appendLine("2. 分组名要短，不要英文缩写，不要标点。")
        appendLine("3. 只输出 JSON 数组，不要解释、不要代码块。格式：")
        appendLine("""[{"id":"技能id","group":"分组名","icon":"类别"}]""")
        appendLine()
        appendLine("技能列表：")
        candidates.forEach { candidate ->
            append("- id=").append(candidate.id)
            append(" 名称=").append(candidate.name.take(40))
            if (candidate.description.isNotBlank()) {
                append(" 描述=").append(candidate.description.replace('\n', ' ').take(120))
            }
            appendLine()
        }
    }

    /**
     * 解析模型输出。
     *
     * 容错策略：先找第一个 `[` 到最后一个 `]`，再逐条校验；
     * 组名与类别都要过白名单/长度检查，不合格的条目直接丢弃（调用方回落「其他」）。
     */
    internal fun parse(raw: String, candidates: List<Candidate>): Map<String, Result> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyMap()
        val known = candidates.map { it.id }.toSet()
        return runCatching {
            val array = JSONArray(raw.substring(start, end + 1))
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id !in known) continue
                    val group = sanitizeGroup(item.optString("group"))
                    if (group.isBlank()) continue
                    val icon = item.optString("icon").trim().lowercase()
                        .takeIf { it in ICON_KEYS } ?: "other"
                    put(id, Result(group, icon))
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** 组名清洗：去掉标点与控制字符，限 8 字，空则丢弃。 */
    private fun sanitizeGroup(raw: String): String = raw
        .replace(Regex("[\\p{P}\\s\\u0000-\\u001f]"), "")
        .take(8)
        .trim()

}

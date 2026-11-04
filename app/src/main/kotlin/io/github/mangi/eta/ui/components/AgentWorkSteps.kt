package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi

/** 工作过程卡片内的一个时间线节点：单条消息，或连续调用同一工具形成的分组。 */
@Immutable
internal sealed interface AgentWorkStep {
    val key: String

    data class Single(val message: AgentChatMessageUi) : AgentWorkStep {
        override val key: String = message.id
    }

    data class ToolGroup(
        val toolName: String,
        val calls: List<ToolActivityMessageUi>,
    ) : AgentWorkStep {
        override val key: String = "group-${calls.first().id}"
        val running: Boolean get() = calls.any { it.status == ToolActivityStatusUi.Running }
        val failedCount: Int get() = calls.count { it.status == ToolActivityStatusUi.Failed }
    }
}

/**
 * Agent 常连续读取多个文件或多次观察屏幕；同名工具连续出现时合并为一个节点，
 * 时间线只保留“做了什么”的层级，细节按需展开。浏览器条目携带实时预览，保持独立。
 */
internal fun List<AgentChatMessageUi>.toWorkSteps(): List<AgentWorkStep> = buildList {
    val run = mutableListOf<ToolActivityMessageUi>()

    fun flushRun() {
        when (run.size) {
            0 -> Unit
            1 -> add(AgentWorkStep.Single(run.single()))
            else -> add(AgentWorkStep.ToolGroup(toolName = run.first().toolName, calls = run.toList()))
        }
        run.clear()
    }

    this@toWorkSteps.forEach { message ->
        val groupable = message is ToolActivityMessageUi && message.toolName !in UNGROUPED_TOOLS
        if (groupable && run.isNotEmpty() && run.first().toolName != (message as ToolActivityMessageUi).toolName) {
            flushRun()
        }
        if (groupable) {
            run += message as ToolActivityMessageUi
        } else {
            flushRun()
            add(AgentWorkStep.Single(message))
        }
    }
    flushRun()
}

private val UNGROUPED_TOOLS = setOf("browser_use", "subagent")

/** 折叠后的工作过程摘要所需计数。 */
internal data class AgentWorkSummary(
    val thinkingCount: Int,
    val toolCount: Int,
    val failedCount: Int,
)

internal fun List<AgentChatMessageUi>.workSummary(): AgentWorkSummary = AgentWorkSummary(
    thinkingCount = count { it is ThinkingMessageUi },
    toolCount = count { it is ToolActivityMessageUi },
    failedCount = count { it is ToolActivityMessageUi && it.status == ToolActivityStatusUi.Failed },
)

/**
 * 折叠行下方的一行结果。成功的常态结果（如“完成”）不占行；终端输出取最后一行，
 * 因为命令的结论通常在末尾；失败去掉重复的“失败”前缀与日志用的 code= 尾巴。
 */
internal fun ToolActivityMessageUi.outcomeLine(): String? {
    val summary = resultSummary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = summary.lines().map(String::trim).filter(String::isNotEmpty)
    val head = lines.firstOrNull() ?: return null
    return when (status) {
        ToolActivityStatusUi.Running -> null
        ToolActivityStatusUi.Failed, ToolActivityStatusUi.Unknown -> {
            val detail = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            head.removePrefix(FAILURE_PREFIX)
                .substringBefore(FAILURE_CODE_SEPARATOR)
                .takeIf { it.isNotBlank() && it != FAILURE_LABEL }
                ?: detail
        }
        ToolActivityStatusUi.Success -> {
            val output = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            when {
                // 只有输出时看不出这是命令结果，容易被当成 App 自己的说明
                // （2026-11-04 用户把测试命令 echo 的内容当成了界面文案）。
                // 所以给「完成 / 执行完成」这类通用状态补上前缀，业务摘要（如「已打开 · 抖音」）保持原样。
                // 顺带压一下长度：某条命令打一整行超长内容时，摘要不该把卡片撑满。
                output != null -> {
                    val compact = output.take(OUTCOME_MAX_CHARS)
                    head.takeIf { it in TRIVIAL_SUCCESS }?.let { "$it · $compact" } ?: compact
                }
                head in TRIVIAL_SUCCESS -> null
                else -> head.removePrefix(SUCCESS_PREFIX).takeIf { it.isNotBlank() }
            }
        }
    }
}

// 与 Runtime 侧结果摘要的固定格式对应。
private const val FAILURE_LABEL = "失败"
private const val FAILURE_PREFIX = "失败 · "
private const val FAILURE_CODE_SEPARATOR = " · code="
private const val SUCCESS_PREFIX = "完成 · "
private const val TRUNCATION_MARK = "…"

/** 摘要行最长多少字：单行靠省略号兜底，但先截断能让省略号落在句子里而不是字符中间。 */
private const val OUTCOME_MAX_CHARS = 120

private val TRIVIAL_SUCCESS = setOf("完成", "执行完成")

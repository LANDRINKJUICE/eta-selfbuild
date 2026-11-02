package io.github.mangi.eta.agent.schedule

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking

/**
 * 执行「自定义指令」：把用户写的一句话交给 Agent 跑一次，返回能直接放进通知的结果摘要。
 *
 * 三个设计取舍：
 * 1. **先申请前台服务**（[AgentExecutionService]）：一次 Agent run 可能跑几分钟，没有前台服务
 *    随时可能被系统回收。闹钟触发的接收器有约 10 秒的临时豁免，正好够把前台服务拉起来。
 * 2. **结果只截断摘要进通知**：完整回答仍留在会话里（这次 run 会自己建一个会话），
 *    通知只负责告诉你"干了、结果大概是什么"。
 * 3. **失败不抛异常**：定时任务不该因为一次失败就断链，错误信息也会进通知。
 */
internal object ScheduledAgentRunner {
    private const val MAX_NOTIFICATION_CHARS = 400

    fun run(context: Context, task: ScheduledTask): String {
        val prompt = task.prompt.trim()
        if (prompt.isEmpty()) return "指令为空，已跳过"

        val appContext = context.applicationContext
        val config = runCatching { runBlocking { RuntimeConfigRepository.currentRuntimeConfig() } }
            .getOrNull()
            ?: return "没有可用的模型配置，请先在设置里配置模型提供商"

        val leaseId = "scheduled:${task.id}"
        val acquired = runCatching {
            AgentExecutionService.acquire(appContext, leaseId, allowBoundFallback = false) { }
        }.getOrDefault(false)

        val summary = try {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = "scheduled-${task.id}-${System.currentTimeMillis()}",
                    prompt = prompt,
                    config = config,
                    images = emptyList(),
                ),
                onEvent = { },
            )
            when {
                result.ok -> result.content.trim().ifEmpty { "已完成（无文字输出）" }
                else -> result.error?.takeIf { it.isNotBlank() } ?: "执行失败"
            }
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("Scheduled prompt failed: type=${failure.javaClass.simpleName}")
            "执行失败（${failure.javaClass.simpleName}）"
        } finally {
            if (acquired) runCatching { AgentExecutionService.release(leaseId) }
        }

        return if (summary.length <= MAX_NOTIFICATION_CHARS) {
            summary
        } else {
            summary.take(MAX_NOTIFICATION_CHARS) + "…"
        }
    }
}

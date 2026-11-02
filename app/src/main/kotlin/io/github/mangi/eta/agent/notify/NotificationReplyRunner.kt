package io.github.mangi.eta.agent.notify

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 通知栏回复的**后台执行**：把用户打的字直接送进对应会话并跑一次，**不拉起界面**。
 *
 * 为什么不经过队列、也不等界面：用户点完回复就该能继续做别的事。
 * 之前那套"落盘排队 → 等主界面渲染时再发"的方案，用户必须先回到 Eta 才会发出，
 * 体验上等于"没生效"（这也是它被换掉的原因）。
 *
 * 三个必要动作（缺一个都跑不起来或会被系统杀掉）：
 * 1. **先申请前台服务** [AgentExecutionService.acquire]：一次 run 可能几分钟，
 *    没有前台服务随时会被回收。
 * 2. **handoff 里带会话 id**：`AgentUiHandoffPayload.from` 对非 JSON 的 payload
 *    直接当作 conversationId，所以这里传裸字符串即可 —— 结果会落进**对应会话**，
 *    而不是新建一个。
 * 3. **结束后 release**：让前台服务在没事可做时退下去，不留常驻通知。
 */
internal object NotificationReplyRunner {

    fun run(context: Context, conversationId: String, text: String) {
        val appContext = context.applicationContext
        val config = runCatching {
            kotlinx.coroutines.runBlocking {
                io.github.mangi.eta.data.repository.RuntimeConfigRepository.currentRuntimeConfig()
            }
        }.getOrNull()
        if (config == null) {
            AndroidAgentLogger.warn("Notification reply skipped: no runtime config")
            return
        }

        val runId = "reply-${System.currentTimeMillis()}"
        val leaseId = "reply:$conversationId"
        val acquired = runCatching {
            AgentExecutionService.acquire(appContext, leaseId, allowBoundFallback = false) { }
        }.getOrDefault(false)

        try {
            AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = text,
                    config = config,
                    images = emptyList(),
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = runId,
                        source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                        // 裸字符串（非 JSON）会被 AgentUiHandoffPayload.from 当作会话 id
                        payload = conversationId,
                    ),
                ),
                onEvent = { },
            )
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("Notification reply run failed: type=${failure.javaClass.simpleName}")
        } finally {
            if (acquired) runCatching { AgentExecutionService.release(leaseId) }
        }
    }
}

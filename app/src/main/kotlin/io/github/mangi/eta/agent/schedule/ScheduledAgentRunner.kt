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

    /**
     * 后台执行的上限（秒）：超过就按超时失败，不无限期占着 `:schedule` 进程。
     *
     * 定 5 分钟是按实测：这类「看一眼通知给一两步操作」的指令正常 1~2 分钟内结束，
     * 到 5 分钟还没回基本就是主进程那头出问题了。
     */
    private const val SCHEDULED_RUN_TIMEOUT_SECONDS = 300L

    /**
     * 把触发事件里的关键信息塞进指令正文。
     *
     * 为什么必须这么做：通知匹配任务的 prompt 只写了「刚收到含「签到」的通知」，
     * 没写是哪条、哪个 App。Agent 想知道细节只能调 GUI 去打开通知栏 —— 那是 systemui，
     * 被 `CanvasRoutingPolicy` 强制走主屏，用户正在用的界面就被顶掉。
     * 把通知原文直接交给它，它就不需要看屏幕（2026-11-04 用户反馈「sub 会抢我焦点」）。
     */
    private fun withEventContext(basePrompt: String, event: ScheduledEvent?): String {
        val note = event as? ScheduledEvent.NotificationPosted ?: return basePrompt
        return buildString {
            appendLine("触发这条任务的通知：")
            appendLine("应用：${note.packageName.ifBlank { "未知应用" }}")
            if (note.title.isNotBlank()) appendLine("标题：${note.title}")
            if (note.text.isNotBlank()) appendLine("正文：${note.text}")
            appendLine()
            appendLine("上面就是通知原文，不用再去打开通知栏或看屏幕。")
            appendLine()
            appendLine(basePrompt)
        }.trim()
    }

    fun run(context: Context, task: ScheduledTask, event: ScheduledEvent? = null): String {
        val basePrompt = task.prompt.trim()
        if (basePrompt.isEmpty()) return "指令为空，已跳过"
        // 把触发事件（通知原文）拼进去：Agent 就不用为了看"是哪条通知"去打开通知栏。
        val prompt = withEventContext(basePrompt, event)

        val appContext = context.applicationContext
        val config = runCatching { runBlocking { RuntimeConfigRepository.currentRuntimeConfig() } }
            .onFailure { failure ->
                // 2026-11-04：以前这里静默吞异常，只报「没有可用的模型配置」，看不出真因
                // （实际是 :schedule 进程没初始化 ProviderRepository）。失败必须留痕。
                AndroidAgentLogger.warn(
                    "Scheduled model config failed: type=${failure.javaClass.simpleName}"
                )
            }
            .getOrNull()
            ?: return "没有可用的模型配置，请先在设置里配置模型提供商"

        // 诊断：这条日志能区分「配置没拿到」和「拿到配置但 run 没返回」两种情况。
        AndroidAgentLogger.warn("Scheduled run start: task=${task.id}")
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
                // 后台入口必须带超时：主进程忙或被 ColorOS 杀掉时，没超时就会永久挂在
                // CountDownLatch 上，任务既不成功也不失败、通知永远不来（2026-11-04 实测）。
                timeoutSeconds = SCHEDULED_RUN_TIMEOUT_SECONDS,
            )
            AndroidAgentLogger.warn(
                "Scheduled run done: ok=${result.ok} chars=${result.content.length} " +
                    "err=${result.error?.take(60).orEmpty()}"
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

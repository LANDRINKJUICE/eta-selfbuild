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

    fun run(context: Context, task: ScheduledTask, event: ScheduledEvent? = null): ScheduledOutcome {
        val basePrompt = task.prompt.trim()
        if (basePrompt.isEmpty()) return ScheduledOutcome(false, "指令为空，已跳过")
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
            ?: return ScheduledOutcome(false, "没有可用的模型配置，请先在设置里配置模型提供商")

        // 权限按触发方式区分（用户 2026-11-04 的要求）：
        //
        // - **通知匹配**：它只是「某条通知来了，看看是什么、要不要处理」，只该读信息 + 给建议。
        //   2026-11-04 真机事故：通知匹配任务为了"看看签到没"，直接打开并操作了支付宝。
        //   所以 GUI、敏感操作、终端、浏览器、子代理全部关掉；
        //   `deviceSensitiveReadTools` 保留——读通知/账单正是它要做的事。
        // - **用户自己建的定时任务**：保留完整能力。用户明确让它「每天帮我做某件事」时，
        //   操作能力就是他要的，不该一刀切砍掉。
        // 权限级别：任务自己设的优先，没设就按触发方式推断
        // （通知匹配 → 只读；用户自建定时 → 完全开放）。
        val readOnly = task.readOnly ?: (task.trigger == ScheduledTrigger.NOTIFICATION_MATCH)
        val backgroundConfig = if (readOnly) {
            config.copy(
                deviceDirectTools = false,
                deviceSensitiveActionTools = false,
                terminalTools = false,
                browserTools = false,
                subagentTools = false,
                // 关键：GUI 交互那一组（launch_app / open_uri / tap / type_text / press_key）
                // 以前不受任何开关控制，所以「只读」照样能打开支付宝并点按
                // （2026-11-04 用户实测：设了只读分析还是被打开支付宝）。
                interactionTools = false,
            )
        } else {
            config
        }

        // 诊断：这条日志能区分「配置没拿到」和「拿到配置但 run 没返回」两种情况。
        AndroidAgentLogger.warn("Scheduled run start: task=${task.id}")
        val leaseId = "scheduled:${task.id}"
        val acquired = runCatching {
            AgentExecutionService.acquire(appContext, leaseId, allowBoundFallback = false) { }
        }.getOrDefault(false)
        // 前台服务没拿到就别硬跑：一次 run 最长 300 秒，没有前台服务保护，
        // 进程被 ColorOS 回收后既不成功也不失败，通知永远不来（2026-11-04 审查发现）。
        if (!acquired) {
            AndroidAgentLogger.warn("Scheduled run aborted: foreground service unavailable")
            return ScheduledOutcome(false, "执行失败（无法启动前台服务，可能被系统限制）")
        }

        val outcome = try {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = "scheduled-${task.id}-${System.currentTimeMillis()}",
                    prompt = prompt,
                    config = backgroundConfig,
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
            if (result.ok) {
                ScheduledOutcome(true, result.content.trim().ifEmpty { "已完成（无文字输出）" })
            } else {
                // 模型的错误原文（`HTTP 401`、`模型返回为空`、`上下文超长`…）直接当失败摘要。
                // 以前这里返回纯文本，外层再靠前缀猜成败 —— 这些文案都不在表里，于是记成成功。
                ScheduledOutcome(false, result.error?.takeIf { it.isNotBlank() } ?: "执行失败")
            }
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("Scheduled prompt failed: type=${failure.javaClass.simpleName}")
            ScheduledOutcome(false, "执行失败（${failure.javaClass.simpleName}）")
        } finally {
            if (acquired) runCatching { AgentExecutionService.release(leaseId) }
        }

        return if (outcome.summary.length <= MAX_NOTIFICATION_CHARS) {
            outcome
        } else {
            outcome.copy(summary = outcome.summary.take(MAX_NOTIFICATION_CHARS) + "…")
        }
    }
}

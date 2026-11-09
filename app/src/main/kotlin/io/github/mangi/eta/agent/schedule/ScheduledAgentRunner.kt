package io.github.mangi.eta.agent.schedule

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking

/**
 * 执行「自定义指令」：把用户写的一句话交给 Agent 跑一次，返回能直接放进通知的结果摘要。
 *
 * 三个设计取舍：
 * 1. **前台保护由两段各自负责**：`:schedule` 进程由 [ScheduledTaskReceiver] 自己
 *    `startForeground`（护住本地动作与这里的等待期）；主进程那边一次 Agent run 由
 *    `AgentRuntimeService.admitRun` 自己 `AgentExecutionService.acquire`（护住真正跑模型的时间）。
 *    以前这里跨进程 acquire 主进程的服务：`:schedule` 侧 lease 记在自己进程的 map 里、
 *    主进程 map 是空的，服务被拉起后立刻 `stopSelf`，既没护住任何东西，还白抖一次（2026-11-09 修）。
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
        val eventPrompt = withEventContext(basePrompt, event)
        // 顺带压篇幅：定时任务多数是「看一眼、给个结论」，长分析只会让通知和会话都难读。
        val answerRule = "（答复尽量短：先一句结论，最多再补一句依据；不要用 markdown 标题、列表或加粗。）"
        val prompt = buildString {
            append(eventPrompt)
            append("\n\n").append(answerRule)
        }

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
                // 2026-11-08：定时任务无人值守，插件工具不注册（同唤醒那条的理由）。
                // 想让定时任务跑插件请用 PLUGIN 动作（PluginInvoker，走确认门禁的另一条路）。
                pluginTools = false,
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
        val runId = "scheduled-${task.id}-${System.currentTimeMillis()}"
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        // 2026-11-09 修（A1）：必须带 handoff。
        // persistCompletedRun 第一行就是 `request.handoff ?: return` —— 不带时终态根本不进 outbox，
        // 一旦客户端超时或进程被杀，这次干了什么就彻底查不到（11-07 12:30 那次只剩「执行超时」）。
        // source 用独立值 "scheduled"：恢复逻辑（AgentRunRecoveryCoordinator / AgentAppState）
        // 只认 agent_ui，定时任务的结果不会被 UI 当自己的 run 接管重建会话。
        // payload 留空：定时任务不归档、不落会话，persistArchivedRun 遇到空 payload 会直接 return。
        val handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.SCHEDULED_HANDOFF_SOURCE,
            payload = "",
            dismissEntrySurfaceOnForegroundOperation = false,
        )

        val outcome = try {
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = prompt,
                    config = backgroundConfig,
                    images = emptyList(),
                    handoff = handoff,
                ),
                onEvent = { },
                // 后台入口必须带超时：主进程忙或被 ColorOS 杀掉时，没超时就会永久挂在
                // CountDownLatch 上，任务既不成功也不失败、通知永远不来（2026-11-04 实测）。
                timeoutSeconds = SCHEDULED_RUN_TIMEOUT_SECONDS,
            )
            // 超时/失败先回捞一次 outbox：终态可能已经在超时边界之前写完，
            // 这时把真实结果捞回来，比直接报「执行超时」有用得多。
            val settled = if (result.ok) result else recoverCompletedRun(client, runId) ?: result
            AndroidAgentLogger.warn(
                "Scheduled run done: ok=${settled.ok} chars=${settled.content.length} " +
                    "recovered=${settled !== result} err=${settled.error?.take(60).orEmpty()}"
            )
            if (settled.ok) {
                ScheduledOutcome(true, settled.content.trim().ifEmpty { "已完成（无文字输出）" })
            } else {
                // 模型的错误原文（`HTTP 401`、`模型返回为空`、`上下文超长`…）直接当失败摘要。
                // 以前这里返回纯文本，外层再靠前缀猜成败 —— 这些文案都不在表里，于是记成成功。
                ScheduledOutcome(false, settled.error?.takeIf { it.isNotBlank() } ?: "执行失败")
            }
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("Scheduled prompt failed: type=${failure.javaClass.simpleName}")
            ScheduledOutcome(false, "执行失败（${failure.javaClass.simpleName}）")
        } finally {
            // 无论成败都确认一次：未确认的终态会一直留在 outbox，
            // 而 UI 侧 drain 是 `ORDER BY created_at ASC LIMIT 8`，堆多了会把 UI 自己的结果挤出窗口
            // （那样会话就恢复不出来）。确认失败只记日志，不影响这次任务的结果。
            runCatching { client.ackResult(runId) }
                .onFailure { failure ->
                    AndroidAgentLogger.warn("Scheduled result ack failed: type=${failure.javaClass.simpleName}")
                }
        }

        return if (outcome.summary.length <= MAX_NOTIFICATION_CHARS) {
            outcome
        } else {
            outcome.copy(summary = outcome.summary.take(MAX_NOTIFICATION_CHARS) + "…")
        }
    }

    /**
     * 回捞已经落进 outbox 的终态。
     *
     * owner 传空串是**刻意**的：定时任务的 handoff.payload 就是空串，服务端
     * `ownedResult(runId, owner)` 按 `handoff_payload = :owner` 精确匹配，
     * 空 payload 正好只命中它自己这条，不会读到别的入口的结果。
     */
    private fun recoverCompletedRun(
        client: AgentRuntimeClient,
        runId: String,
    ): AgentRuntimeWire.RunResult? = runCatching {
        client.readCompletedRun(runId, owner = "")
    }.onFailure { failure ->
        AndroidAgentLogger.warn("Scheduled result recovery failed: type=${failure.javaClass.simpleName}")
    }.getOrNull()
}

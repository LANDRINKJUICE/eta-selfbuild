package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 后台命令失败后自动唤醒 Agent 处理。
 *
 * 为什么需要：`terminal_job` 的完成通知发给用户，助手在那一轮结束后就「睡」了，
 * 构建失败只能等用户回来说一句「失败了」。这里把失败结果直接投给 Agent，
 * 让它自己看日志、修问题、报告结论。
 *
 * 四条边界（每条都对应一个真实风险）：
 * 1. **只对失败唤醒**：退出码 0 不打扰，否则每次成功构建都要白烧一轮 token。
 * 2. **时间窗限流**：10 分钟内最多 2 次。唤醒出来的 run 自己也可能再起后台任务，
 *    没有限流会形成「失败 → 唤醒 → 再失败 → 再唤醒」的死循环。
 * 3. **异步执行**：监控线程每秒 refresh 一次，同步跑 Agent 会把整个后台任务监控卡住。
 * 4. **日志尾部随 prompt 一起给**：Agent 不必先读一遍文件就能判断原因。
 *
 * 用户主动停止（`end_reason=stopped`）不唤醒 —— 那是意图，不是故障。
 */
internal object JobWakeUpController {
    /** 限流窗口与上限；测试直接引用这两个常量。 */
    const val WINDOW_MS = 10 * 60 * 1000L
    const val MAX_WAKEUPS_PER_WINDOW = 2

    /** 随 prompt 带上的日志尾部字符数；再多会挤占上下文。 */
    const val LOG_TAIL_CHARS = 3_000

    /** 会话忙时延迟多久再试一次（毫秒）。 */
    private const val BUSY_RETRY_DELAY_MS = 120_000L

    private const val HISTORY_KEY = "agent_job_wakeup_history"
    private const val HISTORY_LIMIT = 8

    /** 判定这次完成是否属于「需要处理的失败」。 */
    internal fun isFailure(exitCode: Int?, endReason: String?): Boolean {
        if (endReason == "timed_out") return true
        if (endReason == "stopped") return false
        return exitCode != null && exitCode != 0
    }

    /** 当前时间窗内还剩几次唤醒额度。 */
    internal fun remainingWakeUps(history: List<Long>, now: Long): Int =
        (MAX_WAKEUPS_PER_WINDOW - history.count { it > now - WINDOW_MS }).coerceAtLeast(0)

    /** 失败时投一轮 Agent；任何一步不满足都安静退出，不影响原有的完成通知。 */
    fun maybeWake(
        context: Context,
        task: DetachedTask,
        logTail: String,
        logger: AgentLogger,
    ) {
        if (!isFailure(task.exitCode, task.endReason)) return
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_JOB_WAKEUP)) return
        val preferences = Prefs.localAgentPreferences() ?: return
        val now = System.currentTimeMillis()
        val history = decodeHistory(preferences.getString(HISTORY_KEY, null))
        val remaining = remainingWakeUps(history, now)
        if (remaining <= 0) {
            logger.warn("Job wake-up skipped: rate limit reached")
            return
        }
        // 第几次唤醒要告诉 Agent，否则它不知道自己已经在同一个坑里试过一轮。
        val ordinal = MAX_WAKEUPS_PER_WINDOW - remaining + 1
        runCatching {
            preferences.edit()
                .putString(HISTORY_KEY, encodeHistory((history + now).takeLast(HISTORY_LIMIT)))
                .apply()
        }
        val app = context.applicationContext
        Thread({ run(app, task, logTail, ordinal, logger) }, "eta-job-wakeup").apply { isDaemon = true }.start()
    }

    /**
     * 失败原因是不是「目标会话正忙」。
     *
     * 这不是故障，是**时机不对**：用户正在跟助手对话时，runtime 会拒绝同一会话的并发 run
     * （`RuntimeRunRegistry` 的 CONVERSATION_BUSY 准入判据）。这种情况不该给用户弹
     * 「后台命令失败，自动处理未完成」——那只是在吓人。
     */
    private fun isConversationBusy(error: String): Boolean =
        error.contains("当前会话仍有任务在运行") || error.contains("CONVERSATION_BUSY")

    /** 会话忙时的延后重试：只排一次，重试那一轮不再递归刷新。 */
    private fun scheduleBusyRetry(
        app: Context,
        task: DetachedTask,
        logTail: String,
        wakeUpOrdinal: Int,
        logger: AgentLogger,
    ) {
        Thread({
            try {
                Thread.sleep(BUSY_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            if (!Prefs.isEnabled(Prefs.Keys.AGENT_JOB_WAKEUP)) return@Thread
            run(app, task, logTail, wakeUpOrdinal, logger, allowBusyRetry = false)
        }, "eta-job-wakeup-retry").apply { isDaemon = true }.start()
    }

    private fun run(
        app: Context,
        task: DetachedTask,
        logTail: String,
        wakeUpOrdinal: Int,
        logger: AgentLogger,
        /** 第一次因「会话忙」失败时允许再排一次重试；重试那轮置 false，避免死循环。 */
        allowBusyRetry: Boolean = true,
    ) {
        val baseConfig = runCatching { runBlocking { RuntimeConfigRepository.currentRuntimeConfig() } }
            .getOrNull()
        if (baseConfig == null) {
            logger.warn("Job wake-up skipped: no runtime config")
            return
        }
        // 唤醒是「收尾」，不是「开工」：只留诊断和重试用得上的能力。
        // 2026-11-04 用户实测：不限制的话它跑去搜网页、写长篇分析，问题一个没解决。
        // browserTools 同时管 web_search / fetch_url / browser_use（见 AgentToolCatalog）。
        val config = baseConfig.copy(
            browserTools = false,
            subagentTools = false,
            deviceSensitiveActionTools = false,
            interactionTools = false,
        )
        val leaseId = "job-wakeup:${task.id}"
        val acquired = runCatching {
            AgentExecutionService.acquire(app, leaseId, allowBoundFallback = false) { }
        }.getOrDefault(false)
        val runId = "job-wakeup-${task.id}-${System.currentTimeMillis()}"
        // 带上 handoff，结果才会写进 runtime outbox 并落回会话。
        // 不带 handoff 时 persistCompletedRun 第一行就 return，用户只能在通知里看到一句摘要，
        // 对话里没有任何痕迹（2026-11-05 用户实测反馈「唤醒了但不会回到对话」）。
        val handoffConversationId = latestConversationId(app)
        try {
            val client = AgentRuntimeClient(app, logger)
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = buildPrompt(task, logTail, wakeUpOrdinal),
                    config = config,
                    images = emptyList(),
                    handoff = handoffConversationId.takeIf { it.isNotBlank() }?.let { conversationId ->
                        AgentRuntimeWire.EntryHandoff(
                            id = runId,
                            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                            payload = conversationId,
                        )
                    },
                ),
                onEvent = { },
            )
            if (!result.ok) {
                val error = result.error.orEmpty()
                logger.warn("Job wake-up run failed: ${error.take(200)}")
                // 「会话正忙」不是失败：用户本来就在跟助手说话，这时候弹「后台命令失败，自动处理未完成」
                // 只会吓人（2026-11-05 用户反馈「怎么一直弹这个玩意」）。
                // 改成：安静地稍后重试一次；再忙就只记日志，不打扰。
                if (isConversationBusy(error)) {
                    if (allowBusyRetry) {
                        logger.info("Job wake-up deferred: conversation busy, will retry in ${BUSY_RETRY_DELAY_MS / 1000}s")
                        scheduleBusyRetry(app, task, logTail, wakeUpOrdinal, logger)
                    } else {
                        logger.info("Job wake-up skipped quietly: conversation still busy")
                    }
                    return
                }
            }
            // 回执：把原来那条「失败了」的通知升级成「已自动处理」，用户不用回会话才知道。
            runCatching {
                BackgroundCommandNotification.showWakeUpResult(
                    context = app,
                    task = task,
                    summary = if (result.ok) result.content else result.error.orEmpty(),
                    ok = result.ok,
                )
            }
        } catch (failure: Exception) {
            logger.warn("Job wake-up failed: type=${failure.javaClass.simpleName}")
        } finally {
            if (acquired) runCatching { AgentExecutionService.release(leaseId) }
        }
    }

    /**
     * 取最近更新过的会话 id，作为唤醒结果的归属。
     *
     * 后台命令通常是用户在刚才那个会话里让我跑的，所以「最近会话」是合理归属。
     * 一条会话都没有时返回空串，调用方退化成「只发通知」，不制造空会话。
     */
    private fun latestConversationId(app: Context): String = runCatching {
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(app).conversationDao().conversationMetadataPage(1, 0).firstOrNull()?.id
        }
    }.getOrNull().orEmpty()

    /** 交给 Agent 的指令：事实在前，要求在后，明确禁止起同样的任务重试。 */
    internal fun buildPrompt(task: DetachedTask, logTail: String, wakeUpOrdinal: Int = 1): String = buildString {
        appendLine("后台命令结束了，但看起来是失败的。请判断原因并处理。")
        appendLine()
        appendLine("任务：${task.description.ifBlank { "（无描述）" }}")
        appendLine("命令：${task.command}")
        appendLine("退出码：${task.exitCode ?: "未知"}（end_reason=${task.endReason ?: "无"}）")
        appendLine("日志路径：${task.logPath}")
        val tail = logTail.takeLast(LOG_TAIL_CHARS).trim()
        if (tail.isNotEmpty()) {
            appendLine()
            appendLine("日志末尾：")
            appendLine(tail)
        }
        if (wakeUpOrdinal > 1) {
            appendLine()
            appendLine("注意：这是 10 分钟内的第 $wakeUpOrdinal 次自动唤醒，上一次没修好。")
            appendLine("这次如果还没有把握，就直接报告结论和阻塞点，不要再尝试同样的做法。")
        }
        appendLine()
        appendLine("要求：")
        appendLine("1. 先看日志确认是不是真失败，有些命令非 0 退出属于预期。")
        appendLine("2. 只做「能自己恢复」的动作：换参数、换路径、原样重试一次。")
        appendLine("3. 断网、限速、连接中断这类，重试一次还不行就直接说「需要用户自己处理」，")
        appendLine("   不要去搜网页，不要分析原理，不要写长篇结论。")
        appendLine("4. 整个过程最多 3 次工具调用；结论不超过 3 句话。")
        appendLine("5. 不要再起同样的后台任务重试，避免循环。")
    }

    private fun decodeHistory(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    private fun encodeHistory(history: List<Long>): String = history.joinToString(",")
}

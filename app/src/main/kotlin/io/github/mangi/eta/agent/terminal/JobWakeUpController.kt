package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

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
 *
 * 2026-11-08 重做（三条都是实测出来的漏，别退回去）：
 * - **持久队列**：待唤醒任务写进 SharedPreferences（`agent_job_wakeup_pending`）。
 *   旧实现起一个 daemon thread 就完事，App 进程被 ColorOS 冻/杀后唤醒凭空消失
 *   （11-08 06:36 实测：history 记了一笔，会话里没有结果、日志里没有任何记录）。
 *   现在进程重启后 [resumePending] 会把队列跑完。
 * - **忙就等到空闲**：旧实现只重试一次 120 秒，之后静默丢弃，11-07 连续 17 次全部因此
 *   失败（日志 `Job wake-up deferred` → `skipped quietly`）。现在每 [RETRY_INTERVAL_MS]
 *   试一次，累计超过 [MAX_PENDING_MS] 才放弃。
 * - **有结果必通知**：成功、失败、最终放弃各发一条通知，用户不用猜。
 */
internal object JobWakeUpController {
    /** 限流窗口与上限；测试直接引用这两个常量。 */
    const val WINDOW_MS = 10 * 60 * 1000L
    const val MAX_WAKEUPS_PER_WINDOW = 2

    /** 随 prompt 带上的日志尾部字符数；再多会挤占上下文。 */
    const val LOG_TAIL_CHARS = 3_000

    /** 会话忙时的重试节奏。 */
    const val RETRY_INTERVAL_MS = 20_000L

    /** 一条待唤醒最多等多久；超过就放弃并通知用户，避免永远挂着。 */
    const val MAX_PENDING_MS = 10 * 60 * 1000L

    /** 单轮唤醒 run 的超时秒数。没有它，等结果的线程可能永久挂住，draining 卡死。 */
    const val WAKEUP_RUN_TIMEOUT_SECONDS = 300L

    private const val HISTORY_KEY = "agent_job_wakeup_history"
    private const val HISTORY_LIMIT = 8
    private const val PENDING_KEY = "agent_job_wakeup_pending"
    private const val PENDING_LIMIT = 4

    /** 一条pending：prompt 提前拼好，进程重启后不必再找任务记录。 */
    internal data class PendingWakeUp(
        val taskId: String,
        val description: String,
        val exitCode: Int?,
        val prompt: String,
        val createdAt: Long,
    )

    /** 一次唤醒尝试的结果。 */
    private sealed interface Attempt {
        data class Done(val summary: String) : Attempt
        /** 会话仍有任务在跑（或并发上限）；不是故障，是时机不对，继续等。 */
        data object Busy : Attempt
        data class Failed(val message: String) : Attempt
    }

    /** 判定这次完成是否属于「需要处理的失败」。 */
    internal fun isFailure(exitCode: Int?, endReason: String?): Boolean {
        if (endReason == "timed_out") return true
        if (endReason == "stopped") return false
        return exitCode != null && exitCode != 0
    }

    /** 当前时间窗内还剩几次唤醒额度。 */
    internal fun remainingWakeUps(history: List<Long>, now: Long): Int =
        (MAX_WAKEUPS_PER_WINDOW - history.count { it > now - WINDOW_MS }).coerceAtLeast(0)

    /** 队列里同一条任务只保留最新一条，并裁到上限。 */
    internal fun upsertPending(
        current: List<PendingWakeUp>,
        entry: PendingWakeUp,
    ): List<PendingWakeUp> = (current.filterNot { it.taskId == entry.taskId } + entry).takeLast(PENDING_LIMIT)

    /** 等太久就该放弃了；测试直接引用。 */
    internal fun pendingExpired(createdAt: Long, now: Long): Boolean = now - createdAt >= MAX_PENDING_MS

    internal fun encodePending(entries: List<PendingWakeUp>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("task_id", entry.taskId)
                    .put("description", entry.description)
                    .put("exit_code", entry.exitCode ?: JSONObject.NULL)
                    .put("prompt", entry.prompt)
                    .put("created_at", entry.createdAt),
            )
        }
        return array.toString()
    }

    internal fun decodePending(raw: String?): List<PendingWakeUp> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val taskId = item.optString("task_id")
            val prompt = item.optString("prompt")
            if (taskId.isBlank() || prompt.isBlank()) return@mapNotNull null
            PendingWakeUp(
                taskId = taskId,
                description = item.optString("description"),
                exitCode = if (item.isNull("exit_code")) null else item.optInt("exit_code"),
                prompt = prompt,
                createdAt = item.optLong("created_at"),
            )
        }
    }

    /** 失败时入队并尝试投一轮 Agent；任何一步不满足都安静退出，不影响原有的完成通知。 */
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
        val entry = PendingWakeUp(
            taskId = task.id,
            description = task.description,
            exitCode = task.exitCode,
            prompt = buildPrompt(task, logTail, ordinal),
            createdAt = now,
        )
        enqueue(preferences, entry)
        logger.info("Job wake-up queued: task=${task.id} exit=${task.exitCode} ordinal=$ordinal")
        drain(context.applicationContext, logger)
    }

    /** App 起来后补处理上次没跑完的唤醒（进程被冻/杀时留下的那条）。 */
    fun resumePending(context: Context, logger: AgentLogger) {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_JOB_WAKEUP)) return
        val preferences = Prefs.localAgentPreferences() ?: return
        val queued = decodePending(preferences.getString(PENDING_KEY, null))
        if (queued.isEmpty()) return
        logger.info("Job wake-up queue resumed: count=${queued.size}")
        drain(context.applicationContext, logger)
    }

    /**
     * 失败原因是不是「目标会话正忙」。
     *
     * 这不是故障，是**时机不对**：用户正在跟助手对话时，runtime 会拒绝同一会话的并发 run
     * （`RuntimeRunRegistry` 的 CONVERSATION_BUSY 准入判据）。这种情况不该给用户弹
     * 「后台命令失败，自动处理未完成」——那只是在吓人，等一会儿再来就行。
     */
    private fun isRetryable(error: String): Boolean =
        error.contains("当前会话仍有任务在运行") ||
            error.contains("CONVERSATION_BUSY") ||
            error.contains("同时运行的任务已达上限") ||
            error.contains("RUN_CAPACITY_REACHED")

    /** 同一时刻只允许一个排空线程，避免重复投递。 */
    private val draining = AtomicBoolean(false)

    private fun drain(app: Context, logger: AgentLogger) {
        if (!draining.compareAndSet(false, true)) return
        Thread({ drainLoop(app, logger) }, "eta-job-wakeup").apply { isDaemon = true }.start()
    }

    /** 逐条处理队列：成功/失败就出队，忙就等一轮再试，等满 [MAX_PENDING_MS] 放弃。 */
    private fun drainLoop(app: Context, logger: AgentLogger) {
        try {
            while (true) {
                val preferences = Prefs.localAgentPreferences() ?: return
                val queued = decodePending(preferences.getString(PENDING_KEY, null))
                val entry = queued.firstOrNull() ?: return
                val now = System.currentTimeMillis()
                // 限流在「真正投一轮」这一刻判，而不是只在入口判：补发路径（resumePending）
                // 一次可能带出好几条，不加这道闸就能绕过「10 分钟最多 2 次」。
                if (remainingWakeUps(decodeHistory(preferences.getString(HISTORY_KEY, null)), now) <= 0) {
                    if (pendingExpired(entry.createdAt, now)) {
                        dequeue(preferences, entry.taskId)
                        logger.warn("Job wake-up abandoned: task=${entry.taskId} reason=rate limit until expiry")
                        notifyResult(app, entry, "唤醒额度用完了，没能自动处理。你回来说一声我再看日志。", ok = false)
                        continue
                    }
                    logger.info("Job wake-up deferred: task=${entry.taskId} reason=rate limit")
                    Thread.sleep(RETRY_INTERVAL_MS)
                    continue
                }
                when (val attempt = attemptOnce(app, entry, logger)) {
                    is Attempt.Done -> {
                        dequeue(preferences, entry.taskId)
                        recordAcceptedWakeUp(preferences)
                        logger.info("Job wake-up done: task=${entry.taskId} chars=${attempt.summary.length}")
                        notifyResult(app, entry, attempt.summary, ok = true)
                    }

                    is Attempt.Failed -> {
                        dequeue(preferences, entry.taskId)
                        recordAcceptedWakeUp(preferences)
                        logger.warn("Job wake-up failed: task=${entry.taskId} reason=${attempt.message.take(120)}")
                        notifyResult(app, entry, attempt.message, ok = false)
                    }

                    Attempt.Busy -> {
                        if (pendingExpired(entry.createdAt, System.currentTimeMillis())) {
                            dequeue(preferences, entry.taskId)
                            logger.warn("Job wake-up abandoned: task=${entry.taskId} reason=conversation busy too long")
                            notifyResult(
                                app,
                                entry,
                                "会话一直在忙，没能自动处理。你回来说一声我再看日志。",
                                ok = false,
                            )
                        } else {
                            logger.info("Job wake-up deferred: task=${entry.taskId} retry in ${RETRY_INTERVAL_MS / 1000}s")
                            Thread.sleep(RETRY_INTERVAL_MS)
                        }
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            // 中断标志还挂着，不能再起线程 —— 新线程第一次 sleep 会立刻抛 InterruptedException，
            // 形成无间隔的线程创建热循环（2026-11-08 复查发现）。队列数据还在，下次启动补跑。
            logger.info("Job wake-up loop interrupted; queue kept for next launch")
            return
        } catch (failure: Exception) {
            logger.warn("Job wake-up loop failed: type=${failure.javaClass.simpleName}")
        } finally {
            draining.set(false)
            // 收尾竞态：排空线程正好读到「队列空」准备退出时，maybeWake 刚入队并调 drain()，
            // 那一次会被 draining=true 挡掉。这里补看一次队列，有东西就再起一轮。
            // 只有「正常走完」才重投；异常/中断路径上面已经 return，退避交给下一次触发。
            runCatching {
                val pending = Prefs.localAgentPreferences()
                    ?.let { decodePending(it.getString(PENDING_KEY, null)) }
                    .orEmpty()
                if (pending.isNotEmpty()) drain(app, logger)
            }
        }
    }

    private fun attemptOnce(app: Context, entry: PendingWakeUp, logger: AgentLogger): Attempt {
        val baseConfig = runCatching { runBlocking { RuntimeConfigRepository.currentRuntimeConfig() } }
            .getOrNull()
        if (baseConfig == null) {
            return Attempt.Failed("没有可用的模型配置，无法自动处理")
        }
        // 唤醒是「收尾」，不是「开工」：只留诊断和重试用得上的能力。
        // 2026-11-04 用户实测：不限制的话它跑去搜网页、写长篇分析，问题一个没解决。
        // browserTools 同时管 web_search / fetch_url / browser_use（见 AgentToolCatalog）。
        val config = baseConfig.copy(
            browserTools = false,
            subagentTools = false,
            deviceSensitiveActionTools = false,
            interactionTools = false,
            // 2026-11-08：唤醒是无人值守的 run。linux 环境插件要 root、每次调用都必须确认，
            // 没有人能点那张卡 → 模型一调就白等 120 秒超时再被拒，还吃掉一次唤醒额度。
            // 直接不注册插件工具。
            pluginTools = false,
        )
        val leaseId = "job-wakeup:${entry.taskId}"
        val acquired = runCatching {
            AgentExecutionService.acquire(app, leaseId, allowBoundFallback = false) { }
        }.getOrDefault(false)
        val runId = "job-wakeup-${entry.taskId}-${System.currentTimeMillis()}"
        // 带上 handoff，结果才会写进 runtime outbox 并落回会话。
        // 不带 handoff 时 persistCompletedRun 第一行就 return，用户只能在通知里看到一句摘要，
        // 对话里没有任何痕迹（2026-11-05 用户实测反馈「唤醒了但不会回到对话」）。
        val handoffConversationId = latestConversationId(app)
        return try {
            val client = AgentRuntimeClient(app, logger)
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = entry.prompt,
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
                // 2026-11-08 修：以前不传超时（=0 表示不限）。唤醒线程会永久阻塞在等结果上，
                // draining 永远为 true，之后所有唤醒被挡掉、队列再也不动 ——
                // 正好是这轮想修的症状。定时任务那条路一直有 300 秒超时，这里对齐。
                timeoutSeconds = WAKEUP_RUN_TIMEOUT_SECONDS,
            )
            when {
                result.ok -> Attempt.Done(result.content)
                isRetryable(result.error.orEmpty()) -> Attempt.Busy
                else -> Attempt.Failed(result.error.orEmpty())
            }
        } catch (failure: Exception) {
            logger.warn("Job wake-up failed: type=${failure.javaClass.simpleName}")
            Attempt.Failed("唤醒时出错（${failure.javaClass.simpleName}）")
        } finally {
            if (acquired) runCatching { AgentExecutionService.release(leaseId) }
        }
    }

    private fun notifyResult(app: Context, entry: PendingWakeUp, summary: String, ok: Boolean) {
        runCatching {
            BackgroundCommandNotification.showWakeUpResult(
                context = app,
                taskId = entry.taskId,
                description = entry.description,
                exitCode = entry.exitCode,
                summary = summary,
                ok = ok,
            )
        }
    }

    private fun enqueue(preferences: android.content.SharedPreferences, entry: PendingWakeUp) {
        synchronized(PENDING_LOCK) {
            val current = decodePending(preferences.getString(PENDING_KEY, null))
            preferences.edit()
                .putString(PENDING_KEY, encodePending(upsertPending(current, entry)))
                .apply()
        }
    }

    private fun dequeue(preferences: android.content.SharedPreferences, taskId: String) {
        synchronized(PENDING_LOCK) {
            val current = decodePending(preferences.getString(PENDING_KEY, null))
            if (current.none { it.taskId == taskId }) return
            preferences.edit()
                .putString(PENDING_KEY, encodePending(current.filterNot { it.taskId == taskId }))
                .apply()
        }
    }

    /** 真正投出去的一轮才算额度；重试和被拒不算，否则限流会把一次失败吃掉两次额度。 */
    private fun recordAcceptedWakeUp(preferences: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        val history = decodeHistory(preferences.getString(HISTORY_KEY, null))
        runCatching {
            preferences.edit()
                .putString(HISTORY_KEY, encodeHistory((history + now).takeLast(HISTORY_LIMIT)))
                .apply()
        }
    }

    private val PENDING_LOCK = Any()

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
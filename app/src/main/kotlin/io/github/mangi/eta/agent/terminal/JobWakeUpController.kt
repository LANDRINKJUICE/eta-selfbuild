package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
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
        if (remainingWakeUps(history, now) <= 0) {
            logger.warn("Job wake-up skipped: rate limit reached")
            return
        }
        runCatching {
            preferences.edit()
                .putString(HISTORY_KEY, encodeHistory((history + now).takeLast(HISTORY_LIMIT)))
                .apply()
        }
        val app = context.applicationContext
        Thread({ run(app, task, logTail, logger) }, "eta-job-wakeup").apply { isDaemon = true }.start()
    }

    private fun run(app: Context, task: DetachedTask, logTail: String, logger: AgentLogger) {
        val config = runCatching { runBlocking { RuntimeConfigRepository.currentRuntimeConfig() } }
            .getOrNull()
        if (config == null) {
            logger.warn("Job wake-up skipped: no runtime config")
            return
        }
        val leaseId = "job-wakeup:${task.id}"
        val acquired = runCatching {
            AgentExecutionService.acquire(app, leaseId, allowBoundFallback = false) { }
        }.getOrDefault(false)
        try {
            val client = AgentRuntimeClient(app, logger)
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = "job-wakeup-${task.id}-${System.currentTimeMillis()}",
                    prompt = buildPrompt(task, logTail),
                    config = config,
                    images = emptyList(),
                ),
                onEvent = { },
            )
            if (!result.ok) {
                logger.warn("Job wake-up run failed: ${result.error?.take(200).orEmpty()}")
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

    /** 交给 Agent 的指令：事实在前，要求在后，明确禁止起同样的任务重试。 */
    internal fun buildPrompt(task: DetachedTask, logTail: String): String = buildString {
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
        appendLine()
        appendLine("要求：")
        appendLine("1. 先看日志确认是不是真失败，有些命令非 0 退出属于预期。")
        appendLine("2. 能修就直接修（改配置、换参数、重试），修完说明结果。")
        appendLine("3. 修不了就给出结论和下一步建议。")
        appendLine("4. 不要再起同样的后台任务重试，避免循环。")
    }

    private fun decodeHistory(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    private fun encodeHistory(history: List<Long>): String = history.joinToString(",")
}

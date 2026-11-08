package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 入口进程侧的 Runtime 客户端。
 *
 * 它只负责把一次 Agent 请求交给模块进程，并把事件/结果带回入口适配层；
 * 不执行模型、不执行工具、不渲染 UI。
 */
internal class AgentRuntimeClient(
    private val context: Context,
    private val logger: AgentLogger
) {
    sealed interface AttachOutcome {
        data class Completed(val result: AgentRuntimeWire.RunResult) : AttachOutcome
        data object NotActive : AttachOutcome
        data object Unavailable : AttachOutcome
    }

    sealed interface ActiveRunQuery {
        data class Known(val runId: String?) : ActiveRunQuery
        data object Unavailable : ActiveRunQuery
    }

    sealed interface ActiveRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.ActiveRunSnapshot>) : ActiveRunsQuery {
            val runIds: Set<String> get() = runs.mapTo(linkedSetOf()) { it.runId }
        }
        data object Unavailable : ActiveRunsQuery
    }

    sealed interface CompletedRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.CompletedRun>) : CompletedRunsQuery
        data object Unavailable : CompletedRunsQuery
    }

    fun run(
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit,
        /**
         * 等待结果的超时秒数；0 表示不限。
         *
         * 交互式入口（聊天、通知回复）不传，跑多久都等；**后台入口必须传**——
         * `:schedule` 那种进程等主进程回结果时，主进程一忙或被厂商杀掉就会永久挂住，
         * 任务既不成功也不失败，通知永远不来（2026-11-04 真机抓到卡 10 分钟）。
         */
        timeoutSeconds: Long = 0L,
    ): AgentRuntimeWire.RunResult {
        val resultLatch = CountDownLatch(1)
        val resultRef = AgentResultMailbox()
        val preparedImagesRef = AtomicReference<AgentRuntimeImageTransfer.PreparedImages?>()
        val clientMessenger = Messenger(
            ClientHandler(
                onEvent = onEvent,
                onResult = { result ->
                    resultRef.set(result)
                    resultLatch.countDown()
                },
                onRequestIngested = {
                    preparedImagesRef.getAndSet(null)?.close()
                },
            )
        )

        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 服务绑定失败")
        val serviceMessenger = lease.messenger
        val deathRecipient = IBinder.DeathRecipient {
            if (resultRef.get() == null) {
                resultRef.set(
                    AgentRuntimeWire.toBundle(AgentRuntimeWire.RunResult(request.runId, false, "", "Agent Runtime 服务连接已断开", contextSnapshotRef = request.runId))
                )
                resultLatch.countDown()
            }
        }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_START_RUN)
            msg.replyTo = clientMessenger
            val preparedImages = AgentRuntimeImageTransfer.prepare(context, request.images)
            preparedImagesRef.set(preparedImages)
            msg.data = AgentRuntimeWire.toBundle(request, preparedImages.images, context.cacheDir)
            AgentWireText.send(serviceMessenger, msg)
            // 最终结果或 Binder 断连负责唤醒。交互式入口（timeoutSeconds=0）不设上限，
            // 长任务不因客户端等待时长被取消；后台入口带超时，避免主进程不回结果时永久挂住。
            val finished = if (timeoutSeconds > 0L) {
                resultLatch.await(timeoutSeconds, TimeUnit.SECONDS)
            } else {
                resultLatch.await()
                true
            }
            if (!finished) {
                // 超时：主动取消这次 run 并返回失败，让调用方把「执行超时」写进历史与通知，
                // 而不是让线程和任务记录一起悬在那里。
                runCatching {
                    val cancelMessage = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                    cancelMessage.data = AgentRuntimeWire.ackBundle(request.runId)
                    serviceMessenger.send(cancelMessage)
                }
                return AgentRuntimeWire.RunResult(
                    runId = request.runId,
                    contextSnapshotRef = request.runId,
                    ok = false,
                    content = "",
                    error = "执行超时（${timeoutSeconds} 秒未返回结果）",
                )
            }
            return resultRef.get()?.let(AgentRuntimeWire::runResultFromBundle) ?: AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 未返回结果")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching {
                val cancelMessage = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
                cancelMessage.data = AgentRuntimeWire.ackBundle(request.runId)
                serviceMessenger.send(cancelMessage)
            }
            return AgentRuntimeWire.RunResult(request.runId, false, "", "Agent Runtime 等待被中断", contextSnapshotRef = request.runId)
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime start request failed: type=${throwable.safeLogType()}")
            return AgentRuntimeWire.RunResult(
                runId = request.runId,
                contextSnapshotRef = request.runId,
                ok = false,
                content = "",
                error = when (throwable) {
                    is AgentRuntimeWire.PayloadTooLargeException -> throwable.message
                    is AgentRuntimeImageTransfer.ImageTransferException -> throwable.message
                    else -> "Agent Runtime 请求发送失败（${throwable.safeLogType()}）"
                },
            )
        } finally {
            resultRef.close()
            preparedImagesRef.getAndSet(null)?.close()
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }

    fun ackResult(runId: String): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ACK_RESULT)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
            true
        }
    }

    /**
     * 插话：把 [text] 排进 [runId] 这一轮的补充队列，**不打断**当前运行。
     *
     * 返回 true 只代表消息已经投递给 runtime 进程；真正生效的时机是那一轮的下一个回合边界
     * （AgentLoop 在模型请求前 pollSteeringMessage）。run 已经结束时会返回 false，
     * 调用方应退回「排队 / 正常发送」，不要静默丢掉用户输入。
     */
    fun supplement(runId: String, text: String): Boolean {
        if (runId.isBlank() || text.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_SUPPLEMENT)
            msg.data = AgentRuntimeWire.supplementBundle(runId, text)
            serviceMessenger.send(msg)
            true
        }
    }

    fun drainCompletedRuns(): List<AgentRuntimeWire.CompletedRun> {
        return when (val query = queryCompletedRuns()) {
            is CompletedRunsQuery.Known -> query.runs
            CompletedRunsQuery.Unavailable -> emptyList()
        }
    }

    fun queryCompletedRuns(): CompletedRunsQuery {
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<List<AgentRuntimeWire.CompletedRun>>(emptyList())
        val clientMessenger = Messenger(
            DrainHandler { results ->
                resultRef.set(results)
                resultLatch.countDown()
            }
        )

        return withRuntimeMessenger<CompletedRunsQuery>(CompletedRunsQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS)
            msg.data = Bundle().apply { putBoolean("complete_result_refs", true) }
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (resultLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 单条读失败只跳过这一条：整批作废会让其余已完成的结果也白丢一轮，
                // 而且上层分不清「真的没有结果」和「读失败」（2026-11-04 代码体检 #9）。
                // 失败的那条没有 ack，仍留在服务端 outbox，下一轮会重试。
                val resolved = resultRef.get().mapNotNull { completed ->
                    if (completed.result.contextSnapshotRef.isBlank()) return@mapNotNull completed
                    readCompletedResult(serviceMessenger, completed)?.let { full ->
                        completed.copy(result = full)
                    }
                }
                CompletedRunsQuery.Known(resolved)
            } else {
                CompletedRunsQuery.Unavailable
            }
        }
    }

    private fun readCompletedResult(
        service: Messenger,
        completed: AgentRuntimeWire.CompletedRun,
    ): AgentRuntimeWire.RunResult? =
        readResultByOwner(service, completed.result.runId, completed.handoff.payload)

    /**
     * 按 runId + handoff payload 精确取回一条完整终态。
     *
     * 服务端找不到时返回的是占位结果（`error = 完整运行结果不可用`、`contextSnapshotRef = runId`），
     * 这里用 `contextSnapshotRef` 区分真结果与占位，不靠错误文案匹配。
     */
    private fun readResultByOwner(
        service: Messenger,
        runId: String,
        owner: String,
    ): AgentRuntimeWire.RunResult? = AgentResultMailbox().use { mailbox ->
        val received = CountDownLatch(1)
        val receiver = Messenger(ClientHandler(onEvent = {}, onResult = {
            mailbox.set(it)
            received.countDown()
        }, onRequestIngested = {}))
        service.send(Message.obtain(null, AgentRuntimeWire.MSG_READ_CONTEXT_RESULT).apply {
            replyTo = receiver
            data = AgentRuntimeWire.ackBundle(runId).apply {
                putString("context_owner", owner)
            }
        })
        if (!received.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return@use null
        val bundle = mailbox.get() ?: return@use null
        AgentRuntimeWire.runResultFromBundle(bundle).takeIf {
            it.runId == runId && it.contextSnapshotRef.isBlank()
        }
    }

    /**
     * 后台入口用：`run()` 超时或失败后回捞已经落进 outbox 的终态。
     *
     * 为什么需要它：`run()` 等不到结果时会发 MSG_CANCEL 并返回「执行超时」，
     * 但终态可能已经在超时边界之前写进 outbox。直接报超时等于把真实结果丢掉
     * （2026-11-07 12:30 那次定时任务就是这样，只剩一行「执行超时」）。
     */
    fun readCompletedRun(runId: String, owner: String): AgentRuntimeWire.RunResult? {
        if (runId.isBlank()) return null
        return withRuntimeMessenger<AgentRuntimeWire.RunResult?>(null) { serviceMessenger ->
            readResultByOwner(serviceMessenger, runId, owner)
        }
    }

    fun queryActiveRun(): ActiveRunQuery {
        val responseLatch = CountDownLatch(1)
        val runIdRef = AtomicReference("")
        val clientMessenger = Messenger(
            ActiveRunHandler { runId ->
                runIdRef.set(runId)
                responseLatch.countDown()
            }
        )

        return withRuntimeMessenger<ActiveRunQuery>(ActiveRunQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (!responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ActiveRunQuery.Unavailable
            } else {
                ActiveRunQuery.Known(runIdRef.get().takeIf(String::isNotBlank))
            }
        }
    }

    fun queryActiveRuns(): ActiveRunsQuery {
        val responseLatch = CountDownLatch(1)
        val response = AtomicReference<List<AgentRuntimeWire.ActiveRunSnapshot>>()
        val clientMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS_RESPONSE) return
                response.set(AgentRuntimeWire.activeRunsFromBundle(msg.data ?: Bundle()))
                responseLatch.countDown()
            }
        })
        return withRuntimeMessenger<ActiveRunsQuery>(ActiveRunsQuery.Unavailable) { serviceMessenger ->
            serviceMessenger.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUNS).apply {
                replyTo = clientMessenger
            })
            if (responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                ActiveRunsQuery.Known(response.get().orEmpty())
            } else {
                ActiveRunsQuery.Unavailable
            }
        }
    }

    /** 历史一次性交给 onReplay；未指定时沿用 onEvent。后续新增事件始终交给 onEvent。 */
    fun attachRun(
        runId: String,
        onReplay: ((List<AgentEvent>) -> Unit)? = null,
        onEvent: (AgentEvent) -> Unit,
    ): AttachOutcome {
        if (runId.isBlank()) return AttachOutcome.NotActive
        val terminalLatch = CountDownLatch(1)
        val attachLatch = CountDownLatch(1)
        val attachedRef = AtomicReference<Boolean?>(null)
        val resultRef = AgentResultMailbox()
        val clientMessenger = Messenger(
            AttachHandler(
                onReplay = onReplay,
                onEvent = onEvent,
                onAttachResponse = { attached ->
                    attachedRef.set(attached)
                    attachLatch.countDown()
                    if (!attached) terminalLatch.countDown()
                },
                onResult = { result ->
                    resultRef.set(result)
                    attachLatch.countDown()
                    terminalLatch.countDown()
                },
            )
        )
        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AttachOutcome.Unavailable
        val deathRecipient = IBinder.DeathRecipient {
            attachLatch.countDown()
            terminalLatch.countDown()
        }

        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN)
            msg.replyTo = clientMessenger
            msg.data = AgentRuntimeWire.ackBundle(runId)
            lease.messenger.send(msg)
            if (!attachLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return AttachOutcome.Unavailable
            }
            // 不能无限等：run 活着但卡住（工具挂起、GUI 等待、模型流不动）时，
            // 这里会永久阻塞调用线程，主线程调用方就是 ANR（2026-11-04 代码体检 #6）。
            if (!terminalLatch.await(ATTACH_TERMINAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return AttachOutcome.Unavailable
            }
            resultRef.get()?.let { return AttachOutcome.Completed(AgentRuntimeWire.runResultFromBundle(it)) }
            return if (attachedRef.get() == false) {
                AttachOutcome.NotActive
            } else {
                AttachOutcome.Unavailable
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return AttachOutcome.Unavailable
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime attach failed: type=${throwable.safeLogType()}")
            return AttachOutcome.Unavailable
        } finally {
            resultRef.close()
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    private fun <T> withRuntimeMessenger(defaultValue: T, block: (Messenger) -> T): T {
        val lease = AgentRuntimeConnection.acquire(context, logger) ?: return defaultValue
        try {
            return block(lease.messenger)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return defaultValue
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime service call failed: type=${throwable.safeLogType()}")
            return defaultValue
        } finally {
            lease.close()
        }
    }

    private class ClientHandler(
        private val onEvent: (AgentEvent) -> Unit,
        private val onResult: (Bundle) -> Unit,
        private val onRequestIngested: () -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT -> {
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(onEvent)
                }

                AgentRuntimeWire.MSG_RESULT -> {
                    onResult(msg.data ?: return)
                }

                AgentRuntimeWire.MSG_REQUEST_INGESTED -> onRequestIngested()
            }
        }
    }

    private class DrainHandler(
        private val onResults: (List<AgentRuntimeWire.CompletedRun>) -> Unit
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE) {
                onResults(AgentRuntimeWire.completedRunsFromBundle(msg.data ?: return))
            }
        }
    }

    private class ActiveRunHandler(
        private val onResponse: (String) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE) {
                onResponse(AgentRuntimeWire.runIdFromBundle(msg.data ?: return))
            }
        }
    }

    private class AttachHandler(
        onReplay: ((List<AgentEvent>) -> Unit)?,
        onEvent: (AgentEvent) -> Unit,
        onAttachResponse: (Boolean) -> Unit,
        private val onResult: (Bundle) -> Unit,
    ) : Handler(Looper.getMainLooper()) {
        private val delivery = AgentRuntimeAttachDelivery(
            onReplay = onReplay,
            onEvent = onEvent,
            onAttachResponse = onAttachResponse,
            onResult = {},
        )

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT ->
                    AgentRuntimeWire.eventFromBundle(msg.data ?: return)?.let(delivery::event)
                AgentRuntimeWire.MSG_RESULT ->
                    if (delivery.beginResult()) onResult(msg.data ?: return)
                AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE ->
                    delivery.attachResponse(AgentRuntimeWire.attachRunSucceeded(msg.data ?: return))
            }
        }
    }

    private companion object {
        const val RESPONSE_TIMEOUT_SECONDS = 8L

        /**
         * attach 之后等「run 终态」的上限。
         *
         * 单次 run 自身有 300 秒上限（见 ScheduledAgentRunner），这里留足余量；
         * 目的是别让调用线程无限阻塞（2026-11-04 代码体检 #6）。
         */
        const val ATTACH_TERMINAL_TIMEOUT_SECONDS = 600L
    }
}

package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.OpenCodeProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 OpenCode Server（chroot 内 http://127.0.0.1:4098/api，HTTP Basic 鉴权）的接入适配器。
 *
 * 两种模式：
 * - CHAT：会话桥。Eta 会话映射到一个稳定的 OpenCode 会话，每次 run 只提交"未转发过"的
 *   新 user 消息（`POST /session/{id}/message`），回复的文本增量通过 `GET /event` 的
 *   `message.part.delta` / `message.part.updated` 事件以 append-only 方式实时呈现。
 *   OpenCode 自己执行它的工具，Eta 不做本地工具循环，因此不产生 tool_calls，
 *   最终 finish_reason 恒为 stop，单轮 END_TURN 即结束。
 * - 非 CHAT（COMPACTION / REPLY_REWRITE / TRANSCRIPT_REFINE）：单发临时会话。
 *   建临时会话提交完整 prompt，同步取回结果文本后异步删除会话，不发流式事件。
 */
internal object OpenCodeAgentProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "opencode_session_bridge"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = false,
            imageInput = false,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

    // ====== 会话桥注册表（进程级） ======

    private class Bridge(val opencodeSessionId: String) {
        /** 已成功提交到 OpenCode 的 user 消息摘要；重试时据此跳过重复提交。 */
        val forwardedDigests = HashSet<String>()
    }

    private val bridges = HashMap<String, Bridge>()
    private val bridgeLock = Any()

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val config = request.effectiveConfig
        return when (request.purpose) {
            ProviderRequestPurpose.CHAT -> completeSessionBridge(request, config, runController, onEvent)
            else -> completeOneShot(request, config, runController, onEvent)
        }
    }

    // ====== CHAT：会话桥 ======

    private fun completeSessionBridge(
        request: ProviderRequest,
        config: AgentModelClient.ModelConfig,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        runController.throwIfCancelled()
        onEvent(ProviderEvent.RequestStarted)

        val prompt = lastUserText(request.messages)
        if (prompt.isBlank()) {
            return ProviderResponse(emptyAssistantMessage())
        }

        val bridgeKey = "${config.providerId}|${config.baseUrl}|${request.sessionId}"
        val bridge = bridgeFor(bridgeKey, config, runController)
        val base = ProviderUrls.normalizeBaseUrl(config.baseUrl)
        val headers = jsonHeaders(config)
        val presenter = Presenter(onEvent)

        // SSE 尾随线程：在提交之前挂上事件流，让增量随生成实时出现。
        val tailer = EventTailer(base, bridge.opencodeSessionId, headers, runController, presenter)
        tailer.start()

        val digest = digestOf(prompt)
        val alreadyForwarded = synchronized(bridgeLock) { digest in bridge.forwardedDigests }

        if (alreadyForwarded) {
            // 重试场景：消息已在 OpenCode 的执行链路上，轮询会话回到 idle 后重建回合。
            try {
                return rebuildTurn(bridge, base, headers, runController, presenter)
            } finally {
                tailer.stop()
            }
        }

        val binding = runController.register {
            val abortUrl = opencodeUrl(base, "session", bridge.opencodeSessionId, "abort")
            Thread {
                runCatching {
                    AgentHttpClient.client.newCall(
                        Request.Builder().url(abortUrl).headers(headers).post(ByteArray(0).toRequestBody(null)).build()
                    ).execute().close()
                }
            }.apply { isDaemon = true }.start()
        }

        try {
            val body = JSONObject().put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
            modelTarget(config)?.let { (providerId, modelId) ->
                body.put("model", JSONObject().put("providerID", providerId).put("modelID", modelId))
            }
            agentName(config.model)?.let { body.put("agent", it) }

            val url = opencodeUrl(base, "session", bridge.opencodeSessionId, "message")
            val httpRequest = Request.Builder().url(url).headers(headers).post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
            return AuthorizedProviderHttp.execute(
                client = AgentHttpClient.modelClient(request.purpose),
                providerId = config.providerId,
                authMode = config.authMode,
                request = httpRequest,
                controller = runController,
            ) { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                if (!response.isSuccessful) {
                    throw AgentModelFailure.http(response.code, response.peekBody(16_384).string())
                }
                val result = JSONObject(response.body.string())
                synchronized(bridgeLock) { bridge.forwardedDigests.add(digest) }
                tailer.stop()
                return@execute finishBridgeTurn(bridge, base, headers, result, runController, presenter)
            }
        } catch (cancelled: AgentRunCancelledException) {
            throw cancelled
        } catch (e: IOException) {
            // 提交链路中断但 server 可能已接收：查会话消息比对最后一条 user 文本，等价幂等恢复。
            if (sessionHoldsPrompt(bridge, base, headers, runController, prompt)) {
                synchronized(bridgeLock) { bridge.forwardedDigests.add(digest) }
                try {
                    return rebuildTurn(bridge, base, headers, runController, presenter)
                } finally {
                    tailer.stop()
                }
            }
            throw e
        } finally {
            binding.close()
            tailer.stop()
            presenter.closeIfOpen()
        }
    }

    /** 提交成功路径：从响应的 parts 组装 assistant 消息，并把展示补齐到最终全量。 */
    private fun finishBridgeTurn(
        bridge: Bridge,
        base: String,
        headers: okhttp3.Headers,
        result: JSONObject,
        runController: AgentRunController,
        presenter: Presenter,
    ): ProviderResponse {
        val parts = result.optJSONArray("parts") ?: JSONArray()
        val text = mergePartText(parts, kind = "text")
        val reasoning = mergePartText(parts, kind = "reasoning")
        presenter.finalizeWith(parts)

        val assistantMessage = emptyAssistantMessage()
            .put("content", text)
            .put("reasoning_content", reasoning)
            .put("finish_reason", "stop")
        result.optJSONObject("info")?.optJSONObject("tokens")?.let { tokens ->
            tapUsage(tokens)?.let { usage ->
                assistantMessage.put("usage", usageToJson(usage))
                presenter.emitUsage(usage)
            }
        }
        presenter.emitCompleted()
        return ProviderResponse(assistantMessage)
    }

    /** 已转发路径：等待会话回到 idle，再取最后一条 assistant 消息整体重建回合。 */
    private fun rebuildTurn(
        bridge: Bridge,
        base: String,
        headers: okhttp3.Headers,
        runController: AgentRunController,
        presenter: Presenter,
    ): ProviderResponse {
        val deadline = System.currentTimeMillis() + AgentHttpClient.MODEL_READ_TIMEOUT_MS
        var sessionIdle = false
        while (System.currentTimeMillis() < deadline) {
            runController.throwIfCancelled()
            val statusUrl = opencodeUrl(base, "session", "status")
            val response = AgentHttpClient.client.newCall(
                Request.Builder().url(statusUrl).headers(headers).get().build()
            ).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = JSONObject(it.body.string())
                    val status = body.optJSONObject(bridge.opencodeSessionId)?.optString("type").orEmpty()
                    if (status == "idle") sessionIdle = true
                }
            }
            if (sessionIdle) break
            runController.awaitRetryDelay(500)
        }
        runController.throwIfCancelled()
        if (!sessionIdle) throw AgentModelFailure.incompleteStream("OpenCode 会话等待空闲超时，请稍后重试")

        val messagesUrl = opencodeUrl(base, "session", bridge.opencodeSessionId, "message")
        val response = AgentHttpClient.client.newCall(
            Request.Builder().url(messagesUrl).headers(headers).get().build()
        ).execute()
        response.use {
            if (!it.isSuccessful) throw AgentModelFailure.http(it.code, it.peekBody(16_384).string())
            val list = JSONArray(it.body.string())
            for (index in list.length() - 1 downTo 0) {
                val entry = list.optJSONObject(index) ?: continue
                val info = entry.optJSONObject("info") ?: continue
                if (info.optString("role") != "assistant") continue
                val parts = entry.optJSONArray("parts") ?: JSONArray()
                val text = mergePartText(parts, kind = "text")
                val reasoning = mergePartText(parts, kind = "reasoning")
                presenter.finalizeWith(parts)
                val assistantMessage = emptyAssistantMessage()
                    .put("content", text)
                    .put("reasoning_content", reasoning)
                    .put("finish_reason", "stop")
                info.optJSONObject("tokens")?.let { tokens ->
                    tapUsage(tokens)?.let { usage ->
                        assistantMessage.put("usage", usageToJson(usage))
                        presenter.emitUsage(usage)
                    }
                }
                presenter.emitCompleted()
                return ProviderResponse(assistantMessage)
            }
        }
        throw AgentModelFailure.incompleteStream("OpenCode 会话中没有可恢复的 assistant 回复")
    }

    private fun sessionHoldsPrompt(
        bridge: Bridge,
        base: String,
        headers: okhttp3.Headers,
        runController: AgentRunController,
        prompt: String,
    ): Boolean {
        return try {
            val messagesUrl = opencodeUrl(base, "session", bridge.opencodeSessionId, "message")
            val response = AgentHttpClient.client.newCall(
                Request.Builder().url(messagesUrl).headers(headers).get().build()
            ).execute()
            response.use {
                if (!it.isSuccessful) return false
                val list = JSONArray(it.body.string())
                for (index in list.length() - 1 downTo 0) {
                    val entry = list.optJSONObject(index) ?: continue
                    val info = entry.optJSONObject("info") ?: continue
                    if (info.optString("role") == "user") {
                        return mergePartText(entry.optJSONArray("parts") ?: JSONArray(), kind = "text") == prompt
                    }
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // ====== 非 CHAT：单发临时会话 ======

    private fun completeOneShot(
        request: ProviderRequest,
        config: AgentModelClient.ModelConfig,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        runController.throwIfCancelled()
        onEvent(ProviderEvent.RequestStarted)
        val base = ProviderUrls.normalizeBaseUrl(config.baseUrl)
        val headers = jsonHeaders(config)

        val created = createSession(base, headers, runController)
        val sessionId = created.optString("id").ifBlank {
            created.optJSONObject("data")?.optString("id").orEmpty().ifBlank {
                created.optJSONObject("info")?.optString("id").orEmpty()
            }
        }
        if (sessionId.isBlank()) {
            throw AgentModelFailure.incompleteStream("OpenCode 创建临时会话失败：响应缺少会话 id")
        }
        try {
            val body = JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("type", "text").put("text", messagesToPromptText(request.messages))),
            )
            modelTarget(config)?.let { (providerId, modelId) ->
                body.put("model", JSONObject().put("providerID", providerId).put("modelID", modelId))
            }
            agentName(config.model)?.let { body.put("agent", it) }
            val url = opencodeUrl(base, "session", sessionId, "message")
            val httpRequest = Request.Builder()
                .url(url).headers(headers).post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
            return AuthorizedProviderHttp.execute(
                client = AgentHttpClient.modelClient(request.purpose),
                providerId = config.providerId,
                authMode = config.authMode,
                request = httpRequest,
                controller = runController,
            ) { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                if (!response.isSuccessful) {
                    throw AgentModelFailure.http(response.code, response.peekBody(16_384).string())
                }
                val result = JSONObject(response.body.string())
                val text = mergePartText(result.optJSONArray("parts") ?: JSONArray(), kind = "text")
                val assistantMessage = emptyAssistantMessage()
                    .put("content", text)
                    .put("finish_reason", "stop")
                val presenter = Presenter(onEvent)
                result.optJSONObject("info")?.optJSONObject("tokens")?.let { tokens ->
                    tapUsage(tokens)?.let { usage ->
                        assistantMessage.put("usage", usageToJson(usage))
                        presenter.emitUsage(usage)
                    }
                }
                presenter.emitCompleted()
                ProviderResponse(assistantMessage)
            }
        } finally {
            // 临时会话用完全部静默清理，失败不影响主流程。
            Thread {
                runCatching {
                    AgentHttpClient.client.newCall(
                        Request.Builder().url(opencodeUrl(base, "session", sessionId)).headers(headers).delete().build()
                    ).execute().close()
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun createSession(
        base: String,
        headers: okhttp3.Headers,
        runController: AgentRunController,
    ): JSONObject {
        runController.throwIfCancelled()
        val body = JSONObject().put("title", "Eta-${UUID.randomUUID().toString().take(8)}")
        val url = opencodeUrl(base, "session")
        val response = AgentHttpClient.client.newCall(
            Request.Builder().url(url).headers(headers).post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        ).execute()
        response.use {
            if (!it.isSuccessful) throw AgentModelFailure.http(it.code, it.peekBody(16_384).string())
            return JSONObject(it.body.string())
        }
    }

    // ====== 展示器：把 OpenCode 的 part 事件翻译成 Eta 的 Block 事件 ======

    /**
     * append-only 展示器。`message.part.delta` 提供增量；`message.part.updated` 提供
     * 全量对账（漏掉的 delta 由全量补足；全量收缩视作内容被替换，块重新开合）。
     */
    private class Presenter(private val onEvent: (ProviderEvent) -> Unit) {
        private val lock = Any()
        var closed = false
        var nextIndex = 0

        /** partID -> 该 part 已展示长度与展示块。 */
        val shown = HashMap<String, PartView>()
        var activeKind: AssistantBlockKind? = null
        var activeIndex = -1
        var activeContent = StringBuilder()

        class PartView(val kind: AssistantBlockKind, val index: Int, var shownLen: Int)

        fun blockKindOf(partsType: String): AssistantBlockKind? = when (partsType) {
            "text" -> AssistantBlockKind.TEXT
            "reasoning" -> AssistantBlockKind.THINKING
            else -> null
        }

        /** delta 事件：按字段直接推进（field 决定思考/正文）。 */
        fun applyDelta(partId: String, field: String, delta: String) {
            val kind = blockKindOf(field) ?: return
            synchronized(lock) {
                if (closed) return
                val view = shown[partId] ?: openViewLocked(partId, kind)
                if (view.kind != kind) return
                view.shownLen += delta.length
                pushDeltaLocked(view, delta)
            }
        }

        /** 全量事件：对账推进。 */
        fun reconcilePart(partId: String, partsType: String, text: String) {
            val kind = blockKindOf(partsType) ?: return
            synchronized(lock) {
                if (closed) return
                val view = shown[partId] ?: openViewLocked(partId, kind)
                if (view.kind != kind) return
                when {
                    text.length > view.shownLen -> {
                        val delta = text.substring(view.shownLen)
                        view.shownLen = text.length
                        pushDeltaLocked(view, delta)
                    }
                    text.length < view.shownLen -> {
                        // 收缩：结束当前块，丢弃展示进度，内容随后可重新开块。
                        finishActiveLocked()
                        shown.remove(partId)
                    }
                }
            }
        }

        fun partRemoved(partId: String) {
            synchronized(lock) {
                if (closed) return
                finishActiveLocked()
                shown.remove(partId)
            }
        }

        /** 已广播 HostedToolStarted 的工具 part id（去重）。 */
        private val toolStartedIds = HashSet<String>()

        /** 已广播 HostedToolFinished 的工具 part id（去重）。 */
        private val toolFinishedIds = HashSet<String>()

        /**
         * OpenCode 的 tool part 可见性：把服务端工具执行映射到 HostedToolStarted/Finished，
         * 让 Eta 的状态胶囊与消息界面能看到「正在调哪个工具、成没成」，与原生模型工具一致。
         * OpenCode tool part 形如 { type:"tool", id, tool:<name>, state:{ status: pending|running|completed|error, ... } }
         */
        fun handleToolPart(part: JSONObject) {
            val partId = part.optString("id").ifBlank { return }
            val toolName = part.optString("tool").ifBlank { return }
            val state = part.optJSONObject("state")
            val status = state?.optString("status") ?: "running"
            synchronized(lock) {
                if (closed) return
                if (status == "completed" || status == "error") {
                    if (toolFinishedIds.add(partId)) {
                        onEvent(ProviderEvent.HostedToolFinished(partId, toolName, status != "error"))
                    }
                } else if (toolStartedIds.add(partId)) {
                    onEvent(ProviderEvent.HostedToolStarted(partId, toolName))
                }
            }
        }

        fun finalizeWith(finalParts: JSONArray) {
            synchronized(lock) {
                if (closed) return
                for (position in 0 until finalParts.length()) {
                    val part = finalParts.optJSONObject(position) ?: continue
                    val kind = blockKindOf(part.optString("type")) ?: continue
                    val text = part.optString("text")
                    val partId = part.optString("id").ifBlank { "final-$position" }
                    val view = shown[partId] ?: openViewLocked(partId, kind)
                    if (view.kind != kind) continue
                    if (text.length > view.shownLen) {
                        val delta = text.substring(view.shownLen)
                        view.shownLen = text.length
                        pushDeltaLocked(view, delta)
                    }
                    finishViewLocked(view)
                }
                // 兜底：SSE 断流/回合结束时未关闭的工具卡全部收尾，避免 Running 残留。
                toolStartedIds.forEach { partId ->
                    if (toolFinishedIds.add(partId)) {
                        onEvent(ProviderEvent.HostedToolFinished(partId, "", true))
                    }
                }
                finishActiveLocked()
            }
        }

        fun closeIfOpen() {
            synchronized(lock) {
                closed = true
                finishActiveLocked()
            }
        }

        private fun openViewLocked(partId: String, kind: AssistantBlockKind): PartView =
            PartView(kind, nextIndex++, 0).also { shown[partId] = it }

        private fun pushDeltaLocked(view: PartView, delta: String) {
            if (delta.isEmpty()) return
            if (activeKind != view.kind) {
                finishActiveLocked()
                activeKind = view.kind
                activeIndex = view.index
                activeContent = StringBuilder()
                onEvent(ProviderEvent.BlockStart(view.kind, view.index))
            }
            activeContent.append(delta)
            onEvent(ProviderEvent.BlockDelta(view.kind, view.index, delta))
        }

        private fun finishViewLocked(view: PartView) {
            if (activeKind == view.kind && activeIndex == view.index) {
                finishActiveLocked()
            }
        }

        private fun finishActiveLocked() {
            val kind = activeKind ?: return
            onEvent(ProviderEvent.BlockEnd(kind, activeIndex, content = activeContent.toString()))
            activeKind = null
            activeIndex = -1
            activeContent = StringBuilder()
        }

        fun emitUsage(usage: AgentTokenUsage?) {
            usage?.let { onEvent(ProviderEvent.Usage(it)) }
        }

        fun emitCompleted() {
            onEvent(ProviderEvent.Completed("stop"))
        }
    }

    // ====== SSE 事件尾随 ======

    private class EventTailer(
        base: String,
        val sessionId: String,
        val headers: okhttp3.Headers,
        val runController: AgentRunController,
        val presenter: Presenter,
    ) {
        private val url = opencodeUrl(base, "event")
        private val stopped = AtomicBoolean(false)
        private var thread: Thread? = null

        fun start() {
            val newThread = Thread {
                try {
                    val response = AgentHttpClient.client.newCall(
                        Request.Builder().url(url).headers(headers).get().build()
                    ).execute()
                    response.use {
                        if (!it.isSuccessful) return@use
                        val stream = it.body.byteStream()
                        readProviderSse(stream, runController) { _, data ->
                            if (stopped.get()) return@readProviderSse false
                            handleEvent(data)
                            !stopped.get()
                        }
                    }
                } catch (_: Exception) {
                    // 展示链路尽力而为：SSE 断流不影响同步响应主路径。
                }
            }
            newThread.isDaemon = true
            thread = newThread
            newThread.start()
        }

        fun stop() {
            if (stopped.compareAndSet(false, true)) {
                thread?.interrupt()
            }
        }

        private fun handleEvent(data: String): Boolean {
            val payload = try {
                JSONObject(data)
            } catch (_: Exception) {
                return true
            }
            val type = payload.optString("type")
            val properties = payload.optJSONObject("properties") ?: return true
            val eventSessionId = properties.optString("sessionID")
            if (eventSessionId.isNotBlank() && eventSessionId != sessionId) return true
            when (type) {
                "message.part.delta" -> {
                    val field = properties.optString("field")
                    val delta = properties.optString("delta")
                    if (delta.isNotEmpty()) presenter.applyDelta(properties.optString("partID"), field, delta)
                }
                "message.part.updated" -> {
                    val part = properties.optJSONObject("part") ?: return true
                    val partType = part.optString("type")
                    when (partType) {
                        "text", "reasoning" ->
                            presenter.reconcilePart(part.optString("id"), partType, part.optString("text"))
                        "tool" -> presenter.handleToolPart(part)
                    }
                }
                "message.part.removed" -> presenter.partRemoved(properties.optString("partID"))
            }
            return true
        }
    }

    // ====== 协议辅助 ======

    /** 会话桥的 OpenCode 会话按需创建；失败直接抛给调用方。 */
    private fun bridgeFor(
        key: String,
        config: AgentModelClient.ModelConfig,
        runController: AgentRunController,
    ): Bridge = synchronized(bridgeLock) {
        bridges[key]
    } ?: run {
        val base = ProviderUrls.normalizeBaseUrl(config.baseUrl)
        val headers = jsonHeaders(config)
        val created = createSession(base, headers, runController)
        val id = created.optString("id").ifBlank {
            created.optJSONObject("data")?.optString("id").orEmpty().ifBlank {
                created.optJSONObject("info")?.optString("id").orEmpty()
            }
        }
        if (id.isBlank()) throw AgentModelFailure.incompleteStream("OpenCode 创建会话失败：响应缺少会话 id")
        val bridge = Bridge(id)
        synchronized(bridgeLock) { bridges[key] = bridge }
        bridge
    }

    private fun modelTarget(config: AgentModelClient.ModelConfig): Pair<String, String>? {
        val value = config.model.trim()
        if (value.isBlank() || value == "default") return null
        val slash = value.indexOf('/')
        if (slash <= 0) return null
        val providerId = value.take(slash)
        val modelId = value.substring(slash + 1)
        if (providerId.isBlank() || modelId.isBlank()) return null
        return providerId to modelId
    }

    private fun agentName(model: String): String? {
        val value = model.trim()
        if (!value.startsWith("@")) return null
        val name = value.substring(1).trim()
        return name.takeIf { it.isNotBlank() }
    }

    private fun usageToJson(usage: AgentTokenUsage): JSONObject =
        JSONObject().also { json ->
            usage.contextTokens?.let { json.put("total_tokens", it) }
            usage.inputTokens?.let { json.put("input_tokens", it) }
            usage.outputTokens?.let { json.put("output_tokens", it) }
            usage.reasoningTokens?.let { json.put("reasoning_tokens", it) }
            usage.cachedTokens?.let { json.put("cached_tokens", it) }
        }

    private fun tapUsage(tokens: JSONObject): AgentTokenUsage? {
        fun intOf(key: String): Int? = when (val raw = tokens.opt(key)) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
        val input = intOf("input")
        val output = intOf("output")
        val reasoning = intOf("reasoning")
        val cached = tokens.optJSONObject("cache")?.let { cache ->
            when (val raw = cache.opt("read")) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull()
                else -> null
            }
        }
        val usage = AgentTokenUsage(
            contextTokens = listOfNotNull(input, output).takeIf { it.isNotEmpty() }?.sumOf { it },
            inputTokens = input,
            outputTokens = output,
            reasoningTokens = reasoning,
            cachedTokens = cached,
        )
        return usage.takeUnless { it.isEmpty }
    }

    private fun mergePartText(parts: JSONArray, kind: String): String {
        val builder = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            if (part.optString("type") != kind) continue
            val text = part.optString("text")
            if (text.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(text)
        }
        return builder.toString()
    }

    private fun emptyAssistantMessage(): JSONObject =
        JSONObject().put("role", "assistant")

    private fun lastUserText(messages: JSONArray): String {
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "user") continue
            return messageContentText(message)
        }
        return ""
    }

    /** 一次性 prompt 转录：把 wire 消息序列拼接为单条纯文本（compaction / 改写 / 语音精修）。 */
    private fun messagesToPromptText(messages: JSONArray): String {
        val builder = StringBuilder()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            val text = messageContentText(message)
            if (text.isBlank()) continue
            val label = when (role) {
                "system" -> "[系统]"
                "user" -> "[用户]"
                "assistant" -> "[助手]"
                "tool" -> "[工具]"
                else -> "[消息]"
            }
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(label).append('\n').append(text)
        }
        return builder.toString()
    }

    private fun messageContentText(message: JSONObject): String = when (val content = message.opt("content")) {
        is String -> content
        is JSONArray -> {
            val builder = StringBuilder()
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (item.optString("type") != "text") continue
                val text = item.optString("text")
                if (text.isNotEmpty()) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(text)
                }
            }
            builder.toString()
        }
        else -> ""
    }

    private fun digestOf(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun jsonHeaders(config: AgentModelClient.ModelConfig): okhttp3.Headers =
        okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add(
                        "Authorization",
                        okhttp3.Credentials.basic(
                            OpenCodeProviderSetting.DEFAULT_USERNAME,
                            config.apiKey,
                            Charsets.UTF_8,
                        ),
                    )
                }
            }
            .also {
                ProviderRequestHeaders.mergeInto(
                    it, config.baseUrl, config.customHeaders, sessionId = UUID.randomUUID().toString(),
                    authMode = config.authMode, model = config.model,
                )
            }
            .build()

    private fun opencodeUrl(vararg segments: String): String =
        ProviderUrls.normalizeBaseUrl(segments[0]).let { base ->
            base + "/" + segments.drop(1).joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        }
}

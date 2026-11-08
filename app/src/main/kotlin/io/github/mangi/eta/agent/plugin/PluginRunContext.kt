package io.github.mangi.eta.agent.plugin

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.ProotCommandBuilder
import io.github.mangi.eta.agent.tool.DangerousOperationGuard
import io.github.mangi.eta.agent.tool.DangerousOperationPolicy
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 一次 run 里可用的一个插件工具。 */
internal class PluginRunTool(
    val modelName: String,
    val manifest: PluginManifest,
    val granted: Set<PluginPermission>,
)

/**
 * 一次 run 冻结的插件目录（和 MCP 一样：运行中改设置从下一次 run 生效）。
 *
 * 为什么冻结：模型一次任务里可能多次调用同一个插件，如果中途用户在设置页
 * 关了插件或撤了权限，工具表和执行器会不一致 —— 冻结后语义稳定、也好排查。
 */
internal class PluginRunSnapshot(
    val tools: List<PluginRunTool>,
) {
    private val byName = tools.associateBy(PluginRunTool::modelName)

    fun resolve(modelName: String): PluginRunTool? = byName[modelName]

    /** 把插件工具追加进模型可见的工具数组。 */
    fun appendModelTools(destination: JSONArray) {
        tools.forEach { tool ->
            val manifest = tool.manifest
            val description = buildString {
                append("插件「").append(manifest.name).append("」")
                if (manifest.description.isNotBlank()) append("：").append(manifest.description)
                append("（").append(if (manifest.kind == PluginKind.SHELL) "本机命令" else "HTTP 端点").append("）")
            }
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.modelName)
                            .put("description", description)
                            .put("parameters", schemaOf(manifest)),
                    ),
            )
        }
    }

    private fun schemaOf(manifest: PluginManifest): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        manifest.params.forEach { param ->
            properties.put(
                param.name,
                JSONObject()
                    .put(
                        "type",
                        when (param.type) {
                            PluginParamType.NUMBER -> "number"
                            PluginParamType.BOOLEAN -> "boolean"
                            else -> "string"
                        },
                    )
                    .put("description", param.description.ifBlank { param.name }),
            )
            if (param.required) required.put(param.name)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
    }

    companion object {
        val EMPTY = PluginRunSnapshot(emptyList())

        /** 读取已启用插件 + 每个插件当前被授予的权限。 */
        fun load(context: Context): PluginRunSnapshot {
            val tools = PluginRepository.enabledManifests(context).map { manifest ->
                PluginRunTool(
                    modelName = manifest.toolName,
                    manifest = manifest,
                    granted = PluginRepository.granted(context, manifest.id),
                )
            }
            return PluginRunSnapshot(tools)
        }
    }
}

/**
 * 插件工具执行器：参数校验 → 权限 → 确认 → 执行（本机命令 / HTTP）。
 *
 * 安全取舍（2026-11-08 v1，别放松）：
 * 1. 插件**不豁免**危险操作策略：渲染后的命令照样过 [DangerousOperationPolicy]，
 *    命中就弹同一张确认卡片。
 * 2. `rule` 为空的确认不给「这类别问了」—— 插件是第三方写的，一次性放行风险太大。
 * 3. 声明了 `shell:root` 权限的插件，每次调用都必须确认（与 manifest 里的 confirm 无关）。
 * 4. 访问非本机地址必须有 `network:any`；只连 localhost 的插件拿不到更大网络权限。
 */
internal class PluginToolExecutor(
    private val context: Context,
    private val snapshot: PluginRunSnapshot,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    private val rootExecutor = BoundedRootCommandExecutor(logger = AndroidAgentLogger)
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tool = snapshot.resolve(toolCall.name)
            ?: return failure("UNKNOWN_PLUGIN_TOOL", "插件未在本次运行中启用")
        val manifest = tool.manifest
        val args = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return failure("INVALID_ARGUMENT", "插件参数不是 JSON object") }

        val missing = manifest.requiredPermissions() - tool.granted
        if (missing.isNotEmpty()) {
            return failure(
                "PLUGIN_PERMISSION_DENIED",
                "插件「${manifest.name}」缺授权：" + missing.joinToString("、") { it.wire } +
                    "。请到设置里授权后再试。",
            )
        }

        val template = manifest.exec?.command ?: manifest.http?.bodyTemplate.orEmpty()
        val rendered = PluginTemplate.render(template, args, manifest.params)
            ?: return failure("PLUGIN_INVALID_ARGUMENT", "参数不合法（缺必填项，或类型与声明不符）")

        if (manifest.kind == PluginKind.HTTP && !endpointIsLocal(manifest.http?.endpoint.orEmpty()) &&
            PluginPermission.NETWORK_ANY !in manifest.permissions
        ) {
            return failure("PLUGIN_PERMISSION_DENIED", "访问非本机地址需要 network:any 权限")
        }

        when (val gate = confirmIfNeeded(manifest, rendered)) {
            is PluginGate.Denied -> return failure("PLUGIN_USER_DENIED", gate.message)
            PluginGate.Allowed -> Unit
        }

        AndroidAgentLogger.info(
            "plugin_run id=${manifest.id} kind=${manifest.kind.wire} args=${args.toString().take(200)}",
        )
        return when (manifest.kind) {
            PluginKind.SHELL -> runShell(manifest, rendered)
            PluginKind.HTTP -> runHttp(manifest, rendered)
        }
    }

    private sealed interface PluginGate {
        data object Allowed : PluginGate
        data class Denied(val message: String) : PluginGate
    }

    private fun confirmIfNeeded(manifest: PluginManifest, rendered: String): PluginGate {
        val verdict = if (manifest.kind == PluginKind.SHELL) {
            DangerousOperationPolicy.evaluate("terminal", JSONObject().put("command", rendered))
        } else {
            DangerousOperationPolicy.Verdict.Safe
        }
        val rootDeclared = PluginPermission.SHELL_ROOT in manifest.permissions
        val firstTime = manifest.confirm == PluginConfirm.FIRST &&
            !PluginRepository.isFirstConfirmDone(context, manifest.id)
        val needsConfirm = verdict.dangerous || rootDeclared ||
            manifest.confirm == PluginConfirm.ALWAYS || firstTime
        if (!needsConfirm) return PluginGate.Allowed

        val decision = if (verdict.dangerous) {
            DangerousOperationGuard.requestApproval(
                toolName = manifest.toolName,
                reason = verdict.reason.ifBlank { "插件「${manifest.name}」要执行这一步" },
                rule = verdict.rule,
                impact = verdict.impact,
                recovery = verdict.recovery,
                critical = verdict.severity == DangerousOperationPolicy.Severity.CRITICAL,
                detail = rendered,
                allowSessionOption = true,
            )
        } else {
            DangerousOperationGuard.requestApproval(
                toolName = manifest.toolName,
                // rule 留空：插件请求不提供「这类别问了」，避免第三方插件一次性放行
                reason = if (rootDeclared) {
                    "插件「${manifest.name}」声明了 root 权限，每次调用都要你确认"
                } else {
                    "插件「${manifest.name}」想执行这一步"
                },
                rule = "",
                impact = manifest.description.ifBlank { "这是插件声明的操作。" },
                recovery = "",
                critical = false,
                detail = rendered,
                allowSessionOption = false,
            )
        }
        return when (decision) {
            DangerousOperationGuard.Decision.DENY ->
                PluginGate.Denied("用户没有批准这次插件调用")

            DangerousOperationGuard.Decision.ALLOW_ONCE,
            DangerousOperationGuard.Decision.ALLOW_SESSION,
            -> {
                if (manifest.confirm == PluginConfirm.FIRST) {
                    PluginRepository.markFirstConfirmDone(context, manifest.id)
                }
                PluginGate.Allowed
            }
        }
    }

    private fun runShell(manifest: PluginManifest, command: String): AgentModelClient.ToolResult {
        val target = manifest.exec ?: return failure("PLUGIN_BAD_CONFIG", "插件缺少 exec 段")
        val wrapped = buildString {
            // 让插件自带脚本可以用 $PLUGIN_DIR 定位自己，不必把绝对路径写进 manifest。
            append("export PLUGIN_DIR=").append(PluginTemplate.shellQuote(manifest.directory)).append("; ")
            append(command)
        }
        val payload = if (target.environment == "linux") {
            val distribution = LinuxEnvironmentSettingsRepository.current(context)
            val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs)) {
                return failure("LINUX_ENV_UNAVAILABLE", "Linux 环境未就绪：$rootfs")
            }
            ProotCommandBuilder.payload(rootfsPath = rootfs, command = wrapped)
        } else {
            wrapped
        }
        // 注意：底层执行器把单次超时夹在 30 秒内（它自己的上限），插件里写更长也不会生效。
        val result = rootExecutor.execute(
            command = payload,
            timeoutMillis = target.timeoutMs,
            maxOutputBytes = MAX_OUTPUT_BYTES,
        )
        val output = buildString {
            append("exit=").append(result.exitCode)
            if (result.timedOut) append("（超时）")
            if (result.truncated) append("（输出已截断）")
            if (result.errorCode.isNotBlank()) append(" error=").append(result.errorCode)
            append('\n')
            if (result.stdout.isNotBlank()) append(result.stdout.trimEnd())
            if (result.stderr.isNotBlank()) append("\n[stderr]\n").append(result.stderr.trimEnd())
        }
        return AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", result.ok)
                .put("output", output.take(MAX_RESULT_CHARS))
                .toString(),
            command = payload,
        )
    }

    private fun runHttp(manifest: PluginManifest, body: String): AgentModelClient.ToolResult {
        val target = manifest.http ?: return failure("PLUGIN_BAD_CONFIG", "插件缺少 http 段")
        val url = if (target.method == "GET" && body.isNotBlank()) {
            target.endpoint + (if (target.endpoint.contains('?')) "&" else "?") + body.trimStart('&')
        } else {
            target.endpoint
        }
        val response = runCatching {
            httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .apply {
                        if (target.method == "GET") {
                            get()
                        } else {
                            val mediaType = if (target.contentType == "json") {
                                "application/json; charset=utf-8"
                            } else {
                                "application/x-www-form-urlencoded; charset=utf-8"
                            }
                            method(target.method, body.toRequestBody(mediaType.toMediaType()))
                        }
                    }
                    .build(),
            ).execute()
        }.getOrElse { throwable ->
            return failure("PLUGIN_HTTP_FAILED", "请求失败：${throwable.javaClass.simpleName}")
        }
        response.use { result ->
            val text = runCatching { result.body.string() }.getOrDefault("")
            val picked = pickResponse(text, target.responsePath)
            return AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", result.isSuccessful)
                    .put("status", result.code)
                    .put("output", picked.take(MAX_RESULT_CHARS))
                    .toString(),
            )
        }
    }

    /** 按 `a.b.c` 取 JSON 字段；取不到就返回原文。 */
    private fun pickResponse(text: String, path: String): String {
        if (path.isBlank()) return text
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return text
        var current: Any? = json
        for (segment in path.split('.')) {
            current = (current as? JSONObject)?.opt(segment) ?: return text
        }
        return when (current) {
            null, JSONObject.NULL -> ""
            is String -> current
            else -> current.toString()
        }
    }

    private fun endpointIsLocal(endpoint: String): Boolean =
        endpoint.startsWith("http://127.0.0.1") ||
            endpoint.startsWith("http://localhost") ||
            endpoint.startsWith("http://[::1]")

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject().put("ok", false).put("code", code).put("message", message).toString(),
        )

    override fun close() {
        runCatching { rootExecutor.close() }
    }

    private companion object {
        const val HTTP_TIMEOUT_SECONDS = 60L
        const val MAX_OUTPUT_BYTES = 256 * 1024
        const val MAX_RESULT_CHARS = 4_000
    }
}

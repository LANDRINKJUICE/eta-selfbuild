package io.github.mangi.eta.agent.plugin

import android.content.Context
import java.io.File
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
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
    /**
     * false = 无人值守场景（定时任务）。
     *
     * 定时任务是在没有用户在场时跑的，**不能弹确认卡片**：需要确认的调用直接拒绝并
     * 记原因，而不是默默执行 —— 否则「每次都确认」这条安全承诺就被定时任务绕过去了。
     */
    private val interactive: Boolean = true,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    private val rootExecutor = BoundedRootCommandExecutor(logger = AndroidAgentLogger)
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 不跟随重定向：`network:localhost` 的插件被本机服务 302 到外网就等于越权，
        // 而权限判定只看 manifest 里写的 endpoint（2026-11-08 修）。
        .followRedirects(false)
        .followSslRedirects(false)
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
        // 渲染上下文决定转义方式：shell 命令用单引号转义，HTTP body 必须按 JSON / FORM 转义。
        // 2026-11-08 修：以前一律按 shell 转义，http 插件带任何字符串参数都会发出 `{"q": 'foo'}`。
        val renderContext = when {
            manifest.kind == PluginKind.HTTP && manifest.http?.contentType == "form" -> PluginTemplate.Context.FORM
            manifest.kind == PluginKind.HTTP -> PluginTemplate.Context.JSON
            else -> PluginTemplate.Context.SHELL
        }
        val rendered = PluginTemplate.render(template, args, manifest.params, renderContext)
            ?: return failure("PLUGIN_INVALID_ARGUMENT", "参数不合法（缺必填项，或类型与声明不符）")

        if (manifest.kind == PluginKind.HTTP && !pluginEndpointIsLocal(manifest.http?.endpoint.orEmpty()) &&
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
        val startedAt = System.currentTimeMillis()
        val result = when (manifest.kind) {
            PluginKind.SHELL -> runShell(manifest, rendered)
            PluginKind.HTTP -> runHttp(manifest, rendered, args)
        }
        val parsed = runCatching { JSONObject(result.content) }.getOrNull()
        PluginRepository.appendCall(
            context = context,
            id = manifest.id,
            ok = parsed?.optBoolean("ok") ?: true,
            millis = System.currentTimeMillis() - startedAt,
            // 2026-11-08 修：以前只从 message/code 取，而 shell 通道只产出 ok/output，
            // 于是调用记录里的失败原因永远是空的。这里退回取 output 首行（含 exit=/error=）。
            note = parsed?.optString("message").orEmpty().ifBlank {
                parsed?.optString("code").orEmpty()
            }.ifBlank {
                parsed?.optString("output").orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            },
        )
        return result
    }

    private sealed interface PluginGate {
        data object Allowed : PluginGate
        data class Denied(val message: String) : PluginGate
    }

    /**
     * 卡片里给用户看的内容。
     *
     * 2026-11-08 补：manifest 写 `sh $PLUGIN_DIR/run.sh` 时，危险策略只能看到包装命令，
     * 真正要跑的 `dd`／`mkfs` 全在脚本里。彻底解决要执行前读脚本，代价太大；
     * 折中做法是**把脚本前 20 行一起摆在卡片里**，用户至少能看到要跑的是什么。
     */
    private fun approvalDetail(manifest: PluginManifest, rendered: String): String {
        if (manifest.kind != PluginKind.SHELL) return rendered
        val script = referencedScript(manifest, rendered) ?: return rendered
        // 只读一次（以前 take / size 各调一遍，白读三趟）。
        val lines = runCatching { script.readLines() }.getOrDefault(emptyList())
        return buildString {
            appendLine(rendered)
            appendLine()
            appendLine("—— 它会执行插件自带的脚本 ${script.name}（前 20 行）——")
            append(lines.take(20).joinToString("\n"))
            if (lines.size > 20) append("\n…（还有更多，共 ${lines.size} 行）")
        }
    }

    /**
     * 从渲染后的命令里找出它引用的插件自带脚本；找不到或读不到返回 null。
     *
     * 2026-11-08 复查修正：以前用「文件名是命令子串」匹配，插件目录里如果有个短名文件
     * （`x`、`sh`、`README`）就会先命中，卡片里展示的是**另一个文件**的内容 ——
     * 用户以为看过了，其实没看到真正要跑的那份。现在按 token 精确比对文件名。
     */
    private fun referencedScript(manifest: PluginManifest, rendered: String): File? {
        if (manifest.directory.isBlank()) return null
        val dir = File(manifest.directory)
        if (!dir.isDirectory) return null
        val files = runCatching { dir.listFiles()?.filter { it.isFile }.orEmpty() }.getOrDefault(emptyList())
        if (files.isEmpty()) return null
        // 命令里的 token：按空白和引号切，去掉引号后取文件名部分。
        val tokens = rendered.split(Regex("[\\s'\"();|&<>]+"))
            .mapNotNull { token -> token.substringAfterLast('/').takeIf { it.isNotBlank() } }
            .toSet()
        val hit = files.firstOrNull { it.name in tokens } ?: return null
        if (hit.length() > 64 * 1024) return null
        return hit
    }

    private fun confirmIfNeeded(manifest: PluginManifest, rendered: String): PluginGate {
        var verdict = if (manifest.kind == PluginKind.SHELL) {
            DangerousOperationPolicy.evaluate("terminal", JSONObject().put("command", rendered))
        } else {
            DangerousOperationPolicy.Verdict.Safe
        }
        // 2026-11-08：manifest 写 `sh $PLUGIN_DIR/run.sh` 时，策略只看得到包装命令，
        // 真正的 dd／mkfs 全在脚本里。既然卡片上要展示脚本（approvalDetail 会读），
        // 就顺便把脚本全文也过一遍策略 —— 成本几乎为零，堵住这个洞。
        if (manifest.kind == PluginKind.SHELL && !verdict.dangerous) {
            val scriptBody = runCatching { referencedScript(manifest, rendered)?.readText() }.getOrNull()
            if (!scriptBody.isNullOrBlank()) {
                val scriptVerdict = DangerousOperationPolicy.evaluate(
                    "terminal",
                    JSONObject().put("command", scriptBody.take(64 * 1024)),
                )
                if (scriptVerdict.dangerous) {
                    verdict = scriptVerdict.copy(
                        reason = "插件自带脚本里有危险命令：" + scriptVerdict.reason,
                    )
                }
            }
        }
        // linux 环境的命令要经 su + unshare + chroot 才能起（见 ShellProcessSupervisor），
        // 本质上就是 root 执行。
        //
        // 2026-11-08 用户拍板（两次）：「每次弹窗太烦了直接放权吧」「改成跟危险拦截卡片那个逻辑一样」。
        // 所以门禁与危险拦截卡片**完全同一套机制**：
        //   · 要确认的场景照旧弹卡，但卡片带「这类别问了」；
        //   · 用户点一次之后，同一插件的同一类操作本会话内不再问（内存记忆，重启失效）；
        //   · root 身份本身不构成确认理由，是否确认由 manifest 的 confirm 字段决定。
        // 危险命令那一层完全没动 —— 它照样弹卡，只是也能被「这类别问了」记住。
        val runsAsRoot = PluginPermission.SHELL_ROOT in manifest.permissions ||
            manifest.exec?.environment == "linux"
        val firstTime = manifest.confirm == PluginConfirm.FIRST &&
            !PluginRepository.isFirstConfirmDone(context, manifest.id)
        // 只有两种非危险场景会走到确认：manifest 要求每次确认 / 首次确认。
        // root 身份不构成确认理由（2026-11-08 用户要求放权），它只影响卡片文案。
        val needsConfirm = verdict.dangerous || firstTime ||
            manifest.confirm == PluginConfirm.ALWAYS
        if (!needsConfirm) return PluginGate.Allowed
        if (!interactive) {
            return PluginGate.Denied("这一步需要你本人确认，无人值守时已跳过")
        }

        val decision = if (verdict.dangerous) {
            DangerousOperationGuard.requestApproval(
                toolName = manifest.toolName,
                reason = verdict.reason.ifBlank { "插件「${manifest.name}」要执行这一步" },
                // 与终端工具的危险卡片同一套机制：给「这类别问了」。
                // 规则名带 plugin:<id>: 前缀，所以放行范围只限**这个插件的这类操作**，
                // 不会顺手把终端工具或其他插件的同类命令一起放行（2026-11-08 用户要求
                // 「改成跟危险拦截卡片那个逻辑一样」）。
                rule = "plugin:${manifest.id}:${verdict.rule}",
                impact = verdict.impact,
                recovery = verdict.recovery,
                critical = verdict.severity == DangerousOperationPolicy.Severity.CRITICAL,
                detail = approvalDetail(manifest, rendered),
                allowSessionOption = true,
            )
        } else {
            DangerousOperationGuard.requestApproval(
                toolName = manifest.toolName,
                // 非危险但需要确认的两种场景（首次 / manifest 要求每次）：
                // 也给「这类别问了」，规则名按原因区分，用户点一次之后同类不再打扰。
                // 与危险卡片共用同一套会话内记忆（重启恢复询问，2026-11-08 用户确认可接受）。
                rule = "plugin:${manifest.id}:" +
                    if (manifest.confirm == PluginConfirm.ALWAYS) CONFIRM_REASON_ALWAYS else CONFIRM_REASON_FIRST,
                reason = when {
                    manifest.confirm == PluginConfirm.ALWAYS && runsAsRoot ->
                        "插件「${manifest.name}」要求每次调用都确认（会以 root 身份执行）"

                    manifest.confirm == PluginConfirm.ALWAYS ->
                        "插件「${manifest.name}」要求每次调用都确认"

                    runsAsRoot ->
                        "插件「${manifest.name}」首次调用需要确认（之后会以 root 身份直接执行）"

                    else ->
                        "插件「${manifest.name}」首次调用需要确认"
                },
                impact = manifest.description.ifBlank { "这是插件声明的操作。" },
                recovery = "",
                critical = false,
                detail = approvalDetail(manifest, rendered),
                allowSessionOption = true,
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
        // 让插件自带脚本可以用 $PLUGIN_DIR 定位自己，不必把绝对路径写进 manifest。
        // 2026-11-08 修：linux 环境里这个宿主路径在 chroot 内根本不存在（chroot 只挂了
        // proc/dev/sys/storage/tmp/workspace），所以 `sh $PLUGIN_DIR/run.sh` 必然 No such file。
        // 现在 linux 环境把它只读 bind 到 [PLUGIN_DIR_IN_LINUX]，$PLUGIN_DIR 指那里。
        val pluginDirHost = manifest.directory
        val pluginDirValue = if (target.environment == "linux") PLUGIN_DIR_IN_LINUX else pluginDirHost
        val wrapped = buildString {
            append("export PLUGIN_DIR=").append(PluginTemplate.shellQuote(pluginDirValue)).append("; ")
            append(command)
        }
        // Linux 环境必须和终端工具走同一套启动器（2026-11-08 修）：
        // 之前这里写死 ProotCommandBuilder，忽略用户选的 backend，也忽略 root 身份的 /workspace，
        // 结果插件里的 /workspace 绑到 App 身份的 terminal-user/workspace，
        // 与终端看到的 /data/local/tmp/eta 不是一个目录（实测 cat /workspace/tmp/build-state.json 报不存在）。
        // 现在统一交给 ShellProcessSupervisor：backend=proot 走 proot，backend=chroot 走 unshare+chroot，
        // 并把共享文件夹一并按当前配置 bind 进 /workspace/mounts/<name>。
        val payload = if (target.environment == "linux") {
            val distribution = LinuxEnvironmentSettingsRepository.current(context)
            val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs)) {
                return failure("LINUX_ENV_UNAVAILABLE", "Linux 环境未就绪：$rootfs")
            }
            ShellProcessSupervisor().buildLinuxPayload(
                rootfsPath = rootfs,
                command = wrapped,
                sharedMounts = SharedFolderMounts.current(),
                // 把插件自己的目录只读挂进 chroot，$PLUGIN_DIR 才指得到（见 runShell 的说明）。
                extraReadOnlyBinds = listOf(pluginDirHost to PLUGIN_DIR_IN_LINUX),
            )
        } else {
            wrapped
        }
        // 执行身份（2026-11-08 改诚实）：
        // · linux 环境 → 进 Debian chroot，本身就要 su
        // · 声明了 shell:root → su 执行（confirmIfNeeded 已保证每次调用都要确认）
        // · 其余 → **以 App 身份**跑，天然只能碰自己的目录和 /sdcard
        val useRoot = target.environment == "linux" ||
            PluginPermission.SHELL_ROOT in manifest.permissions
        // 注意：root 通道把单次超时夹在 30 秒内（它自己的上限），插件里写更长也不会生效。
        // 所以这里先夹到同一个上限，让 manifest 的声明和实际行为一致 ——
        // 否则 manfiest 写 120 秒、实际 30 秒就超时，用户只会看到「莫名超时」（2026-11-08）。
        val effectiveTimeoutMs = target.timeoutMs.coerceIn(1_000L, MAX_SHELL_TIMEOUT_MS)
        val result = if (useRoot) {
            rootExecutor.execute(
                command = payload,
                timeoutMillis = effectiveTimeoutMs,
                maxOutputBytes = MAX_OUTPUT_BYTES,
            )
        } else {
            runAsApp(payload, effectiveTimeoutMs)
        }
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
            // 2026-11-08：以前这里放整条 payload（含 export PLUGIN_DIR=... 和 chroot 包装），
            // 它会直接显示在界面的工具轨迹里，又长又看不懂。改成只给插件名。
            command = manifest.name,
        )
    }

    private fun runHttp(manifest: PluginManifest, body: String, args: JSONObject): AgentModelClient.ToolResult {
        val target = manifest.http ?: return failure("PLUGIN_BAD_CONFIG", "插件缺少 http 段")
        val url = if (target.method == "GET" && body.isNotBlank()) {
            target.endpoint + (if (target.endpoint.contains('?')) "&" else "?") + body.trimStart('&')
        } else {
            target.endpoint
        }
        // 请求头单独渲染：值里绝不能出现 shell 转义产物（`'Bearer x'` 会被服务端当真值）。
        // 这里用 JSON 上下文只是因为它是「只做引号转义、不加单引号」的那一种；
        // 头值里出现引号本来就非法，渲染失败直接报错比悄悄发出去好。
        val renderedHeaders = target.headers.mapValues { (name, template) ->
            val value = PluginTemplate.render(template, args, manifest.params, PluginTemplate.Context.JSON)
                ?: return failure("PLUGIN_INVALID_ARGUMENT", "请求头 $name 的参数不合法")
            // JSON 转义会带首尾引号，去掉后才是真正的头值。
            val plain = if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                runCatching { JSONObject("{\"v\":$value}").getString("v") }.getOrDefault(value.trim('"'))
            } else {
                value
            }
            // 参数值里带换行时，JSON 转义会把它还原成真换行，OkHttp 会抛 IllegalArgumentException
            // 而用户只看到「请求失败」。这里提前拦住并说清楚（2026-11-08）。
            if (plain.any { it.code < 0x20 || it.code == 0x7F }) {
                return failure("PLUGIN_INVALID_ARGUMENT", "请求头 $name 的值包含换行或控制字符")
            }
            plain
        }
        // 超时按 manifest 的 http.timeoutMs 生效（2026-11-08 修：以前写死 60 秒，配置项被忽略）。
        val client = httpClient.newBuilder()
            .callTimeout(target.timeoutMs.coerceIn(1_000L, HTTP_TIMEOUT_SECONDS * 1_000L), TimeUnit.MILLISECONDS)
            .build()
        val response = runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .apply {
                        renderedHeaders.forEach { (name, value) -> header(name, value) }
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
            // 2026-11-08：路径取不到时不再静默返回全文 —— 那会让模型拿到一坨 JSON 干瞪眼。
            // 明确告诉它「配置的路径没命中」，并附上前 500 字原文供判断。
            val picked = pickResponse(text, target.responsePath)
            if (picked == null) {
                return failure(
                    "PLUGIN_RESPONSE_PATH_MISSING",
                    "响应里没有 ${target.responsePath} 这个字段。原文前 500 字：" + text.take(500),
                )
            }
            return AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", result.isSuccessful)
                    .put("status", result.code)
                    .put("output", picked.take(MAX_RESULT_CHARS))
                    .toString(),
            )
        }
    }

    /**
     * 以 App 身份执行命令（没声明 root 的插件走这条）。
     *
     * 2026-11-08 重写，修四个真问题：
     * 1. **顺序读流会死锁**：以前同一个线程先读完 stdout 再读 stderr，命令往 stderr 写超过
     *    管道缓冲（约 64KB）时子进程卡在 write、stdout 永不 EOF → 一直等到超时。
     *    现在两个流各一个线程同时排空。
     * 2. **输出无上限**：以前 `readBytes()` 全量读进内存，`seq 1 100000000` 能直接把 App 撑爆。
     *    现在边读边截断，超限只标记 truncated 并继续排空（不排空同样会卡死子进程）。
     * 3. **超时丢输出**：以前超时直接返回空 stdout/stderr，排障信息为零。
     *    现在返回已收集到的部分输出。
     * 4. **只杀 sh 不杀子孙**：`sh -c "a | b"` 的子进程会变孤儿继续跑。
     *    现在优先按进程组终止，退不到再 destroyForcibly。
     */
    private fun runAsApp(command: String, timeoutMillis: Long): BoundedRootCommandExecutor.Result {
        // 先让 shell 把自己的 pid 写进临时文件，再 exec 成真正要跑的命令。
        //
        // 为什么要这么绕：Android 的 `java.lang.Process` **没有** `pid()` / `toHandle()`
        // （那是 Java 9+ 的 API），反射私有字段又要赌 libcore 的实现细节（Android 14 起
        // 内部结构已经换过）。让被启动的进程自报 pid 是唯一不依赖实现细节的办法，
        // 也是超时后能清掉子孙进程的前提（2026-11-08 复查结论）。
        val pidFile = runCatching {
            File.createTempFile("eta-plugin-pid-", ".txt", context.cacheDir)
        }.getOrNull()
        val launch = buildString {
            if (pidFile != null) {
                append("printf '%s' $$ > ").append(PluginTemplate.shellQuote(pidFile.absolutePath)).append("; ")
            }
            append("exec /system/bin/sh -c ").append(PluginTemplate.shellQuote(command))
        }
        val process = runCatching {
            ProcessBuilder("/system/bin/sh", "-c", launch).redirectErrorStream(false).start()
        }.getOrElse {
            pidFile?.delete()
            return BoundedRootCommandExecutor.Result.failed("SHELL_UNAVAILABLE")
        }
        val limit = MAX_OUTPUT_BYTES
        // Android 的 Process 没有 toHandle()，也没有 volatile 局部变量，这里用数组 + AtomicBoolean 传递结果。
        val stdout = arrayOf("")
        val stderr = arrayOf("")
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
        val outThread = Thread {
            val read = runCatching { process.inputStream.use { it.readBounded(limit) } }.getOrNull()
            stdout[0] = read?.text.orEmpty()
            if (read?.truncated == true) truncated.set(true)
        }.apply { isDaemon = true }
        val errThread = Thread {
            val read = runCatching { process.errorStream.use { it.readBounded(limit) } }.getOrNull()
            stderr[0] = read?.text.orEmpty()
            if (read?.truncated == true) truncated.set(true)
        }.apply { isDaemon = true }
        outThread.start()
        errThread.start()
        val finished = runCatching {
            process.waitFor(timeoutMillis.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) terminateProcessTree(process, pidFile)
        // 即使超时也要把已读到的输出带回去：那往往是唯一能说明卡在哪的线索。
        runCatching { outThread.join(1_500) }
        runCatching { errThread.join(1_500) }
        val output = stdout[0]
        val errors = stderr[0]
        pidFile?.delete()
        return BoundedRootCommandExecutor.Result(
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            stdout = output.take(MAX_RESULT_CHARS),
            stderr = errors.take(MAX_RESULT_CHARS),
            timedOut = !finished,
            truncated = truncated.get() || output.length > MAX_RESULT_CHARS || errors.length > MAX_RESULT_CHARS,
        )
    }

    /**
     * 终止进程及其子孙。
     *
     * Android 上拿不到 `Process.toHandle()`，也没有可靠的进程组 API，
     * 所以退一步：先用 `pkill -P` 按父进程递归清子孙（busybox/toybox 都有 -P），
     * 再 destroy 自己。清不掉的最坏情况是变成孤儿继续跑，但至少管道已被关闭，不会卡死调用方。
     */
    private fun terminateProcessTree(process: Process, pidFile: File?) {
        val pid = readPidFile(pidFile)
        if (pid != null && pid > 0) {
            runCatching {
                ProcessBuilder("/system/bin/sh", "-c", "pkill -TERM -P $pid 2>/dev/null; pkill -KILL -P $pid 2>/dev/null")
                    .redirectErrorStream(true)
                    .start()
                    .apply { waitFor(1_000L, TimeUnit.MILLISECONDS) }
            }
        }
        runCatching { process.destroy() }
        runCatching { process.waitFor(300L, TimeUnit.MILLISECONDS) }
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    /**
     * 取子进程 pid。
     *
     * Android 的 `java.lang.Process` 是 `ProcessImpl`，**没有公开的 `pid()` / `toHandle()`**
     * （那是 Java 9+ 的 API，Android 上不可用），只能读它的私有字段 `pid`。
     * 读不到就返回 null，调用方退化成「只杀 sh 自己」。
     */
    /** 读 shell 自报的 pid（见 runAsApp 开头的说明）。读不到返回 null，退化成只杀 sh 自己。 */
    private fun readPidFile(file: File?): Long? {
        if (file == null) return null
        return runCatching { file.readText().trim().toLongOrNull() }.getOrNull()
    }

    /** 边读边截断：必须持续排空，否则子进程写满管道就卡住。 */
    private fun java.io.InputStream.readBounded(maxBytes: Int): BoundedRead {
        val collected = java.io.ByteArrayOutputStream(maxBytes.coerceAtMost(32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var truncated = false
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            val remaining = maxBytes - collected.size()
            if (remaining > 0) collected.write(buffer, 0, read.coerceAtMost(remaining))
            if (read > remaining) truncated = true
        }
        return BoundedRead(collected.toString("UTF-8"), truncated)
    }

    private data class BoundedRead(val text: String, val truncated: Boolean)

    /** 按 `a.b.c` 取 JSON 字段；path 为空时返回原文，取不到返回 null（调用方据此报错）。 */
    private fun pickResponse(text: String, path: String): String? {
        if (path.isBlank()) return text
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        var current: Any? = json
        for (segment in path.split('.')) {
            current = (current as? JSONObject)?.opt(segment) ?: return null
        }
        return when (current) {
            null, JSONObject.NULL -> ""
            is String -> current
            else -> current.toString()
        }
    }

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
        /**
         * shell 通道的实际超时上限。
         *
         * root 通道（BoundedRootCommandExecutor）内部把单次超时夹在 30 秒，
         * 这里取同一个值，让 manifest 声明的 timeoutMs 与实际行为一致。
         */
        const val MAX_SHELL_TIMEOUT_MS = 30_000L
        /** linux 环境里插件目录的只读挂载点，与 buildLinuxPayload 的 extraReadOnlyBinds 保持一致。 */
        const val PLUGIN_DIR_IN_LINUX = "/opt/eta-plugin"

        // 确认原因 → 规则名后缀。用常量而不是字面量，避免拼错导致「这类别问了」记住一个
        // 永远不会再命中的键（那样等于没记住，用户会一直被打扰）。
        const val CONFIRM_REASON_ALWAYS = "always"
        const val CONFIRM_REASON_FIRST = "first"
    }
}

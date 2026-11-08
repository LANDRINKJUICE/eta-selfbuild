package io.github.mangi.eta.agent.plugin

import org.json.JSONObject

/**
 * 插件类型：v1 只有两种 —— 跑命令、调 HTTP 端点。
 *
 * 设计取舍（2026-11-08）：**不加载第三方代码进 Eta 进程**（不做 DEX / JS 引擎）。
 * 插件的能力全部通过外部进程或网络端点实现，所以坏插件最坏是"返回垃圾"，
 * 不会拖垮 App，也不需要沙箱引擎。
 */
internal enum class PluginKind(val wire: String) {
    SHELL("shell"),
    HTTP("http"),
    ;

    companion object {
        fun from(raw: String): PluginKind? = entries.firstOrNull { it.wire == raw }
    }
}

/** 调用前的确认策略。`SHELL_ROOT` 权限的插件无论这里写什么，都按 [ALWAYS] 处理。 */
internal enum class PluginConfirm(val wire: String) {
    NEVER("never"),
    FIRST("first"),
    ALWAYS("always"),
    ;

    companion object {
        fun from(raw: String): PluginConfirm? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * 插件可申请的权限。**默认一项都不给**，用户在设置页逐项勾选后才生效。
 *
 * [NETWORK_ANY] 与 [NETWORK_LOCALHOST] 分开，是为了让「只连本机服务」这种插件
 * 不必拿到访问任意地址的能力。
 */
internal enum class PluginPermission(val wire: String) {
    SHELL_ANDROID("shell:android"),
    SHELL_LINUX("shell:linux"),
    SHELL_ROOT("shell:root"),
    NETWORK_LOCALHOST("network:localhost"),
    NETWORK_ANY("network:any"),
    ;
    // 刻意**不提供** `files:read` / `files:write`（2026-11-08 改动）。
    // 原因：不给 root 的插件本来就以 App 身份跑，能读到的只有自己目录与 /sdcard，
    // 给了 root 的插件则整机可读 —— 这两种情况下那两行"文件权限"都强制不了任何东西。
    // 与其给用户一个按下去没有实际效果的开关，不如不给，并把执行身份写清楚。

    companion object {
        fun from(raw: String): PluginPermission? = entries.firstOrNull { it.wire == raw }
    }
}

/** 参数类型。`PATH` 与 `STRING` 的差别只在说明与转义策略上（都按字符串处理并 shell 转义）。 */
internal enum class PluginParamType(val wire: String) {
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    PATH("path"),
    ;

    companion object {
        fun from(raw: String): PluginParamType? = entries.firstOrNull { it.wire == raw }
    }
}

internal data class PluginParam(
    val name: String,
    val type: PluginParamType,
    val required: Boolean,
    val description: String,
)

/** `shell` 型插件的执行目标。 */
internal data class PluginExecTarget(
    /** `android`（宿主命令）或 `linux`（Debian/Alpine chroot）。 */
    val environment: String,
    val command: String,
    val timeoutMs: Long,
)

/** `http` 型插件的执行目标。 */
internal data class PluginHttpTarget(
    val endpoint: String,
    val method: String,
    /** `json` 或 `form`（v1 不做 multipart：本地 ASR 那类请用 form + 文件路径由插件自己读）。 */
    val contentType: String,
    val bodyTemplate: String,
    /** 从响应 JSON 里取哪个字段当结果；空表示整段返回。 */
    val responsePath: String,
    val timeoutMs: Long,
)

internal data class PluginManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val kind: PluginKind,
    val params: List<PluginParam>,
    val permissions: Set<PluginPermission>,
    val confirm: PluginConfirm,
    val minAppVersion: String,
    val exec: PluginExecTarget?,
    val http: PluginHttpTarget?,
    /** 插件目录（加载时写入，方便执行时定位自带脚本）。 */
    val directory: String = "",
) {
    /** 注册给模型的工具名。加前缀避免和内置工具撞名。 */
    val toolName: String get() = "plugin_$id"

    fun paramByName(name: String): PluginParam? = params.firstOrNull { it.name == name }

    /** 执行这个插件**至少**需要哪些权限（manifest 里少写就等于不可能执行，属于配置错误）。 */
    fun requiredPermissions(): Set<PluginPermission> = when (kind) {
        PluginKind.SHELL -> {
            val shell = when (exec?.environment) {
                "linux" -> PluginPermission.SHELL_LINUX
                else -> PluginPermission.SHELL_ANDROID
            }
            setOf(shell)
        }

        PluginKind.HTTP -> {
            val endpoint = http?.endpoint.orEmpty()
            val localOnly = endpoint.startsWith("http://127.0.0.1") ||
                endpoint.startsWith("http://localhost") ||
                endpoint.startsWith("http://[::1]")
            setOf(if (localOnly) PluginPermission.NETWORK_LOCALHOST else PluginPermission.NETWORK_ANY)
        }
    }
}

internal sealed interface PluginParseOutcome {
    data class Ok(val manifest: PluginManifest) : PluginParseOutcome
    data class Failure(val message: String) : PluginParseOutcome
}

/**
 * `plugin.json` 解析与校验。
 *
 * 原则：**宁可拒载并说明原因，也不猜**。字段缺失、类型未知、权限名拼错、
 * 模板引用不存在的参数 —— 全部当作解析失败，设置页会原样显示原因。
 */
internal object PluginManifestParser {
    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{1,31}")
    private val PARAM_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]{0,31}")

    private const val DEFAULT_SHELL_TIMEOUT_MS = 30_000L
    private const val DEFAULT_HTTP_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 300_000L

    fun parse(raw: String, directory: String = ""): PluginParseOutcome {
        val json = runCatching { JSONObject(raw) }.getOrElse {
            return PluginParseOutcome.Failure("plugin.json 不是合法 JSON")
        }

        val id = json.optString("id").trim()
        if (!ID_PATTERN.matches(id)) {
            return PluginParseOutcome.Failure("id 只能是小写字母/数字/下划线/连字符，长度 2–32")
        }
        val kindRaw = json.optString("type").trim()
        val kind = PluginKind.from(kindRaw)
            ?: return PluginParseOutcome.Failure("type 必须是 shell 或 http（当前：${kindRaw.ifBlank { "空" }}）")

        val confirmRaw = json.optString("confirm", PluginConfirm.FIRST.wire).trim().ifBlank { PluginConfirm.FIRST.wire }
        val confirm = PluginConfirm.from(confirmRaw)
            ?: return PluginParseOutcome.Failure("confirm 必须是 never / first / always（当前：$confirmRaw）")

        val permissions = mutableSetOf<PluginPermission>()
        json.optJSONArray("permissions")?.let { array ->
            for (index in 0 until array.length()) {
                val wire = array.optString(index).trim()
                val permission = PluginPermission.from(wire)
                    ?: return PluginParseOutcome.Failure("未知权限：$wire")
                permissions += permission
            }
        }

        val params = mutableListOf<PluginParam>()
        json.optJSONArray("params")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: return PluginParseOutcome.Failure("params[$index] 不是对象")
                val name = item.optString("name").trim()
                if (!PARAM_NAME_PATTERN.matches(name)) {
                    return PluginParseOutcome.Failure("params[$index].name 不合法：${name.ifBlank { "空" }}")
                }
                if (params.any { it.name == name }) {
                    return PluginParseOutcome.Failure("参数重名：$name")
                }
                val typeRaw = item.optString("type", PluginParamType.STRING.wire).trim()
                    .ifBlank { PluginParamType.STRING.wire }
                val type = PluginParamType.from(typeRaw)
                    ?: return PluginParseOutcome.Failure("参数 $name 的类型未知：$typeRaw")
                params += PluginParam(
                    name = name,
                    type = type,
                    required = item.optBoolean("required", false),
                    description = item.optString("description").trim(),
                )
            }
        }

        val exec = if (kind == PluginKind.SHELL) {
            val node = json.optJSONObject("exec")
                ?: return PluginParseOutcome.Failure("shell 插件缺 exec 段")
            val command = node.optString("command").trim()
            if (command.isBlank()) return PluginParseOutcome.Failure("exec.command 为空")
            val environment = node.optString("environment", "android").trim().ifBlank { "android" }
            if (environment != "android" && environment != "linux") {
                return PluginParseOutcome.Failure("exec.environment 只能是 android 或 linux")
            }
            PluginExecTarget(
                environment = environment,
                command = command,
                timeoutMs = timeoutOf(node.opt("timeoutMs"), DEFAULT_SHELL_TIMEOUT_MS),
            )
        } else {
            null
        }

        val http = if (kind == PluginKind.HTTP) {
            val node = json.optJSONObject("http")
                ?: return PluginParseOutcome.Failure("http 插件缺 http 段")
            val endpoint = node.optString("endpoint").trim()
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                return PluginParseOutcome.Failure("http.endpoint 必须是 http(s) 地址")
            }
            val method = node.optString("method", "POST").trim().uppercase().ifBlank { "POST" }
            if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
                return PluginParseOutcome.Failure("http.method 不支持：$method")
            }
            val contentType = node.optString("contentType", "json").trim().lowercase().ifBlank { "json" }
            if (contentType !in setOf("json", "form")) {
                return PluginParseOutcome.Failure("http.contentType 只能是 json 或 form")
            }
            PluginHttpTarget(
                endpoint = endpoint,
                method = method,
                contentType = contentType,
                bodyTemplate = node.optString("bodyTemplate"),
                responsePath = node.optString("responsePath").trim(),
                timeoutMs = timeoutOf(node.opt("timeoutMs"), DEFAULT_HTTP_TIMEOUT_MS),
            )
        } else {
            null
        }

        val template = exec?.command ?: http?.bodyTemplate.orEmpty()
        PluginTemplate.validate(template, params)?.let { problem ->
            return PluginParseOutcome.Failure(problem)
        }

        val manifest = PluginManifest(
            id = id,
            name = json.optString("name").trim().ifBlank { id },
            description = json.optString("description").trim(),
            version = json.optString("version").trim().ifBlank { "0.0.0" },
            kind = kind,
            params = params,
            permissions = permissions,
            confirm = confirm,
            minAppVersion = json.optString("minAppVersion").trim(),
            exec = exec,
            http = http,
            directory = directory,
        )
        // manifest 声明的权限必须覆盖执行所需权限，否则插件永远跑不起来 —— 直接拒载更早暴露问题。
        val missing = manifest.requiredPermissions() - permissions
        if (missing.isNotEmpty()) {
            return PluginParseOutcome.Failure(
                "缺少必要权限声明：" + missing.joinToString(", ") { it.wire },
            )
        }
        return PluginParseOutcome.Ok(manifest)
    }

    private fun timeoutOf(raw: Any?, fallback: Long): Long {
        val value = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        } ?: return fallback
        return value.coerceIn(1_000L, MAX_TIMEOUT_MS)
    }
}

/**
 * 参数模板：`{{name}}` 取值、`{{#flag}}…{{/flag}}` 条件块。
 *
 * **只做替换，不求值** —— 模板里的内容永远进不了 shell 语法，
 * 参数值一律按单引号转义（[shellQuote]），避免注入。
 */
internal object PluginTemplate {
    /** 校验模板只引用了已声明的参数；返回 null 表示没问题。 */
    fun validate(template: String, params: List<PluginParam>): String? {
        val declared = params.mapTo(mutableSetOf()) { it.name }
        for (name in referencedNames(template)) {
            if (name !in declared) return "模板引用了未声明的参数：$name"
        }
        return null
    }

    private fun referencedNames(template: String): List<String> {
        val names = mutableListOf<String>()
        CONDITION_BLOCK.findAll(template).forEach { match -> names += match.groupValues[1] }
        PLACEHOLDER.findAll(removeConditionBlocks(template)).forEach { match -> names += match.groupValues[1] }
        return names
    }

    /** 条件块整体去掉后剩下的正文（用于校验普通占位符，避免把条件块里的 `{{/x}}` 误判）。 */
    private fun removeConditionBlocks(template: String): String =
        CONDITION_BLOCK.replace(template, "")

    /**
     * 渲染模板。返回 null 表示参数不合法（缺必填、类型不符、引用了未声明参数）。
     */
    fun render(template: String, args: JSONObject, params: List<PluginParam>): String? {
        for (param in params) {
            if (!args.has(param.name)) {
                if (param.required) return null
                continue
            }
            if (!typeMatches(param.type, args.opt(param.name))) return null
        }
        var result = template
        // 条件块：flag 为真保留内容，否则整块删除。循环到不动为止，兼容嵌套写法。
        while (true) {
            val next = CONDITION_BLOCK.replace(result) { match ->
                val name = match.groupValues[1]
                val body = match.groupValues[2]
                if (truthy(args.opt(name))) body else ""
            }
            if (next == result) break
            result = next
        }
        result = PLACEHOLDER.replace(result) { match ->
            val name = match.groupValues[1]
            renderValue(args.opt(name))
        }
        return result
    }

    private fun renderValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        else -> shellQuote(value.toString())
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        else -> value.toString().isNotBlank()
    }

    private fun typeMatches(type: PluginParamType, value: Any?): Boolean = when (type) {
        PluginParamType.STRING, PluginParamType.PATH -> value is String
        PluginParamType.NUMBER -> value is Number || (value is String && value.trim().toDoubleOrNull() != null)
        PluginParamType.BOOLEAN -> value is Boolean
    }

    /** POSIX 单引号转义：`it's` → `'it'\''s'`。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val CONDITION_BLOCK = Regex("\\{\\{#([A-Za-z_][A-Za-z0-9_]*)\\}\\}([\\s\\S]*?)\\{\\{/\\1\\}\\}")
    private val PLACEHOLDER = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\}")
}

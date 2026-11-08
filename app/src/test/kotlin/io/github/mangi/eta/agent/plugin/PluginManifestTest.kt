package io.github.mangi.eta.agent.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件清单解析与参数模板的边界测试。
 *
 * 原则（2026-11-08）：**宁可拒载并说明原因，也不猜** —— 所以这里的
 * 大多数用例都在验证"错误配置必须被拒绝"，而不是"能被宽容地解析"。
 */
class PluginManifestTest {

    private fun parsed(raw: String): PluginParseOutcome = PluginManifestParser.parse(raw)

    private fun okManifest(raw: String): PluginManifest = when (val outcome = parsed(raw)) {
        is PluginParseOutcome.Ok -> outcome.manifest
        is PluginParseOutcome.Failure -> error("期望解析成功，实际失败：${outcome.message}")
    }

    private fun failureMessage(raw: String): String = when (val outcome = parsed(raw)) {
        is PluginParseOutcome.Failure -> outcome.message
        is PluginParseOutcome.Ok -> error("期望解析失败，实际成功：${outcome.manifest.id}")
    }

    // ------------------------------------------------------------ 解析

    @Test
    fun shellPluginParses() {
        val manifest = okManifest(
            """
            {
              "id": "gw_status",
              "name": "网关状态",
              "description": "读网关",
              "type": "shell",
              "confirm": "first",
              "permissions": ["shell:linux"],
              "params": [{"name": "verbose", "type": "boolean", "required": false}],
              "exec": {"environment": "linux", "command": "python3 x.py {{#verbose}}--v{{/verbose}}"}
            }
            """.trimIndent(),
        )
        assertEquals("gw_status", manifest.id)
        assertEquals(PluginKind.SHELL, manifest.kind)
        assertEquals("linux", manifest.exec?.environment)
        assertEquals("plugin_gw_status", manifest.toolName)
        assertEquals(setOf(PluginPermission.SHELL_LINUX), manifest.permissions)
    }

    @Test
    fun shellPluginDefaultsToAndroidEnvironment() {
        val manifest = okManifest(
            """
            {"id": "a1", "type": "shell", "permissions": ["shell:android"],
             "exec": {"command": "echo hi"}}
            """.trimIndent(),
        )
        assertEquals("android", manifest.exec?.environment)
        assertEquals("a1", manifest.name)
    }

    @Test
    fun shellPluginWithoutMatchingPermissionIsRejected() {
        // 声明跑 linux 却只给了 android 权限 —— 必须拒载，否则永远跑不起来却看不出原因
        val message = failureMessage(
            """
            {"id": "a2", "type": "shell", "permissions": ["shell:android"],
             "exec": {"environment": "linux", "command": "echo hi"}}
            """.trimIndent(),
        )
        assertTrue(message.contains("shell:linux"))
    }

    @Test
    fun httpPluginNeedsNetworkPermission() {
        val local = okManifest(
            """
            {"id": "asr", "type": "http", "permissions": ["network:localhost"],
             "http": {"endpoint": "http://127.0.0.1:8790/v1/x"}}
            """.trimIndent(),
        )
        assertEquals(setOf(PluginPermission.NETWORK_LOCALHOST), local.requiredPermissions())

        val remote = okManifest(
            """
            {"id": "cloud", "type": "http", "permissions": ["network:any"],
             "http": {"endpoint": "https://example.com/x"}}
            """.trimIndent(),
        )
        assertEquals(setOf(PluginPermission.NETWORK_ANY), remote.requiredPermissions())

        val wrong = failureMessage(
            """
            {"id": "cloud2", "type": "http", "permissions": ["network:localhost"],
             "http": {"endpoint": "https://example.com/x"}}
            """.trimIndent(),
        )
        assertTrue(wrong.contains("network:any"))
    }

    @Test
    fun invalidIdIsRejected() {
        assertTrue(failureMessage("""{"id": "Bad Id", "type": "shell", "permissions": ["shell:android"], "exec": {"command": "x"}}""")
            .contains("id"))
        assertTrue(failureMessage("""{"id": "a", "type": "shell", "permissions": ["shell:android"], "exec": {"command": "x"}}""")
            .contains("id"))
    }

    @Test
    fun unknownTypeIsRejected() {
        assertTrue(failureMessage("""{"id": "a1", "type": "grpc", "permissions": []}""").contains("type"))
    }

    @Test
    fun unknownPermissionIsRejected() {
        // 拼错的权限名绝不能被静默忽略 —— 否则用户以为授权了
        assertTrue(
            failureMessage(
                """{"id": "a1", "type": "shell", "permissions": ["shell:root!"], "exec": {"command": "x"}}""",
            ).contains("未知权限"),
        )
    }

    @Test
    fun templateReferencingUndeclaredParamIsRejected() {
        val message = failureMessage(
            """
            {"id": "a1", "type": "shell", "permissions": ["shell:android"],
             "params": [{"name": "path"}],
             "exec": {"command": "cat {{file}}"}}
            """.trimIndent(),
        )
        assertTrue(message.contains("file"))
    }

    @Test
    fun brokenJsonIsRejected() {
        assertTrue(failureMessage("{oops").contains("JSON"))
    }

    @Test
    fun missingManifestSectionsAreRejected() {
        assertTrue(failureMessage("""{"id": "a1", "type": "shell", "permissions": ["shell:android"]}""").contains("exec"))
        assertTrue(failureMessage("""{"id": "a1", "type": "http", "permissions": ["network:any"]}""").contains("http"))
    }

    // ------------------------------------------------------------ 模板

    private val params = listOf(
        PluginParam("target", PluginParamType.PATH, required = true, description = ""),
        PluginParam("flag", PluginParamType.BOOLEAN, required = false, description = ""),
        PluginParam("count", PluginParamType.NUMBER, required = false, description = ""),
    )

    @Test
    fun shellQuoteEscapesSingleQuote() {
        assertEquals("'it'\\''s'", PluginTemplate.shellQuote("it's"))
        assertEquals("'a b'", PluginTemplate.shellQuote("a b"))
    }

    @Test
    fun placeholderIsQuotedAndConditionBlockRespectsFlag() {
        val rendered = PluginTemplate.render(
            "run {{target}} {{#flag}}--force{{/flag}}",
            JSONObject().put("target", "/sdcard/a b").put("flag", true),
            params,
        )
        assertEquals("run '/sdcard/a b' --force", rendered)

        val withoutFlag = PluginTemplate.render(
            "run {{target}} {{#flag}}--force{{/flag}}",
            JSONObject().put("target", "/x").put("flag", false),
            params,
        )
        assertEquals("run '/x' ", withoutFlag)
    }

    @Test
    fun numberAndBooleanRenderVerbatim() {
        // 注意：params 里的 target 是必填项，缺了它整次渲染都会失败 —— 这正是我们要的语义，
        // 所以这里显式带上（模板本身用不到它）。
        val rendered = PluginTemplate.render(
            "n={{count}} f={{flag}}",
            JSONObject().put("target", "/x").put("count", 3).put("flag", false),
            params,
        )
        assertEquals("n=3 f=false", rendered)
    }

    @Test
    fun missingRequiredParamFailsRender() {
        assertNull(PluginTemplate.render("run {{target}}", JSONObject(), params))
    }

    @Test
    fun wrongTypeFailsRender() {
        assertNull(PluginTemplate.render("run {{count}}", JSONObject().put("count", "abc"), params))
        assertNull(PluginTemplate.render("run {{flag}}", JSONObject().put("flag", "yes"), params))
    }

    // ---------------------------------------------------------------- 2026-11-08 修的渲染上下文

    @Test
    fun jsonContextDoesNotShellQuote() {
        // 回归：以前 http 插件的 body 也按 shell 转义，发出 `{"q": 'foo'}` —— 不是合法 JSON。
        val rendered = PluginTemplate.render(
            """{"q": {{target}}}""",
            JSONObject().put("target", "foo"),
            params,
            PluginTemplate.Context.JSON,
        )
        assertEquals("""{"q": "foo"}""", rendered)
        // 结果必须真的是合法 JSON
        assertEquals("foo", org.json.JSONObject(rendered!!).getString("q"))
    }

    @Test
    fun jsonContextEscapesQuotesAndBackslashes() {
        val rendered = PluginTemplate.render(
            """{"q": {{target}}}""",
            JSONObject().put("target", "he said \"hi\"\\"),
            params,
            PluginTemplate.Context.JSON,
        )
        assertEquals("he said \"hi\"\\", org.json.JSONObject(rendered!!).getString("q"))
    }

    @Test
    fun formContextUrlEncodes() {
        val rendered = PluginTemplate.render(
            "q={{target}}&n=1",
            JSONObject().put("target", "a b&c"),
            params,
            PluginTemplate.Context.FORM,
        )
        assertEquals("q=a+b%26c&n=1", rendered)
    }

    @Test
    fun shellContextStaysQuoted() {
        // 默认上下文仍必须是 shell：这是防注入的底线，不能被上面的改动带走。
        val rendered = PluginTemplate.render("run {{target}}", JSONObject().put("target", "a; rm -rf /"), params)
        assertEquals("run 'a; rm -rf /'", rendered)
    }

    @Test
    fun stringFalseDoesNotEnableConditionBlock() {
        // 回归：以前 truthy(\"false\") 返回 true，`{{#flag}}` 反而被展开。
        val stringParams = listOf(
            PluginParam("target", PluginParamType.STRING, required = true, description = ""),
            PluginParam("flag", PluginParamType.STRING, required = false, description = ""),
        )
        val rendered = PluginTemplate.render(
            "run {{target}}{{#flag}} --force{{/flag}}",
            JSONObject().put("target", "/x").put("flag", "false"),
            stringParams,
        )
        assertEquals("run '/x'", rendered)
        val enabled = PluginTemplate.render(
            "run {{target}}{{#flag}} --force{{/flag}}",
            JSONObject().put("target", "/x").put("flag", "true"),
            stringParams,
        )
        assertEquals("run '/x' --force", enabled)
    }

    @Test
    fun missingOptionalParamRendersEmptyLiteralInShell() {
        // 回归：以前渲染成空字符串，`cmd --arg {{opt}} next` 会变成 `cmd --arg  next` 让参数错位。
        val optionalParams = listOf(
            PluginParam("target", PluginParamType.STRING, required = true, description = ""),
            PluginParam("opt", PluginParamType.STRING, required = false, description = ""),
        )
        val rendered = PluginTemplate.render(
            "cmd --arg {{opt}} next {{target}}",
            JSONObject().put("target", "/x"),
            optionalParams,
        )
        assertEquals("cmd --arg '' next '/x'", rendered)
    }

    @Test
    fun responsePathRejectsArrayIndex() {
        // 回归：以前 items[0].url 这种写法运行时静默退化成「返回整段原文」，模型只能瞎猜。
        val raw = """
            {
              "id": "sample_http",
              "type": "http",
              "permissions": ["network:any"],
              "http": {
                "endpoint": "https://example.com/api",
                "responsePath": "items[0].url"
              }
            }
        """.trimIndent()
        assertTrue(failureMessage(raw).contains("responsePath"))
    }

    @Test
    fun responsePathAcceptsDottedPath() {
        val raw = """
            {
              "id": "sample_http",
              "type": "http",
              "permissions": ["network:any"],
              "http": {
                "endpoint": "https://example.com/api",
                "responsePath": "data.result.url"
              }
            }
        """.trimIndent()
        assertEquals("data.result.url", okManifest(raw).http?.responsePath)
    }

    // ---------------------------------------------------------------- 2026-11-08 复查补的

    @Test
    fun localhostPermissionUsesSameJudgeAsRuntime() {
        // 回归：装载期以前用 startsWith 判「本机」，`127.0.0.1@evil.com` 会被判成本机、
        // 只要 network:localhost；运行期按 URI 判成外部、要 network:any → 永远 PERMISSION_DENIED。
        assertTrue(pluginEndpointIsLocal("http://127.0.0.1:8080/x"))
        assertTrue(pluginEndpointIsLocal("http://localhost:8080/x"))
        assertTrue(pluginEndpointIsLocal("http://[::1]:8080/x"))
        assertFalse(pluginEndpointIsLocal("http://127.0.0.1@evil.com/x"))
        assertFalse(pluginEndpointIsLocal("http://127.0.0.1.nip.io/x"))
        assertFalse(pluginEndpointIsLocal("https://evil.com/x"))
    }

    @Test
    fun spoofedLocalhostEndpointRequiresNetworkAny() {
        // 装载期必须和运行期一个答案：这种写法只能要 network:any，不能只要 localhost。
        val raw = """
            {
              "id": "spoof_local",
              "type": "http",
              "permissions": ["network:any"],
              "http": {"endpoint": "http://127.0.0.1@evil.com/x"}
            }
        """.trimIndent()
        assertEquals(setOf(PluginPermission.NETWORK_ANY), okManifest(raw).requiredPermissions())

        val realLocal = """
            {
              "id": "real_local",
              "type": "http",
              "permissions": ["network:localhost"],
              "http": {"endpoint": "http://127.0.0.1:8080/x"}
            }
        """.trimIndent()
        assertEquals(
            setOf(PluginPermission.NETWORK_LOCALHOST),
            okManifest(realLocal).requiredPermissions(),
        )
    }

    @Test
    fun headersAreParsedAndValidated() {
        val raw = """
            {
              "id": "with_headers",
              "type": "http",
              "permissions": ["network:any"],
              "http": {
                "endpoint": "https://example.com/api",
                "headers": {"Authorization": "Bearer abc", "X-Trace": "1"}
              }
            }
        """.trimIndent()
        assertEquals("Bearer abc", okManifest(raw).http?.headers?.get("Authorization"))
    }

    @Test
    fun headerWithControlCharsIsRejected() {
        // header 注入的经典入口；参数值里的换行会在运行时被 OkHttp 拒绝，装载期就该拦住。
        val raw = """
            {
              "id": "bad_header",
              "type": "http",
              "permissions": ["network:any"],
              "http": {
                "endpoint": "https://example.com/api",
                "headers": {"X-Bad": "a\nb"}
              }
            }
        """.trimIndent()
        assertTrue(failureMessage(raw).contains("headers"))
    }

    @Test
    fun headerTemplateMustReferenceDeclaredParams() {
        val raw = """
            {
              "id": "header_tpl",
              "type": "http",
              "permissions": ["network:any"],
              "http": {
                "endpoint": "https://example.com/api",
                "headers": {"Authorization": "Bearer {{token}}"}
              }
            }
        """.trimIndent()
        assertTrue(failureMessage(raw).contains("token"))
    }
}

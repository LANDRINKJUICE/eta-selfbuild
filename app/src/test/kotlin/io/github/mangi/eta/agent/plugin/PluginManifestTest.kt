package io.github.mangi.eta.agent.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}

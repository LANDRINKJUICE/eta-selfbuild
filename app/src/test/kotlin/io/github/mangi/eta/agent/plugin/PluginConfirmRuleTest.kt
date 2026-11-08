package io.github.mangi.eta.agent.plugin

import io.github.mangi.eta.agent.tool.DangerousOperationGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件确认门禁与危险拦截卡片共用同一套「这类别问了」记忆（2026-11-08 用户要求）。
 *
 * 这里能测的只有**规则名是否稳定可命中**：真正弹卡要 Context 与 UI，得真机验证。
 * 规则名拼错是最隐蔽的失效方式 —— 记忆永远命不中，用户会一直被打扰，
 * 而界面上完全看不出哪里错了。
 */
class PluginConfirmRuleTest {

    @Test
    fun samePluginAndReasonProducesSameRule() {
        val a = pluginConfirmRule("build_state", "always")
        val b = pluginConfirmRule("build_state", "always")
        assertTrue(a == b)
    }

    @Test
    fun differentPluginsDoNotShareMemory() {
        // 用户在 A 插件点过「这类别问了」，不能让 B 插件跟着免确认。
        assertTrue(
            pluginConfirmRule("plugin_a", "always") != pluginConfirmRule("plugin_b", "always"),
        )
    }

    @Test
    fun differentReasonsDoNotShareMemory() {
        // 「首次确认」放行过，不等于「每次都要问」那一类也放行。
        assertTrue(
            pluginConfirmRule("plugin_a", "first") != pluginConfirmRule("plugin_a", "always"),
        )
    }

    @Test
    fun ruleIsNamespacedSoTerminalToolsAreUnaffected() {
        // 规则名带 plugin: 前缀，终端工具的同名规则不会被插件的放行顺带打开。
        val rule = pluginConfirmRule("plugin_a", "always")
        assertTrue(rule.startsWith("plugin:"))
        assertTrue(rule.contains("plugin_a"))
    }

    @Test
    fun guardDoesNotRememberUnknownRuleByDefault() {
        // 兜底：没被记住的规则必须返回 false，否则任何拼错的规则都会被当成「已放行」。
        assertFalse(DangerousOperationGuard.isRuleRemembered("plugin:never_granted:always"))
        assertFalse(DangerousOperationGuard.isRuleRemembered(""))
    }

    /** 与 PluginRunContext 里构造规则的写法保持一致。 */
    private fun pluginConfirmRule(id: String, reason: String): String = "plugin:$id:$reason"
}

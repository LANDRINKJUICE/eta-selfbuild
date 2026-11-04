package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具表开关的回归测试。
 *
 * 背景（2026-11-04 真机事故）：定时任务设了「只读分析」，Agent 还是打开并操作了支付宝。
 * 根因是 `launch_app` / `open_uri` / `tap*` / `type_text` / `press_key` 这几组工具
 * **无条件**加进工具表，任何权限开关都管不到它们 —— 只读等于没关。
 *
 * 这两个用例锁住「只读模式下模型根本看不到这些工具」。
 */
class AgentToolCatalogTest {

    private fun toolNames(interactionTools: Boolean): Set<String> {
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveActionTools = false,
            interactionTools = interactionTools,
        )
        val names = linkedSetOf<String>()
        for (index in 0 until tools.length()) {
            tools.optJSONObject(index)
                ?.optJSONObject("function")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?.let(names::add)
        }
        return names
    }

    @Test
    fun `只读模式摘掉所有会动手的工具`() {
        val names = toolNames(interactionTools = false)

        AgentToolCatalog.INTERACTION_TOOL_NAMES.forEach { tool ->
            assertFalse("只读模式不该暴露 $tool", names.contains(tool))
        }
    }

    @Test
    fun `只读模式保留纯读工具`() {
        val names = toolNames(interactionTools = false)

        assertTrue(names.contains("get_current_context"))
        assertTrue(names.contains("search_apps"))
        assertTrue(names.contains("observe_screen"))
        assertTrue(names.contains("save_screenshot"))
    }

    @Test
    fun `默认模式仍保留交互工具`() {
        val names = toolNames(interactionTools = true)

        assertTrue(names.contains("launch_app"))
        assertTrue(names.contains("open_uri"))
        assertTrue(names.contains("tap"))
        assertTrue(names.contains("type_text"))
    }
}

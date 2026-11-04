package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        /**
         * 是否允许「会动手」的工具（打开应用、点屏幕、输入文字、按键）。
         *
         * 为什么单独一个开关：只读场景（定时任务里的「只读分析」）原来只关
         * `deviceDirectTools` / `deviceSensitiveActionTools`，但 `launch_app`、`open_uri`、
         * `tap*`、`type_text`、`press_key` 这几组是**无条件**加进工具表的 ——
         * 于是「只读」任务照样能 `launch_app(支付宝) → observe_screen → tap`。
         * 2026-11-04 真机事故就是这个：用户明明设了只读，Agent 还是打开了支付宝并操作。
         *
         * 置 false 时保留纯读工具（`get_current_context`、`search_apps`、`observe_screen`、
         * `save_screenshot`、`wait*`、`get_clipboard`），摘掉 [INTERACTION_TOOL_NAMES]。
         */
        interactionTools: Boolean = true,
    ): JSONArray {
        val tools = capabilities.project(JSONArray().also { tools ->
            AgentContextAppToolCatalog.appendTo(tools)
            AgentScreenshotToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools, includeSearch = localWebSearch)
                AgentBrowserToolCatalog.appendTo(tools)
            }
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
                AgentFileToolCatalog.appendTo(tools)
            }
        })
        return if (interactionTools) tools else tools.withoutTools(INTERACTION_TOOL_NAMES)
    }

    /**
     * 「会动手」或「会接触用户界面」的工具：打开应用／交出画面／打开链接／点、长按、滑、滚／
     * 输入、按键、改剪贴板／读屏、截图、读剪贴板。
     *
     * 只读模式把它们从工具表里摘掉。**读屏和截图也算**：
     * 只读任务（通知匹配）手上已经有通知原文，不需要看用户正在用的界面；
     * 而截屏会读到隐私内容、还会占住 GUI 操作权（2026-11-04 事故就是它自己看屏然后去点）。
     *
     * 执行层 [io.github.mangi.eta.agent.tool.AgentLocalTools] 还有一层同样的拦截 ——
     * 目录层让模型看不见，执行层防止别的路径（历史工具名、MCP、子代理）绕过。
     */
    internal val INTERACTION_TOOL_NAMES: Set<String> = setOf(
        "launch_app",
        "open_uri",
        "present_to_user",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "type_text",
        "set_clipboard",
        "press_key",
        "open_system_panel",
        "observe_screen",
        "save_screenshot",
        "get_clipboard",
    )

    /** 按工具名摘除条目，保持原有顺序。 */
    private fun JSONArray.withoutTools(names: Set<String>): JSONArray {
        val kept = JSONArray()
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val name = item.optJSONObject("function")?.optString("name").orEmpty()
            if (name in names) continue
            kept.put(item)
        }
        return kept
    }
}

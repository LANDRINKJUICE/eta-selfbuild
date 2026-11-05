package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作后观察的模型投影：先给结论（界面是否变化、是否切换应用或窗口），再给新的节点列表。
 * 变化判定只比较节点的可见语义（文本、描述、类名、view_id、位置），不依赖时间戳或动画帧。
 */
internal object AgentAfterActionSummary {
    fun build(before: ElementObservation?, after: ElementObservation): JSONObject {
        val changed = before == null || signature(before.nodes) != signature(after.nodes)
        return JSONObject()
            .put("observation_id", after.id)
            .put("package", after.packageName)
            .put("screen_changed", changed)
            .put("package_changed", before != null && before.packageName != after.packageName)
            .put("window_changed", before != null && before.windowId != after.windowId)
            .put("ui_tree_truncated", after.truncated)
            .put("ui_nodes", JSONArray().also { array -> after.nodes.forEach { array.put(it.compactJson()) } })
            .put(
                "note",
                if (changed) "以上为动作后的新界面，可直接用该 observation_id 继续操作；需要更多节点或截图时再调用 observe_screen"
                else "界面没有可见变化，动作可能未生效；请换目标或方式，不要原样重复",
            )
    }

    /** 节点语义指纹：用于判断「界面有没有可见变化」。gui_macro 与动作后快照共用。 */
    internal fun signature(nodes: List<UiNode>): List<String> =
        nodes.map { node ->
            // 开关/单选/焦点/可用性都要进指纹：只比文本与位置的话，「点开一个开关」会被判成
            // 「界面没变化」，模型以为没生效、回头再点一次，把刚打开的又关掉（2026-11-05 审查发现）。
            "${node.className}|${node.viewId}|${node.text}|${node.desc}|${node.bounds.toShortString()}" +
                "|checked=${node.checked}|selected=${node.selected}|focused=${node.focused}" +
                "|enabled=${node.enabled}|editable=${node.editable}|password=${node.password}"
        }

    /** 精简字段：省略默认值为 false 的布尔与包名，降低每步附带观察的 token 开销。 */
    private fun UiNode.compactJson(): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (text.isNotBlank()) put("text", text)
            if (desc.isNotBlank()) put("desc", desc)
            put("class", className.substringAfterLast('.'))
            if (viewId.isNotBlank()) put("view_id", viewId.substringAfterLast('/'))
            put("center", JSONObject().put("x", centerX).put("y", centerY))
            if (clickable) put("clickable", true)
            if (longClickable) put("long_clickable", true)
            if (scrollable) put("scrollable", true)
            if (editable) put("editable", true)
            if (focused) put("focused", true)
            if (password) put("password", true)
            if (!enabled) put("enabled", false)
            checked?.let { put("checked", it) }
            if (selected) put("selected", true)
            if (hint.isNotBlank()) put("hint", hint)
        }
}

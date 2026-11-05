package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentGuiMacroSpec
import org.json.JSONArray
import org.json.JSONObject

/** 屏幕手势与节点交互工具 schema。 */
internal object AgentGestureToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "tap",
                    description = "点击坐标。能用节点时优先 tap_element；看截图定位时用 coordinate_space=normalized（0–999），坐标来自 ui_nodes 时用 screen。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_area",
                    description = "点击矩形区域中心。大按钮、大列表项和可见文字区域优先用这个工具；坐标系同 tap，必须显式填写 coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_element",
                    description = "点击指定观察快照中的 UI 节点。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Eta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press",
                    description = "长按坐标。能用节点时优先 long_press_element；坐标系同 tap，必须显式填写 coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "长按时长，300 到 3000，默认 800")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press_element",
                    description = "长按指定观察快照中的 UI 节点。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Eta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "长按时长，300 到 3000，默认 800")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "swipe",
                    description = "从一个坐标拖到另一个坐标（手指方向），用于拖动滑块、轮播、地图等精确手势。浏览列表请用 scroll。坐标系同 tap，必须显式填写 coordinate_space。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "滑动时长，100 到 2000，默认 500")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll",
                    description = "按内容浏览方向滚动当前屏幕的主列表：down 显示下方内容，up 显示上方内容，left/right 同理。结果里 moved=false 且 at_boundary=true 表示已到底，换方向或结束；页面有多个列表时用 scroll_element 指定目标。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page 约翻一屏（默认），small 只露出少量新内容，用于把目标项微调进视野。"),
                                )
                        )
                        .put("required", JSONArray().put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll_element",
                    description = "按内容浏览方向滚动指定观察快照中的可滚动 UI 节点：down 显示下方内容，up 显示上方内容，left 显示左侧内容，right 显示右侧内容。index 与 observation_id 必须来自同一次最近的 observe_screen；若观察已过期，先重新观察。Runtime 会在执行前确认 Eta 无障碍服务已经连接。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "同一次 observe_screen 返回的可滚动 UI 节点 index。")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "与 index 来自同一次最近 observe_screen 的 observation_id。")
                                )
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                        .put("description", "内容浏览方向；down 显示下方内容，up 显示上方内容。")
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page 约翻一屏（默认），small 只露出少量新内容，用于把目标项微调进视野。"),
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id").put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "gui_macro",
                    description = "一次调用按顺序执行一串界面动作，每步都在设备侧本地校验，出错立刻中止并把当前界面返回。" +
                        "把「点 A → 等文字出现 → 点 B → 滚一下」这类固定流程压成一次调用：不用每步各花一个模型回合" +
                        "（实测每回合约 4 秒，而每次点击只有 0.05–0.1 秒）。适合重复流程、长表单、设置向导。" +
                        "tap_text 命中多个同文本节点时会中止并列出候选（用 view_id 或 nth 消歧），不会瞎点一个。" +
                        "不支持依赖中间结果做判断的流程——那类仍然逐步调用单独工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "steps",
                                JSONObject()
                                    .put("type", "array")
                                    .put("minItems", 1)
                                    .put("maxItems", AgentGuiMacroSpec.MAX_STEPS)
                                    .put(
                                        "description",
                                        "1–${AgentGuiMacroSpec.MAX_STEPS} 步，按顺序执行，任一步失败即中止。" +
                                            "步骤可选 expect_text（这一步之后等该文字出现，超时就中止）与 " +
                                            "verify_change（默认 true：该步之后界面 900ms 内无变化就中止；" +
                                            "Canvas／视频这类没有节点的界面会自动记为「无法校验」而不是失败）。",
                                    )
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties",
                                                JSONObject()
                                                    .put(
                                                        "action",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put(
                                                                "enum",
                                                                JSONArray().also { array ->
                                                                    AgentGuiMacroSpec.ACTIONS.forEach(array::put)
                                                                },
                                                            )
                                                            .put(
                                                                "description",
                                                                "tap_text 按文字找节点并点击；tap_element 用最近一次 observe_screen 的 index；" +
                                                                    "tap/long_press 用坐标；scroll/press_key/type_text/wait_text/wait。",
                                                            ),
                                                    )
                                                    .put(
                                                        "text",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("description", "tap_text / wait_text / type_text 用。"),
                                                    )
                                                    .put(
                                                        "match",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("enum", JSONArray().put("contains").put("exact").put("prefix").put("regex"))
                                                            .put("description", "文字匹配方式，默认 contains，**不区分大小写**（宏内所有文字判据一致）。"),
                                                    )
                                                    .put(
                                                        "view_id",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("description", "tap_text 消歧用：命中多个时只认 view_id 完全一致的那个（写 view_id 尾段即可，如 title）。"),
                                                    )
                                                    .put(
                                                        "nth",
                                                        JSONObject()
                                                            .put("type", "integer")
                                                            .put("description", "tap_text 消歧用：命中多个时取第 n 个（从 0 开始）。不给就命中多个会直接中止并列出候选。"),
                                                    )
                                                    .put("include_desc", JSONObject().put("type", "boolean").put("description", "是否也匹配 content-desc，默认 true。"))
                                                    .put("index", JSONObject().put("type", "integer").put("description", "tap_element 用，取自最近一次 observe_screen。"))
                                                    .put("observation_id", JSONObject().put("type", "string").put("description", "tap_element 用，与 index 同源。"))
                                                    .put("x", JSONObject().put("type", "integer"))
                                                    .put("y", JSONObject().put("type", "integer"))
                                                    .put("coordinate_space", AgentToolSchema.coordinateSpace())
                                                    .put("duration_ms", JSONObject().put("type", "integer").put("description", "long_press 默认 800；wait 默认 1000。"))
                                                    .put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("up").put("down").put("left").put("right")))
                                                    .put("amount", JSONObject().put("type", "string").put("enum", JSONArray().put("page").put("small")))
                                                    .put("button", JSONObject().put("type", "string").put("enum", JSONArray().put("BACK").put("HOME").put("ENTER").put("RECENTS").put("PASTE").put("NOTIFICATIONS").put("QUICK_SETTINGS")))
                                                    .put("mode", JSONObject().put("type", "string").put("enum", JSONArray().put("replace").put("append")))
                                                    .put("submit", JSONObject().put("type", "boolean").put("description", "type_text 写入后是否按回车提交。"))
                                                    .put("timeout_ms", JSONObject().put("type", "integer").put("description", "tap_text 等目标出现 / wait_text 等文字出现的上限，默认 wait_text 5000。"))
                                                    .put("expect_text", JSONObject().put("type", "string").put("description", "这一步之后必须出现的文字，否则中止。"))
                                                    .put("verify_change", JSONObject().put("type", "boolean").put("description", "默认 true：该步之后界面无变化就中止。确定不会变化时设为 false。"))
                                            )
                                            .put("required", JSONArray().put("action"))
                                    )
                            )
                        )
                        .put("required", JSONArray().put("steps"))
                )
            )
    }
}

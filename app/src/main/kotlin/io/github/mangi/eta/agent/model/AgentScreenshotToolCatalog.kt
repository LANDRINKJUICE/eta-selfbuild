package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 屏幕截图落盘为图片文件的工具 schema。 */
internal object AgentScreenshotToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "save_screenshot",
                description = "把当前屏幕截图保存为图片文件（默认存进系统相册 Pictures/Eta），返回可分享的路径或 content URI。" +
                    "仅当用户明确要求保存、导出或分享截图时使用；只是想看界面请用 observe_screen。" +
                    "需要 Root；当前无法截取屏幕时返回错误。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "path",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 1_024)
                                    .put("description", "可选。保存到指定位置的绝对路径（含文件名）；省略时写入系统相册"),
                            )
                            .put(
                                "name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 128)
                                    .put("description", "可选。相册文件名，不含扩展名；省略时使用带时间戳的默认名"),
                            ),
                    )
                    .put("required", JSONArray()),
            ),
        )
    }
}

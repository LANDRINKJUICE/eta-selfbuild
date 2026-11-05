package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「问用户」工具（`ask_user`）。
 *
 * 背景：用户反馈「你们拿不定主意时不会先问我」。光写提示词管不住 ——
 * 模型完全可以认为「我已经想清楚了」直接往下点。所以给一个**会真的挡住**的入口：
 * 调用它在手机 UI 上弹确认卡片，工具线程阻塞等结果（超时按否决）。
 *
 * 开关：`Prefs.Keys.AGENT_ASK_BEFORE_ASSUME`。关着时这个工具仍在目录里，
 * 但执行会直接返回 `ASK_USER_DISABLED`，提示改用最保守的做法 —— 这样模型不会因为
 * 「工具不在目录里」而误以为可以随便猜。
 */
internal object AgentInteractionToolCatalog {
    const val TOOL_NAME = "ask_user"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = TOOL_NAME,
                description = "拿不准就先问：把「现状 + 两个可选方向 + 你建议哪个」写清楚，" +
                    "然后**停下来等用户回答**。手机会弹确认卡片：用户点「继续」= 同意按你建议的做；" +
                    "点「取消」或 120 秒没回应 = 否决（此时不要执行，改成在最终回复里只写这个疑问）。" +
                    "适用：需求有歧义、要动用户数据／金钱／隐私、不可逆操作、结果取决于无法验证的假设。" +
                    "小事别问，不要拿它刷回合。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "question",
                            JSONObject()
                                .put("type", "string")
                                .put(
                                    "description",
                                    "要问用户的话：现在卡在哪、两个可选方向分别是什么、你建议哪个，共不超过 400 字。",
                                ),
                        ),
                    )
                    .put("required", JSONArray().put("question")),
            ),
        )
    }
}

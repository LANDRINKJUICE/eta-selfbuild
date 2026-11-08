package io.github.mangi.eta.agent.plugin

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject

/**
 * 无人值守调用插件（定时任务用）。
 *
 * 与"模型调用插件"的区别只有一条，但很关键：**没有用户在场，不能弹确认**。
 * 所以凡是需要确认的调用（`confirm: always`、首次确认、dangerous、声明了 root）
 * 一律**直接拒绝并说明原因**，绝不静默执行 —— 否则「每次调用都要确认」这条承诺
 * 会被定时任务绕过去。
 *
 * 结论：定时任务只适合挂"纯读、不需要确认"的插件（例如读网关状态、读构建状态）。
 */
internal object PluginInvoker {

    internal sealed interface Outcome {
        /** [millis] 是**纯执行耗时**，不含等确认卡的时间（由执行器回报）。 */
        data class Ok(val output: String, val millis: Long = 0L) : Outcome
        data class Failure(val message: String, val millis: Long = 0L) : Outcome
    }

    fun invokeUnattended(
        context: Context,
        pluginId: String,
        argumentsJson: String = "",
    ): Outcome = invoke(context, pluginId, argumentsJson, interactive = false)

    /** 定时任务的通知只放摘要，太长会撑爆通知栏。 */
    private const val NOTIFICATION_MAX_OUTPUT_CHARS = 400

    /**
     * 详情页试跑要给人看完整输出。
     *
     * 与 shell 通道的结果上限一致（PluginRunContext.MAX_RESULT_CHARS = 4000），
     * 再长也没有意义 —— 那边已经截断了。
     */
    private const val UI_MAX_OUTPUT_CHARS = 4_000

    /**
     * 用户主动点「试跑一次」：**允许弹确认卡片**。
     *
     * 2026-11-08 修：详情页的试跑一开始复用了 [invokeUnattended]，而它内部是
     * `interactive = false`，于是 `confirm: first` 的插件试跑时会被直接拒成
     * 「无人值守时已跳过」—— 用户明明就坐在屏幕前点按钮，却被告知无人值守。
     * 试跑是前台交互行为，确认门禁要按交互式走。
     */
    fun invokeFromUi(context: Context, pluginId: String): Outcome =
        invoke(context, pluginId, "", interactive = true, maxOutputChars = UI_MAX_OUTPUT_CHARS)

    private fun invoke(
        context: Context,
        pluginId: String,
        argumentsJson: String,
        interactive: Boolean,
        maxOutputChars: Int = NOTIFICATION_MAX_OUTPUT_CHARS,
    ): Outcome {
        val id = pluginId.trim()
        if (id.isEmpty()) return Outcome.Failure("没指定插件")
        // 任务里存的参数必须能当对象解析；坏数据直接说清楚，不要静默当空参数跑。
        val argumentText = argumentsJson.trim()
        if (argumentText.isNotEmpty()) {
            runCatching { JSONObject(argumentText) }.getOrNull()
                ?: return Outcome.Failure("任务里的插件参数不是合法 JSON 对象")
        }

        val entry = PluginRepository.scan(context)
            .filterIsInstance<PluginEntry.Loaded>()
            .firstOrNull { it.manifest.id == id }
            ?: return Outcome.Failure("找不到插件「$id」")

        val manifest = entry.manifest
        if (!PluginRepository.isEnabled(context, manifest.id)) {
            return Outcome.Failure("插件「${manifest.name}」没启用")
        }
        val granted = PluginRepository.granted(context, manifest.id)
        val missing = manifest.requiredPermissions() - granted
        if (missing.isNotEmpty()) {
            return Outcome.Failure(
                "插件「${manifest.name}」缺授权：" + missing.joinToString("、") { it.wire },
            )
        }

        val tool = PluginRunTool(manifest.toolName, manifest, granted)
        val executor = PluginToolExecutor(
            context = context,
            snapshot = PluginRunSnapshot(listOf(tool)),
            interactive = interactive,
        )
        return try {
            val result = executor.execute(
                AgentModelClient.ToolCall(
                    id = "scheduled-$id",
                    name = manifest.toolName,
                    argumentsJson = argumentText.ifBlank { "{}" },
                ),
            )
            val parsed = runCatching { JSONObject(result.content) }.getOrNull()
            val elapsed = parsed?.optLong("ms") ?: 0L
            if (parsed?.optBoolean("ok") == true) {
                Outcome.Ok(parsed.optString("output").take(maxOutputChars), elapsed)
            } else {
                Outcome.Failure(
                    // 2026-11-08 修：shell 通道的结果里只有 ok/output（没有 message/code），
                    // 以前失败就显示一句无信息的「执行失败」，退出码和 stderr 全丢。
                    // 现在退回取 output 首行，那里带着 `exit=N` 和错误输出。
                    parsed?.optString("message").orEmpty().ifBlank {
                        parsed?.optString("code").orEmpty()
                    }.ifBlank {
                        parsed?.optString("output").orEmpty()
                            .lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                    }.ifBlank { "执行失败" },
                    elapsed,
                )
            }
        } finally {
            runCatching { executor.close() }
        }
    }
}

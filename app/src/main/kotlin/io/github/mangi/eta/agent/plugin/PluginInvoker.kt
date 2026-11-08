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
        data class Ok(val output: String) : Outcome
        data class Failure(val message: String) : Outcome
    }

    fun invokeUnattended(context: Context, pluginId: String): Outcome {
        val id = pluginId.trim()
        if (id.isEmpty()) return Outcome.Failure("没指定插件")

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
            interactive = false,
        )
        return try {
            val result = executor.execute(
                AgentModelClient.ToolCall(
                    id = "scheduled-$id",
                    name = manifest.toolName,
                    argumentsJson = "{}",
                ),
            )
            val parsed = runCatching { JSONObject(result.content) }.getOrNull()
            if (parsed?.optBoolean("ok") == true) {
                Outcome.Ok(parsed.optString("output").take(400))
            } else {
                Outcome.Failure(
                    parsed?.optString("message").orEmpty().ifBlank {
                        parsed?.optString("code").orEmpty().ifBlank { "执行失败" }
                    },
                )
            }
        } finally {
            runCatching { executor.close() }
        }
    }
}

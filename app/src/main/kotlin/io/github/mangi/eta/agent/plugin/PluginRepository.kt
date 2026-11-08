package io.github.mangi.eta.agent.plugin

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一个插件的加载结果：成功带 manifest，失败带原因（设置页原样显示，不静默忽略）。 */
internal sealed interface PluginEntry {
    val directoryName: String

    data class Loaded(
        override val directoryName: String,
        val manifest: PluginManifest,
    ) : PluginEntry

    data class Broken(
        override val directoryName: String,
        val reason: String,
    ) : PluginEntry
}

/** 一次插件调用的记录：什么时候、成没成、花了多久。 */
internal data class PluginCallRecord(
    val at: Long,
    val ok: Boolean,
    val millis: Long,
    /** 结果摘要（短），失败时是原因。 */
    val note: String,
)

/**
 * 插件目录与用户授权状态。
 *
 * 插件本体 = `filesDir/plugins/<目录名>/plugin.json`（可选自带脚本、`SKILL.md`）；
 * 启用状态、授权、调用记录存 SharedPreferences —— **不落盘到外部存储、不进备份**，
 * 卸载 App 即清空（与 MCP 凭据同一条安全思路）。
 */
internal object PluginRepository {
    private const val PREFS = "eta_plugins"
    private const val KEY_ENABLED = "enabled_"
    private const val KEY_GRANTED = "granted_"
    private const val KEY_CONFIRMED = "confirmed_"
    private const val KEY_CALLS = "calls_"
    private const val MAX_CALLS = 20
    const val MANIFEST_NAME = "plugin.json"

    fun pluginsDir(context: Context): File = File(context.filesDir, "plugins")

    /**
     * 扫描插件目录。目录不存在时返回空列表（不创建 —— 用户没放插件就不该凭空多个目录）。
     *
     * 排序规则：目录名升序，保证设置页与工具表顺序稳定。
     * `.staging-*`（导入临时目录）会被跳过。
     */
    fun scan(context: Context): List<PluginEntry> {
        val root = pluginsDir(context)
        if (!root.isDirectory) return emptyList()
        val files = root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: return emptyList()
        val seen = mutableSetOf<String>()
        val entries = mutableListOf<PluginEntry>()
        for (dir in files) {
            val manifestFile = File(dir, MANIFEST_NAME)
            if (!manifestFile.isFile) {
                entries += PluginEntry.Broken(dir.name, "缺少 $MANIFEST_NAME")
                continue
            }
            val raw = runCatching { manifestFile.readText() }.getOrElse {
                entries += PluginEntry.Broken(dir.name, "读不到 $MANIFEST_NAME")
                continue
            }
            when (val outcome = PluginManifestParser.parse(raw, dir.absolutePath)) {
                is PluginParseOutcome.Failure ->
                    entries += PluginEntry.Broken(dir.name, outcome.message)

                is PluginParseOutcome.Ok -> {
                    if (!seen.add(outcome.manifest.id)) {
                        entries += PluginEntry.Broken(dir.name, "插件 id 与已有插件重复：${outcome.manifest.id}")
                    } else {
                        entries += PluginEntry.Loaded(dir.name, outcome.manifest)
                    }
                }
            }
        }
        return entries
    }

    /** 已启用的插件（权限不足的仍然返回 —— 由执行器判断，这样设置页能提示"缺少授权"）。 */
    fun enabledManifests(context: Context): List<PluginManifest> =
        scan(context).filterIsInstance<PluginEntry.Loaded>()
            .map(PluginEntry.Loaded::manifest)
            .filter { isEnabled(context, it.id) }

    fun isEnabled(context: Context, id: String): Boolean = prefs(context).getBoolean(KEY_ENABLED + id, false)

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED + id, enabled).apply()
    }

    fun granted(context: Context, id: String): Set<PluginPermission> =
        prefs(context).getStringSet(KEY_GRANTED + id, emptySet())
            .orEmpty()
            .mapNotNull(PluginPermission::from)
            .toSet()

    fun setGranted(context: Context, id: String, permissions: Set<PluginPermission>) {
        prefs(context).edit()
            .putStringSet(KEY_GRANTED + id, permissions.mapTo(mutableSetOf()) { it.wire })
            .apply()
    }

    /** `confirm: first` 用：是否已经确认过一次。 */
    fun isFirstConfirmDone(context: Context, id: String): Boolean =
        prefs(context).getBoolean(KEY_CONFIRMED + id, false)

    fun markFirstConfirmDone(context: Context, id: String) {
        prefs(context).edit().putBoolean(KEY_CONFIRMED + id, true).apply()
    }

    // ---------------------------------------------------------------- 调用记录

    /** 记一次调用（最新的排最前，最多留 [MAX_CALLS] 条）。 */
    fun appendCall(context: Context, id: String, ok: Boolean, millis: Long, note: String) {
        val next = listOf(PluginCallRecord(System.currentTimeMillis(), ok, millis, note.take(160))) +
            recentCalls(context, id, MAX_CALLS)
        val array = JSONArray()
        next.take(MAX_CALLS).forEach { record ->
            array.put(
                JSONObject()
                    .put("at", record.at)
                    .put("ok", record.ok)
                    .put("ms", record.millis)
                    .put("note", record.note),
            )
        }
        prefs(context).edit().putString(KEY_CALLS + id, array.toString()).apply()
    }

    fun recentCalls(context: Context, id: String, limit: Int = 5): List<PluginCallRecord> {
        val raw = prefs(context).getString(KEY_CALLS + id, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val records = mutableListOf<PluginCallRecord>()
        for (index in 0 until minOf(array.length(), limit)) {
            val item = array.optJSONObject(index) ?: continue
            records += PluginCallRecord(
                at = item.optLong("at"),
                ok = item.optBoolean("ok"),
                millis = item.optLong("ms"),
                note = item.optString("note"),
            )
        }
        return records
    }

    /** 删除插件后顺手清掉它的授权、状态与调用记录（避免 id 复用拿到旧权限）。 */
    fun forget(context: Context, id: String) {
        prefs(context).edit()
            .remove(KEY_ENABLED + id)
            .remove(KEY_GRANTED + id)
            .remove(KEY_CONFIRMED + id)
            .remove(KEY_CALLS + id)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

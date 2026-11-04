package io.github.mangi.eta.ui.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 技能分组的持久化。
 *
 * 存的是**展示配置**（分组名、成员、顺序、折叠状态），不是技能本体 ——
 * 所以放在 ui 层，跟 [SkillGroupUi] 一起，技能卸载重装不会动到这里。
 *
 * 用 SharedPreferences 而不是文件锁那套：只有 UI 进程读写，量也小（几十个字符串）。
 */
internal object SkillGroupStore {
    private const val PREFS_NAME = "eta_skill_groups"
    private const val KEY_GROUPS = "groups"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<SkillGroupUi> {
        val raw = prefs(context).getString(KEY_GROUPS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::fromJson)
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, groups: List<SkillGroupUi>) {
        val array = JSONArray()
        groups.forEach { group ->
            array.put(
                JSONObject()
                    .put("id", group.id)
                    .put("name", group.name)
                    .put("collapsed", group.collapsed)
                    .put("auto", group.auto)
                    .put("skills", JSONArray(group.skillIds)),
            )
        }
        prefs(context).edit().putString(KEY_GROUPS, array.toString()).apply()
    }

    /** 用户手动重排/改名/折叠后调用：之后不再按自动分类的顺序覆盖它。 */
    fun markManual(groups: List<SkillGroupUi>): List<SkillGroupUi> =
        groups.map { group -> group.copy(auto = false) }

    private fun fromJson(json: JSONObject): SkillGroupUi? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val skills = json.optJSONArray("skills")
        val ids = buildList {
            if (skills != null) {
                for (index in 0 until skills.length()) {
                    skills.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        return SkillGroupUi(
            id = id,
            name = json.optString("name").takeIf { it.isNotBlank() } ?: id,
            skillIds = ids,
            collapsed = json.optBoolean("collapsed", false),
            auto = json.optBoolean("auto", true),
        )
    }
}

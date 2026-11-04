package io.github.mangi.eta.agent.style

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知风格的存储与「当前生效指令」。
 *
 * 几条设计要点：
 * - **默认就是不加任何语气**：`selectedId` 为空表示默认，界面上也不需要显示「自然」这一条
 *   （用户要求：不选角色就是默认，列表里别占位）。
 * - **预置的也归用户管**：首次安装写入 [SpeechStyle.SEEDS] 作起点，之后可以随意改名、改描述、
 *   换头像、删除，删光了也没关系（就是回到默认语气）。
 * - 系统提示每轮都要拼，所以初始化时读一次进内存缓存，UI 改动后同步缓存并落盘。
 */
internal object SpeechStyleStore {
    private const val PREFS_NAME = "eta_speech_style"
    private const val KEY_STYLES = "styles_v2"
    private const val KEY_SELECTED = "selected_id"
    /** 旧版本的字段，只用于一次性迁移。 */
    private const val LEGACY_KEY_CUSTOM = "custom_styles"

    /** 系统提示里那段语气要求的固定前缀，一眼能看出是风格注入。 */
    private const val PREFIX = "表达风格（只影响语气，不改变事实、不省略风险提示、不改动安全规则）："

    @Volatile private var cachedStyles: List<SpeechStyle> = emptyList()
    @Volatile private var cachedSelected: String = ""

    fun init(context: Context) = reload(context.applicationContext)

    /** 界面上可选的风格：**不包含**「默认」这一项（默认用不选中来表示）。 */
    fun visible(): List<SpeechStyle> = cachedStyles

    /** 当前选中的风格；没选返回 null（表示默认语气）。 */
    fun selected(): SpeechStyle? =
        cachedSelected.takeIf { it.isNotBlank() }?.let { id -> cachedStyles.firstOrNull { it.id == id } }

    fun selectedId(): String = cachedSelected

    /** 选中某个风格；传 null 或空串表示回到默认语气。 */
    fun select(context: Context, id: String?) {
        val app = context.applicationContext
        val next = id.orEmpty().takeIf { candidate -> cachedStyles.any { it.id == candidate } }.orEmpty()
        cachedSelected = next
        prefs(app).edit().putString(KEY_SELECTED, next).apply()
    }

    /** 新增或覆盖一条风格；校验失败返回错误文案。 */
    fun upsert(context: Context, style: SpeechStyle): String? {
        val error = SpeechStyle.validate(style.name, style.instruction)
        if (error != null) return error
        val app = context.applicationContext
        val existing = cachedStyles.any { it.id == style.id }
        if (!existing && cachedStyles.size >= SpeechStyle.MAX_STYLES) {
            return "最多只能有 ${SpeechStyle.MAX_STYLES} 个风格"
        }
        cachedStyles = if (existing) {
            cachedStyles.map { if (it.id == style.id) style else it }
        } else {
            cachedStyles + style
        }
        persist(app)
        return null
    }

    /** 删除任意一条（预置的也能删）；删掉当前选中的会回到默认语气。 */
    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        cachedStyles = cachedStyles.filterNot { it.id == id }
        if (cachedSelected == id) {
            cachedSelected = ""
            prefs(app).edit().putString(KEY_SELECTED, "").apply()
        }
        persist(app)
    }

    /** 把一条推荐模板加成自己的风格；成功返回新 id。 */
    fun addTemplate(context: Context, name: String, instruction: String): String? {
        val style = SpeechStyle(
            id = SpeechStyle.newId("$name-$instruction"),
            name = name,
            instruction = instruction,
        )
        return if (upsert(context, style) == null) style.id else null
    }

    /**
     * 给系统提示用的一段话；没选风格就返回空串（不注入任何语气要求）。
     *
     * 固定带上安全边界：用户写的描述可能很随意，不能让它变成放松规则的借口。
     */
    fun instruction(): String {
        val style = selected() ?: return ""
        val body = style.instruction.trim()
        if (body.isEmpty()) return ""
        return PREFIX + body
    }

    private fun reload(context: Context) {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_STYLES, null)
        val styles = if (raw == null) {
            // 首次安装：写入种子（含旧版本遗留的自建风格，做个不丢数据的迁移）。
            val migrated = runCatching {
                val legacy = prefs.getString(LEGACY_KEY_CUSTOM, null) ?: return@runCatching emptyList()
                parse(legacy)
            }.getOrDefault(emptyList())
            SpeechStyle.SEEDS + migrated
        } else {
            runCatching { parse(raw) }.getOrDefault(SpeechStyle.SEEDS)
        }
        cachedStyles = styles
        if (raw == null) persist(context)
        cachedSelected = prefs.getString(KEY_SELECTED, "").orEmpty()
        if (cachedStyles.none { it.id == cachedSelected }) cachedSelected = ""
    }

    private fun parse(raw: String): List<SpeechStyle> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SpeechStyle(
                id = id,
                name = item.optString("name").ifBlank { id },
                instruction = item.optString("instruction"),
                seed = item.optBoolean("seed", false),
                avatarPath = item.optString("avatar").takeIf { it.isNotBlank() },
                tags = item.optJSONArray("tags")?.let { array ->
                    (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
                }.orEmpty(),
            )
        }
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        cachedStyles.forEach { style ->
            array.put(
                JSONObject()
                    .put("id", style.id)
                    .put("name", style.name)
                    .put("instruction", style.instruction)
                    .put("seed", style.seed)
                    .put("avatar", style.avatarPath.orEmpty())
                    .put("tags", org.json.JSONArray(style.tags)),
            )
        }
        prefs(context).edit().putString(KEY_STYLES, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

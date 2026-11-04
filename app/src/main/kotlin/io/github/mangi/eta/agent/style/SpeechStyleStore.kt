package io.github.mangi.eta.agent.style

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 通知角色的存储与「当前生效指令」。
 *
 * **为什么用文件而不是 SharedPreferences**：定时任务跑在 `:schedule` 进程，
 * 用户在界面（主进程）里选角色后，另一个进程的 SharedPreferences 缓存读不到这次写入 ——
 * 表现就是「明明选了可爱，通知还是默认语气」（2026-11-04 真机实测）。
 * 改成单文件 + 修改时间校验，跨进程立即生效。文件不到 2KB，读盘开销可忽略。
 *
 * 其它约定：
 * - **默认就是不加任何语气**：`selected` 为空表示默认，界面上也不显示「自然」这一条
 * - **预置的也归用户管**：首次安装写入 [SpeechStyle.SEEDS] 作起点，之后随意改/删
 */
internal object SpeechStyleStore {
    private const val FILE_NAME = "speech_style.json"
    private const val LEGACY_PREFS = "eta_speech_style"
    private const val LEGACY_KEY_STYLES = "styles_v2"
    private const val LEGACY_KEY_SELECTED = "selected_id"

    /** 系统提示里那段语气要求的固定前缀，一眼能看出是风格注入。 */
    private const val PREFIX = "表达风格（只影响语气，不改变事实、不省略风险提示、不改动安全规则）："

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedStyles: List<SpeechStyle> = emptyList()
    @Volatile private var cachedSelected: String = ""
    @Volatile private var cachedStamp: Long = -1L

    fun init(context: Context) {
        appContext = context.applicationContext
        reload(context.applicationContext)
    }

    /** 界面上可选的风格：不包含「默认」这一项（默认用不选中来表示）。 */
    fun visible(): List<SpeechStyle> {
        ensureFresh()
        return cachedStyles
    }

    fun selected(): SpeechStyle? {
        ensureFresh()
        return cachedSelected.takeIf { it.isNotBlank() }
            ?.let { id -> cachedStyles.firstOrNull { it.id == id } }
    }

    fun selectedId(): String {
        ensureFresh()
        return cachedSelected
    }

    fun select(context: Context, id: String?) {
        val app = context.applicationContext
        appContext = app
        ensureFresh()
        val next = id.orEmpty().takeIf { candidate -> cachedStyles.any { it.id == candidate } }.orEmpty()
        cachedSelected = next
        persist(app)
    }

    /** 新增或覆盖一条风格；校验失败返回错误文案。 */
    fun upsert(context: Context, style: SpeechStyle): String? {
        val error = SpeechStyle.validate(style.name, style.instruction)
        if (error != null) return error
        val app = context.applicationContext
        appContext = app
        ensureFresh()
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
        appContext = app
        ensureFresh()
        cachedStyles = cachedStyles.filterNot { it.id == id }
        if (cachedSelected == id) cachedSelected = ""
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
     * 给系统提示用的一段话；没选角色就返回空串（不注入任何语气要求）。
     *
     * 每次调用都会先做一次「文件变了吗」的检查，所以主进程改完，`:schedule` 下一次
     * run 立刻生效 —— 这也是它和 SharedPreferences 版本最大的区别。
     */
    fun instruction(): String {
        ensureFresh()
        val style = cachedSelected.takeIf { it.isNotBlank() }
            ?.let { id -> cachedStyles.firstOrNull { it.id == id } } ?: return ""
        val body = style.instruction.trim()
        if (body.isEmpty()) return ""
        // 追加一句「适用范围」：定时任务的通知正文也是模型写的，
        // 如果只说一次语气要求，模型在「处理任务」场景会自作主张恢复正式口吻（真机实测）。
        return PREFIX + body +
            "\n（上面的语气要求适用于**所有**回复，包括定时任务的结果和通知正文；" +
            "但仍然不能改变事实、不能省略风险提示。）"
    }

    /** 文件被别的进程改过就重读。 */
    private fun ensureFresh() {
        val context = appContext ?: return
        val stamp = file(context).lastModified()
        if (stamp != cachedStamp) reload(context)
    }

    private fun reload(context: Context) {
        val target = file(context)
        if (!target.isFile) {
            // 首次运行：写入种子（顺便把旧 SharedPreferences 里的数据迁过来，不丢用户改动）。
            val migratedSelected: String
            val migratedStyles: List<SpeechStyle>
            run {
                val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                migratedSelected = prefs.getString(LEGACY_KEY_SELECTED, "").orEmpty()
                migratedStyles = runCatching {
                    prefs.getString(LEGACY_KEY_STYLES, null)?.let { parseStyles(it) }
                }.getOrNull().orEmpty()
            }
            cachedStyles = (SpeechStyle.SEEDS + migratedStyles).distinctBy { it.id }
            cachedSelected = migratedSelected.takeIf { id -> cachedStyles.any { it.id == id } }.orEmpty()
            persist(context)
            return
        }
        val loaded = runCatching { parse(readRaw(target)) }.getOrNull()
        if (loaded == null) {
            cachedStyles = SpeechStyle.SEEDS
            cachedSelected = ""
        } else {
            cachedStyles = loaded.first
            cachedSelected = loaded.second
        }
        cachedStamp = target.lastModified()
    }

    private fun persist(context: Context) {
        val target = file(context)
        runCatching {
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writeText(
                JSONObject()
                    .put("selected", cachedSelected)
                    .put(
                        "styles",
                        JSONArray().also { array ->
                            cachedStyles.forEach { style ->
                                array.put(
                                    JSONObject()
                                        .put("id", style.id)
                                        .put("name", style.name)
                                        .put("instruction", style.instruction)
                                        .put("seed", style.seed)
                                        .put("avatar", style.avatarPath.orEmpty())
                                        .put("tags", JSONArray(style.tags)),
                                )
                            }
                        },
                    )
                    .toString(),
            )
            // 原子替换：读的一方要么看到旧文件、要么看到新文件。
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
            cachedStamp = target.lastModified()
        }
    }

    private fun readRaw(target: File): String = target.readText()

    private fun parse(raw: String): Pair<List<SpeechStyle>, String> {
        val json = JSONObject(raw)
        val styles = json.optJSONArray("styles")?.let(::parseStylesArray).orEmpty()
            .ifEmpty { SpeechStyle.SEEDS }
        val selected = json.optString("selected").takeIf { id -> styles.any { it.id == id } }.orEmpty()
        return styles to selected
    }

    private fun parseStyles(raw: String): List<SpeechStyle> =
        parseStylesArray(JSONArray(raw))

    private fun parseStylesArray(array: JSONArray): List<SpeechStyle> =
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SpeechStyle(
                id = id,
                name = item.optString("name").ifBlank { id },
                instruction = item.optString("instruction"),
                seed = item.optBoolean("seed", false),
                avatarPath = item.optString("avatar").takeIf { it.isNotBlank() },
                tags = item.optJSONArray("tags")?.let { tags ->
                    (0 until tags.length()).mapNotNull { i ->
                        tags.optString(i).takeIf { it.isNotBlank() }
                    }
                }.orEmpty(),
            )
        }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)
}

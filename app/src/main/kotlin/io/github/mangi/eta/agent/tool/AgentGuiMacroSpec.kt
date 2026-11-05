package io.github.mangi.eta.agent.tool

import java.util.Locale
import org.json.JSONObject

/**
 * GUI 宏的一步。解析完成后执行器只认这个结构，不再回头看 JSON。
 *
 * [raw] 保留原始参数：执行时直接交给已有的单动作工具（tap_element / type_text …），
 * 保证宏里每一步的语义和单独调用时**完全一致**，不另写一套。
 */
internal data class GuiMacroStep(
    /** 在宏里的位置，从 0 开始；只用于报错和计时，节点 index 在 [raw] 里。 */
    val position: Int,
    val action: String,
    val text: String = "",
    val match: String = "contains",
    val includeDesc: Boolean = true,
    val timeoutMs: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val coordinateSpace: String = "screen",
    val durationMs: Int = 1_000,
    val direction: String = "down",
    val amount: String = "page",
    val button: String = "BACK",
    val mode: String = "replace",
    val submit: Boolean = false,
    val expectText: String = "",
    val verifyChange: Boolean = true,
    /** 消歧：命中多个节点时，只认 view_id 完全一致的那个。 */
    val viewId: String = "",
    /** 消歧：命中多个节点时取第 n 个（从 0 开始）。 */
    val nth: Int = 0,
    val raw: JSONObject = JSONObject(),
) {
    /** 会不会动界面：决定要不要记前快照、要不要校验「有变化」。 */
    val interacts: Boolean get() = action in AgentGuiMacroSpec.INTERACTING_ACTIONS

    /** 是不是按文字定位的动作（命中多个时必须消歧，不能瞎挑一个点）。 */
    val needsUnambiguousTarget: Boolean get() = action == AgentGuiMacroSpec.ACTION_TAP_TEXT
}

/** 解析结果：要么是完整计划，要么是一条能直接回给模型的错误。 */
internal sealed interface GuiMacroParse {
    data class Ok(val steps: List<GuiMacroStep>) : GuiMacroParse

    data class Invalid(val code: String, val message: String) : GuiMacroParse
}

/**
 * `gui_macro` 的纯解析与判据。
 *
 * 为什么要有这一层：宏是「一次调用连点好几下」，模型写错一步就是连错几步，
 * 所以参数必须在动手前全部校验完（动作名、必填参数、上限、消歧、快照时效），
 * 并且这部分要能单测。真正碰设备的部分只有 [AgentLocalTools] 里那一小段。
 */
internal object AgentGuiMacroSpec {

    const val ACTION_TAP_TEXT = "tap_text"
    const val ACTION_TAP_ELEMENT = "tap_element"

    /** 一次宏最多几步；再多就该拆成多次调用，否则错了会连错很远。 */
    const val MAX_STEPS = 20

    /** 整个宏的时间上限（毫秒）。 */
    const val MAX_TOTAL_MS = 60_000L

    /** 单步等待的下限/上限（毫秒）。 */
    const val MIN_STEP_TIMEOUT_MS = 500
    const val MAX_STEP_TIMEOUT_MS = 15_000
    const val DEFAULT_TEXT_TIMEOUT_MS = 5_000

    /** 文字类参数上限，和 type_text 自己的限制一致。 */
    const val MAX_TEXT_CHARS = 4_000

    /** `nth` 的合理上限，防止模型写出 nth=999 这种无意义值。 */
    const val MAX_NTH = 20

    val ACTIONS: List<String> = listOf(
        "tap_text",
        "tap_element",
        "tap",
        "long_press",
        "scroll",
        "press_key",
        "type_text",
        "wait_text",
        "wait",
    )

    /** 会改变界面的动作：执行后要等稳定、并校验「确实有变化」。 */
    val INTERACTING_ACTIONS: Set<String> = setOf(
        "tap_text",
        "tap_element",
        "tap",
        "long_press",
        "scroll",
        "press_key",
        "type_text",
    )

    private val MATCH_MODES = setOf("contains", "exact", "prefix", "regex")
    private val COORDINATE_SPACES = setOf("screen", "normalized", "screenshot")
    private val DIRECTIONS = setOf("up", "down", "left", "right")
    private val AMOUNTS = setOf("page", "small")
    private val BUTTONS = setOf(
        "BACK", "HOME", "ENTER", "RECENTS", "PASTE", "NOTIFICATIONS", "QUICK_SETTINGS",
    )
    private val TYPE_MODES = setOf("replace", "append")

    fun parse(args: JSONObject): GuiMacroParse {
        val raw = args.optJSONArray("steps")
            ?: return invalid(
                "INVALID_ARGUMENT",
                "steps 必须是非空数组，每项形如 {\"action\":\"tap_text\",\"text\":\"设置\"}",
            )
        if (raw.length() == 0) return invalid("INVALID_ARGUMENT", "steps 不能为空")
        if (raw.length() > MAX_STEPS) {
            return invalid("INVALID_ARGUMENT", "steps 最多 $MAX_STEPS 步，当前 ${raw.length()} 步")
        }
        val steps = ArrayList<GuiMacroStep>(raw.length())
        var seenInteracting = false
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index)
                ?: return invalid("INVALID_ARGUMENT", "第 ${index + 1} 步不是对象")
            val parsed = runCatching { parseStep(index, item, seenInteracting) }.getOrElse { failure ->
                return invalid(
                    "INVALID_ARGUMENT",
                    "第 ${index + 1} 步参数无效：${failure.message?.take(120).orEmpty()}",
                )
            }
            when (parsed) {
                is StepResult.Ok -> {
                    steps += parsed.step
                    if (parsed.step.interacts) seenInteracting = true
                }
                is StepResult.Invalid -> return invalid(parsed.code, "第 ${index + 1} 步：${parsed.message}")
            }
        }
        return GuiMacroParse.Ok(steps)
    }

    /**
     * 文本判据。**宏内所有文字匹配都走这里**（tap_text / wait_text / expect_text），
     * 统一不区分大小写，避免同一份参数在不同步骤得出相反结论。
     *
     * 注意：它和独立的 `wait_for_text` 工具（区分大小写）语义不同，这是刻意的 ——
     * 宏内要自洽，跨工具时以各自 schema 的描述为准。
     */
    fun matchesText(value: String, query: String, match: String): Boolean {
        if (value.isEmpty() || query.isEmpty()) return false
        return when (match) {
            "exact" -> value.equals(query, ignoreCase = true)
            "prefix" -> value.startsWith(query, ignoreCase = true)
            "regex" -> runCatching {
                Regex(query, RegexOption.IGNORE_CASE).containsMatchIn(value)
            }.getOrDefault(false)
            else -> value.contains(query, ignoreCase = true)
        }
    }

    private sealed interface StepResult {
        data class Ok(val step: GuiMacroStep) : StepResult

        data class Invalid(val code: String, val message: String) : StepResult
    }

    private fun parseStep(index: Int, item: JSONObject, seenInteracting: Boolean): StepResult {
        val action = item.optString("action").trim().lowercase(Locale.ROOT)
        if (action !in ACTIONS) {
            return StepResult.Invalid("INVALID_ARGUMENT", "action 只能是 ${ACTIONS.joinToString("/")}")
        }
        val expectText = item.optString("expect_text").trim()
        val verifyChange = item.optBoolean("verify_change", true)
        val match = item.optString("match", "contains").trim().lowercase(Locale.ROOT)
        if (match !in MATCH_MODES) {
            return StepResult.Invalid("INVALID_ARGUMENT", "match 只能是 ${MATCH_MODES.joinToString("/")}")
        }
        val viewId = item.optString("view_id").trim()
        val nth = item.optInt("nth", 0)
        if (nth < 0 || nth > MAX_NTH) {
            return StepResult.Invalid("INVALID_ARGUMENT", "nth 必须是 0 到 $MAX_NTH 之间的整数")
        }
        val common = StepBuilder(index, action, match, expectText, verifyChange, viewId, nth, item)

        return when (action) {
            "tap_text", "wait_text" -> {
                val text = item.optString("text").trim()
                if (text.isEmpty()) return StepResult.Invalid("INVALID_ARGUMENT", "text 不能为空")
                if (text.length > MAX_TEXT_CHARS) {
                    return StepResult.Invalid("INVALID_ARGUMENT", "text 超过 $MAX_TEXT_CHARS 字符")
                }
                val defaultTimeout = if (action == "wait_text") DEFAULT_TEXT_TIMEOUT_MS else 0
                StepResult.Ok(
                    common.build(
                        text = text,
                        includeDesc = item.optBoolean("include_desc", true),
                        timeoutMs = timeoutMs(item, defaultTimeout),
                    ),
                )
            }

            ACTION_TAP_ELEMENT -> {
                val nodeIndex = item.optInt("index", -1)
                if (nodeIndex < 0) {
                    return StepResult.Invalid(
                        "INVALID_ARGUMENT",
                        "tap_element 需要 index（取自最近一次 observe_screen）",
                    )
                }
                // 没有 observation_id 时执行期必被 ObservationReferencePolicy 拒（ID_REQUIRED），
                // 与其点到第 N 步才失败，不如解析期就拦下。
                if (item.optString("observation_id").trim().isEmpty()) {
                    return StepResult.Invalid(
                        "INVALID_ARGUMENT",
                        "tap_element 需要 observation_id（与 index 同源）；或者改用 tap_text 按文字点",
                    )
                }
                // index 只对「宏开始时的界面」有效。前面任何一步动过界面，index 就指向别的界面了，
                // 而指纹重定位遇到 android:id/title 这种通用 id 会在新界面上找到「同一个」并点下去。
                if (seenInteracting) {
                    return StepResult.Invalid(
                        "INVALID_ARGUMENT",
                        "tap_element 只能放在宏的第一步（前面的步骤会换界面，index 会失效）；" +
                            "后续步骤请用 tap_text 按文字点",
                    )
                }
                StepResult.Ok(common.build())
            }

            "tap", "long_press" -> {
                if (!item.has("x") || !item.has("y")) {
                    return StepResult.Invalid("INVALID_ARGUMENT", "$action 需要 x、y 与 coordinate_space")
                }
                val space = item.optString("coordinate_space").trim().lowercase(Locale.ROOT)
                if (space !in COORDINATE_SPACES) {
                    return StepResult.Invalid(
                        "INVALID_ARGUMENT",
                        "coordinate_space 只能是 ${COORDINATE_SPACES.joinToString("/")}",
                    )
                }
                StepResult.Ok(
                    common.build(
                        x = item.optInt("x"),
                        y = item.optInt("y"),
                        coordinateSpace = space,
                        durationMs = item.optInt("duration_ms", 800).coerceIn(300, 3_000),
                    ),
                )
            }

            "scroll" -> {
                val direction = item.optString("direction").trim().lowercase(Locale.ROOT)
                if (direction !in DIRECTIONS) {
                    return StepResult.Invalid(
                        "INVALID_ARGUMENT",
                        "scroll 的 direction 只能是 ${DIRECTIONS.joinToString("/")}",
                    )
                }
                val amount = item.optString("amount").trim().lowercase(Locale.ROOT).ifBlank { "page" }
                if (amount !in AMOUNTS) {
                    return StepResult.Invalid(
                        "INVALID_ARGUMENT",
                        "scroll 的 amount 只能是 ${AMOUNTS.joinToString("/")}",
                    )
                }
                StepResult.Ok(common.build(direction = direction, amount = amount))
            }

            "press_key" -> {
                val button = item.optString("button").trim().uppercase(Locale.ROOT)
                if (button !in BUTTONS) {
                    return StepResult.Invalid("INVALID_ARGUMENT", "button 只能是 ${BUTTONS.joinToString("/")}")
                }
                StepResult.Ok(common.build(button = button))
            }

            "type_text" -> {
                val text = item.optString("text")
                if (text.length > MAX_TEXT_CHARS) {
                    return StepResult.Invalid("INVALID_ARGUMENT", "text 超过 $MAX_TEXT_CHARS 字符")
                }
                val mode = item.optString("mode", "replace").trim().lowercase(Locale.ROOT).ifBlank { "replace" }
                if (mode !in TYPE_MODES) {
                    return StepResult.Invalid("INVALID_ARGUMENT", "mode 只能是 ${TYPE_MODES.joinToString("/")}")
                }
                if (mode == "append" && item.has("index")) {
                    return StepResult.Invalid("INVALID_ARGUMENT", "append 只作用于当前输入焦点，不能带 index")
                }
                if (mode == "replace" && item.has("index") && item.optInt("index", -1) < 0) {
                    return StepResult.Invalid("INVALID_ARGUMENT", "index 不能为负")
                }
                StepResult.Ok(
                    common.build(text = text, mode = mode, submit = item.optBoolean("submit", false)),
                )
            }

            else -> StepResult.Ok(
                common.build(durationMs = item.optInt("duration_ms", 1_000).coerceIn(100, 30_000)),
            )
        }
    }

    private fun timeoutMs(item: JSONObject, default: Int): Int {
        if (!item.has("timeout_ms")) return default
        val value = item.optInt("timeout_ms", default)
        if (value <= 0) return default
        return value.coerceIn(MIN_STEP_TIMEOUT_MS, MAX_STEP_TIMEOUT_MS)
    }

    private fun invalid(code: String, message: String): GuiMacroParse =
        GuiMacroParse.Invalid(code, message)

    /** 逐字段构造，避免每个分支重复写一遍公共参数。 */
    private class StepBuilder(
        private val position: Int,
        private val action: String,
        private val match: String,
        private val expectText: String,
        private val verifyChange: Boolean,
        private val viewId: String,
        private val nth: Int,
        private val raw: JSONObject,
    ) {
        fun build(
            text: String = "",
            includeDesc: Boolean = true,
            timeoutMs: Int = 0,
            x: Int = 0,
            y: Int = 0,
            coordinateSpace: String = "screen",
            durationMs: Int = 1_000,
            direction: String = "down",
            amount: String = "page",
            button: String = "BACK",
            mode: String = "replace",
            submit: Boolean = false,
        ): GuiMacroStep = GuiMacroStep(
            position = position,
            action = action,
            text = text,
            match = match,
            includeDesc = includeDesc,
            timeoutMs = timeoutMs,
            x = x,
            y = y,
            coordinateSpace = coordinateSpace,
            durationMs = durationMs,
            direction = direction,
            amount = amount,
            button = button,
            mode = mode,
            submit = submit,
            expectText = expectText,
            verifyChange = verifyChange,
            viewId = viewId,
            nth = nth,
            raw = raw,
        )
    }
}

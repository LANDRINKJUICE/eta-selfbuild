package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gui_macro 的解析与判据。**刻意不依赖 Robolectric**：这台机器跑不了 Robolectric，
 * 纯逻辑留在普通 JVM 单测里才真能跑绿。
 */
class AgentGuiMacroSpecTest {

    private fun parse(steps: JSONArray) = AgentGuiMacroSpec.parse(JSONObject().put("steps", steps))

    private fun stepsOf(steps: JSONArray): List<GuiMacroStep> {
        val parsed = parse(steps)
        assertTrue("期望解析成功，实际 $parsed", parsed is GuiMacroParse.Ok)
        return (parsed as GuiMacroParse.Ok).steps
    }

    private fun invalidMessage(steps: JSONArray): String {
        val parsed = parse(steps)
        assertTrue("期望解析失败，实际 $parsed", parsed is GuiMacroParse.Invalid)
        return (parsed as GuiMacroParse.Invalid).message
    }

    @Test
    fun emptyStepsIsRejected() {
        assertTrue(parse(JSONArray()) is GuiMacroParse.Invalid)
    }

    @Test
    fun moreThanMaxStepsIsRejectedBeforeTouchingTheDevice() {
        val steps = JSONArray()
        repeat(AgentGuiMacroSpec.MAX_STEPS + 1) {
            steps.put(JSONObject().put("action", "wait").put("duration_ms", 100))
        }
        val message = invalidMessage(steps)
        assertTrue(message.contains("${AgentGuiMacroSpec.MAX_STEPS}"))
    }

    @Test
    fun exactlyMaxStepsIsAccepted() {
        val steps = JSONArray()
        repeat(AgentGuiMacroSpec.MAX_STEPS) {
            steps.put(JSONObject().put("action", "wait").put("duration_ms", 100))
        }
        assertEquals(AgentGuiMacroSpec.MAX_STEPS, stepsOf(steps).size)
    }

    @Test
    fun unknownActionListsAllowedOnes() {
        val message = invalidMessage(JSONArray().put(JSONObject().put("action", "tap_screen")))
        assertTrue(message.contains("tap_text"))
        assertTrue(message.contains("第 1 步"))
    }

    @Test
    fun stepWithoutActionIsRejected() {
        val message = invalidMessage(JSONArray().put(JSONObject().put("text", "设置")))
        assertTrue(message.contains("action"))
    }

    @Test
    fun tapTextNeedsTextAndDefaultsToNoWaiting() {
        val message = invalidMessage(JSONArray().put(JSONObject().put("action", "tap_text")))
        assertTrue(message.contains("text"))

        val step = stepsOf(JSONArray().put(JSONObject().put("action", "tap_text").put("text", "显示与亮度"))).single()
        assertEquals("contains", step.match)
        assertTrue(step.includeDesc)
        assertEquals(0, step.timeoutMs)
        assertTrue(step.interacts)
    }

    @Test
    fun tapTextTimeoutIsClampedIntoASaneWindow() {
        val step = stepsOf(
            JSONArray().put(
                JSONObject().put("action", "tap_text").put("text", "完成").put("timeout_ms", 50),
            ),
        ).single()
        assertEquals(AgentGuiMacroSpec.MIN_STEP_TIMEOUT_MS, step.timeoutMs)
    }

    @Test
    fun waitTextDefaultsToFiveSecondTimeout() {
        val step = stepsOf(JSONArray().put(JSONObject().put("action", "wait_text").put("text", "完成"))).single()
        assertEquals(AgentGuiMacroSpec.DEFAULT_TEXT_TIMEOUT_MS, step.timeoutMs)
        assertFalse("等文字不该被当成会改变界面的动作", step.interacts)
    }

    @Test
    fun tapElementNeedsIndexAndObservationIdAndMustBeTheFirstInteractingStep() {
        // 缺 index
        assertTrue(invalidMessage(JSONArray().put(JSONObject().put("action", "tap_element"))).contains("index"))
        // index 为负
        assertTrue(
            invalidMessage(
                JSONArray().put(JSONObject().put("action", "tap_element").put("index", -3).put("observation_id", "o1")),
            ).contains("index"),
        )
        // 缺 observation_id：执行期必被 ObservationReferencePolicy 拒，解析期就拦下
        assertTrue(
            invalidMessage(JSONArray().put(JSONObject().put("action", "tap_element").put("index", 7)))
                .contains("observation_id"),
        )
        // 合法：带 observation_id 且在第一步
        val step = stepsOf(
            JSONArray().put(
                JSONObject().put("action", "tap_element").put("index", 7).put("observation_id", "o7"),
            ),
        ).single()
        assertEquals("节点 index 原样留给执行器，不跟步序混用", 7, step.raw.optInt("index"))
        assertEquals(0, step.position)

        // 前面动过界面后 index 就属于旧界面了，必须在解析期拒绝（否则会在新界面上点到同指纹的别的节点）
        val afterInteraction = invalidMessage(
            JSONArray()
                .put(JSONObject().put("action", "tap_text").put("text", "设置"))
                .put(JSONObject().put("action", "tap_element").put("index", 3).put("observation_id", "o7")),
        )
        assertTrue(afterInteraction.contains("tap_element"))
        assertTrue(afterInteraction.contains("第 2 步"))

        // 纯等待不换界面，后面仍可以用 index
        val afterWait = stepsOf(
            JSONArray()
                .put(JSONObject().put("action", "wait").put("duration_ms", 200))
                .put(JSONObject().put("action", "tap_element").put("index", 3).put("observation_id", "o7")),
        )
        assertEquals(2, afterWait.size)
    }

    @Test
    fun tapTextCarriesDisambiguationFields() {
        val step = stepsOf(
            JSONArray().put(
                JSONObject()
                    .put("action", "tap_text")
                    .put("text", "删除")
                    .put("view_id", "title")
                    .put("nth", 2),
            ),
        ).single()
        assertEquals("title", step.viewId)
        assertEquals(2, step.nth)
        assertTrue(step.needsUnambiguousTarget)
    }

    @Test
    fun nthOutOfRangeIsRejected() {
        assertTrue(
            invalidMessage(
                JSONArray().put(JSONObject().put("action", "tap_text").put("text", "删除").put("nth", -1)),
            ).contains("nth"),
        )
        assertTrue(
            invalidMessage(
                JSONArray().put(JSONObject().put("action", "tap_text").put("text", "删除").put("nth", 999)),
            ).contains("nth"),
        )
    }

    @Test
    fun coordinateTapsRequireExplicitCoordinateSpace() {
        val missing = invalidMessage(JSONArray().put(JSONObject().put("action", "tap").put("x", 10).put("y", 20)))
        assertTrue(missing.contains("coordinate_space"))

        val badSpace = invalidMessage(
            JSONArray().put(JSONObject().put("action", "tap").put("x", 10).put("y", 20).put("coordinate_space", "pixel")),
        )
        assertTrue(badSpace.contains("coordinate_space"))

        val step = stepsOf(
            JSONArray().put(
                JSONObject().put("action", "tap").put("x", 10).put("y", 20).put("coordinate_space", "screen"),
            ),
        ).single()
        assertEquals(10, step.x)
        assertEquals("screen", step.coordinateSpace)
    }

    @Test
    fun scrollAndPressKeyValidateEnumValues() {
        assertTrue(
            invalidMessage(JSONArray().put(JSONObject().put("action", "scroll").put("direction", "forward")))
                .contains("direction"),
        )
        val scroll = stepsOf(
            JSONArray().put(JSONObject().put("action", "scroll").put("direction", "down")),
        ).single()
        assertEquals("down", scroll.direction)
        assertEquals("page", scroll.amount)

        val key = stepsOf(
            JSONArray().put(JSONObject().put("action", "press_key").put("button", "back")),
        ).single()
        assertEquals("BACK", key.button)
        assertTrue(
            invalidMessage(JSONArray().put(JSONObject().put("action", "press_key").put("button", "POWER")))
                .contains("button"),
        )
    }

    @Test
    fun typeTextRejectsAppendWithIndexBecauseItOnlyTargetsTheCurrentFocus() {
        val message = invalidMessage(
            JSONArray().put(
                JSONObject().put("action", "type_text").put("text", "abc").put("mode", "append").put("index", 1),
            ),
        )
        assertTrue(message.contains("append"))

        val step = stepsOf(
            JSONArray().put(
                JSONObject().put("action", "type_text").put("text", "abc").put("mode", "replace").put("index", 1),
            ),
        ).single()
        assertEquals("replace", step.mode)
    }

    @Test
    fun expectTextAndVerifyChangeAreCarriedPerStep() {
        val step = stepsOf(
            JSONArray().put(
                JSONObject()
                    .put("action", "tap_text")
                    .put("text", "下一步")
                    .put("expect_text", "已保存")
                    .put("verify_change", false),
            ),
        ).single()
        assertEquals("已保存", step.expectText)
        assertFalse(step.verifyChange)
    }

    @Test
    fun matchesTextModes() {
        assertTrue(AgentGuiMacroSpec.matchesText("显示与亮度", "亮度", "contains"))
        assertTrue(AgentGuiMacroSpec.matchesText("Settings", "settings", "exact"))
        assertFalse(AgentGuiMacroSpec.matchesText("设置", "settings", "exact"))
        assertTrue(AgentGuiMacroSpec.matchesText("WLAN 与网络", "WLAN", "prefix"))
        assertFalse(AgentGuiMacroSpec.matchesText("移动网络", "WLAN", "prefix"))
        assertTrue(AgentGuiMacroSpec.matchesText("第 12 项", "\\d+", "regex"))
        assertFalse(AgentGuiMacroSpec.matchesText("第 十二 项", "\\d+", "regex"))
        assertFalse("空查询不该命中任何节点", AgentGuiMacroSpec.matchesText("设置", "", "contains"))
        assertFalse("空文本不该命中", AgentGuiMacroSpec.matchesText("", "设置", "contains"))
    }

    @Test
    fun interactingActionsCoverEverythingThatTouchesTheScreen() {
        assertTrue("wait" !in AgentGuiMacroSpec.INTERACTING_ACTIONS)
        assertTrue("wait_text" !in AgentGuiMacroSpec.INTERACTING_ACTIONS)
        listOf("tap_text", "tap_element", "tap", "long_press", "scroll", "press_key", "type_text").forEach { action ->
            assertTrue("$action 必须算会动界面的动作", action in AgentGuiMacroSpec.INTERACTING_ACTIONS)
            assertTrue("$action 必须能被解析", action in AgentGuiMacroSpec.ACTIONS)
        }
    }
}

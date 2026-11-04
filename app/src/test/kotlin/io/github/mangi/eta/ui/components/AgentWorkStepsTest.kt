package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkStepsTest {

    @Test
    fun consecutiveSameToolCallsFormOneGroup() {
        val steps = listOf(
            tool("t1", "read_file"),
            tool("t2", "read_file"),
            tool("t3", "read_file"),
            tool("t4", "run_command"),
        ).toWorkSteps()

        assertEquals(2, steps.size)
        val group = steps[0] as AgentWorkStep.ToolGroup
        assertEquals(listOf("t1", "t2", "t3"), group.calls.map { it.id })
        assertTrue(steps[1] is AgentWorkStep.Single)
    }

    @Test
    fun thinkingBreaksToolGroup() {
        val steps = listOf(
            tool("t1", "read_file"),
            ThinkingMessageUi(id = "k1", content = "想", isStreaming = false),
            tool("t2", "read_file"),
        ).toWorkSteps()

        assertEquals(listOf("t1", "k1", "t2"), steps.map { it.key })
    }

    @Test
    fun browserCallsStayIndividual() {
        val steps = listOf(tool("b1", "browser_use"), tool("b2", "browser_use")).toWorkSteps()

        assertEquals(2, steps.size)
        assertTrue(steps.all { it is AgentWorkStep.Single })
    }

    @Test
    fun groupKeyStaysStableWhileCallsAppend() {
        val first = listOf(tool("t1", "read_file"), tool("t2", "read_file")).toWorkSteps()
        val next = listOf(tool("t1", "read_file"), tool("t2", "read_file"), tool("t3", "read_file")).toWorkSteps()

        assertEquals(first.single().key, next.single().key)
    }

    @Test
    fun terminalOutcomeUsesLastOutputLine() {
        val message = tool("t1", "run_command", resultSummary = "执行完成\nline 1\nline 2\n…")

        // 带「执行完成 ·」前缀：只显示一行输出时，看不出这是命令结果而不是 App 的说明
        // （2026-11-04 用户把测试命令 echo 的内容当成界面文案）。
        assertEquals("执行完成 · line 2", message.outcomeLine())
    }

    @Test
    fun trivialSuccessHasNoOutcome() {
        assertNull(tool("t1", "observe_screen", resultSummary = "完成").outcomeLine())
        assertEquals("2 张图片", tool("t2", "observe_screen", resultSummary = "完成 · 2 张图片").outcomeLine())
    }

    @Test
    fun failureOutcomeStripsPrefixAndCode() {
        val message = tool(
            "t1",
            "tap",
            status = ToolActivityStatusUi.Failed,
            resultSummary = "失败 · 找不到元素 · code=E_NOT_FOUND",
        )

        assertEquals("找不到元素", message.outcomeLine())
    }

    @Test
    fun failedTerminalFallsBackToOutput() {
        val message = tool(
            "t1",
            "run_command",
            status = ToolActivityStatusUi.Failed,
            resultSummary = "失败 · 退出码 1\nno such file",
        )

        assertEquals("退出码 1", message.outcomeLine())
    }

    @Test
    fun runningToolHasNoOutcome() {
        assertNull(tool("t1", "read_file", status = ToolActivityStatusUi.Running, resultSummary = "x").outcomeLine())
    }

    private fun tool(
        id: String,
        name: String,
        status: ToolActivityStatusUi = ToolActivityStatusUi.Success,
        resultSummary: String? = null,
    ) = ToolActivityMessageUi(
        id = id,
        toolName = name,
        status = status,
        argumentsSummary = name,
        resultSummary = resultSummary,
    )
}

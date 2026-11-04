package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知折叠态文案的回归测试。
 *
 * 用户反馈：通知正文经常是一整句模型输出，太长、重点不明。
 * 现在折叠态压成一句直白的话，完整内容放展开态。
 */
class BackgroundCommandSummaryTest {

    @Test
    fun `模型 503 压成一句`() {
        val text = "模型接口返回 HTTP 503 已重试 3 次仍未恢复，已保留此前完成的工具结果。"

        assertEquals("模型接口暂时不可用", BackgroundCommandSummary.shortReason(text))
    }

    @Test
    fun `限流与鉴权各自成句`() {
        assertEquals(
            "模型接口被限流",
            BackgroundCommandSummary.shortReason("模型接口暂时限流（HTTP 429）。 已重试 3 次仍未恢复"),
        )
        assertEquals(
            "模型接口鉴权失败",
            BackgroundCommandSummary.shortReason("Token 无效或已过期"),
        )
    }

    @Test
    fun `超时与前台服务失败各自成句`() {
        assertEquals("执行超时", BackgroundCommandSummary.shortReason("执行超时（300 秒未返回结果）"))
        assertEquals(
            "无法启动前台服务",
            BackgroundCommandSummary.shortReason("执行失败（无法启动前台服务，可能被系统限制）"),
        )
    }

    @Test
    fun `认不出就截断且不超过一行`() {
        val long = "构建失败：app/src/main/kotlin/io/github/mangi/eta/ui/SettingsScreen.kt:93 Unresolved reference"

        val short = BackgroundCommandSummary.shortReason(long)

        assertTrue(short.length <= BackgroundCommandSummary.SHORT_CHARS + 1)
        assertTrue(short.endsWith("…"))
    }

    @Test
    fun `空输入返回空串`() {
        assertEquals("", BackgroundCommandSummary.shortReason("   "))
    }
}

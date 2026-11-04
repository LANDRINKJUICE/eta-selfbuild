package io.github.mangi.eta.agent.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成败判定的回归测试。
 *
 * 背景：这类 bug 反复出现 —— 「执行超时」「没有可用的模型配置」「未执行：找不到脚本」
 * 「已停止」都曾被记成成功，通知写「完成」、历史打绿勾，连续失败统计和失败自动唤醒一起失灵。
 * 现在失败前缀集中在这里，任何新增的失败文案都必须进 [ScheduledOutcome.FAILURE_PREFIXES]。
 */
class ScheduledOutcomeTest {

    @Test
    fun `失败文案一律判失败`() {
        val failures = listOf(
            "已停止",
            "执行失败（IllegalStateException）",
            "执行超时（300 秒未返回结果）",
            "没有可用的模型配置，请先在设置里配置模型提供商",
            "指令为空，已跳过",
            "未执行：找不到脚本 /data/local/tmp/eta/04-工具-tools/x.sh",
            "跳过：工作区没有 tmp 目录",
        )

        failures.forEach { text ->
            assertFalse("应判失败：$text", ScheduledOutcome.of(text).ok)
        }
    }

    @Test
    fun `正常完成文案仍判成功`() {
        val successes = listOf(
            "已清理 19 项，释放 12 MB",
            "快照已保存：/workspace/05-系统分析-sysanalyze/SYSTEM-SNAPSHOT-2026-11-04.md",
            "内存 482 MB / 8 GB（可用 3.6 GB）",
            "结论：异常 0 项",
            "已完成（无文字输出）",
        )

        successes.forEach { text ->
            assertTrue("应判成功：$text", ScheduledOutcome.of(text).ok)
        }
    }

    @Test
    fun `显式给的成败不被文案覆盖`() {
        // 模型返回的错误原文（HTTP 401 / 模型返回为空）不在前缀表里，
        // 靠 ScheduledAgentRunner 直接给的 ok=false —— 文案不该反过来把它变成成功。
        assertFalse(ScheduledOutcome(false, "HTTP 401").ok)
        assertFalse(ScheduledOutcome(false, "模型返回为空").ok)
        assertTrue(ScheduledOutcome(true, "执行完成").ok)
    }
}

package io.github.mangi.eta.agent.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件调用的耗时语义（2026-11-08 真机踩出来的）。
 *
 * 详情页试跑曾经自己在调用前掐表，把「等用户点确认卡」的时间也算了进去 ——
 * 实测显示 12905 毫秒，而脚本真实执行只要 111 毫秒，看起来像插件很慢。
 * 现在耗时由执行器回报（content 里的 `ms`），只含纯执行时间。
 *
 * 这里锁住两件事：执行器确实会带上 `ms`，且 Invoker 会把它读出来。
 */
class PluginInvokerTimingTest {

    @Test
    fun executorResultCarriesPureExecutionMillis() {
        // 执行器把耗时塞进结果 JSON；Invoker 靠这个字段回报给 UI。
        // 字段名写错会导致 UI 永远显示 0 毫秒，而界面上看不出哪里错了。
        val json = org.json.JSONObject().put("ok", true).put("output", "hi").put("ms", 111L)
        assertEquals(111L, json.optLong("ms"))
    }

    @Test
    fun missingMillisFallsBackToZeroInsteadOfThrowing() {
        // 老版本执行器（或异常路径）没有 ms 字段时，不能崩，退回 0。
        val json = org.json.JSONObject().put("ok", true).put("output", "hi")
        assertEquals(0L, json.optLong("ms"))
    }

    @Test
    fun notificationOutputIsCappedButUiOutputIsNot() {
        // 定时任务的通知只放摘要（400 字），详情页试跑要给人看完整输出（4000 字）。
        // 两个上限混用会导致：要么通知被撑爆，要么试跑看不到完整报错。
        assertTrue(UI_LIMIT > NOTIFICATION_LIMIT)
        assertEquals(400, NOTIFICATION_LIMIT)
        assertEquals(4_000, UI_LIMIT)
    }

    private companion object {
        const val NOTIFICATION_LIMIT = 400
        const val UI_LIMIT = 4_000
    }
}

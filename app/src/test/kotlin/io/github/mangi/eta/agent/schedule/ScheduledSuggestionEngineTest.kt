package io.github.mangi.eta.agent.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 建议引擎的纯 JVM 测试：不依赖 Android，所以本机 `gradlew test` 就能跑。
 *
 * 重点不是「算法对不对」，而是**它什么时候该闭嘴**：
 * 只有 2 天数据不许建议、已有同样任务不许重复建议、自己的通知不算证据。
 */
class ScheduledSuggestionEngineTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
        }.timeInMillis

    private fun sample(pkg: String, title: String, day: Int, hour: Int, minute: Int, text: String = "") =
        NotificationSample(pkg, title, at(day, hour, minute), text)

    private fun now() = at(7, 12, 0)

    @Test
    fun `关键词集中在固定时段会给出每天定时建议`() {
        val samples = (1..4).map { sample("com.eg.android.AlipayGphone", "信用卡账单已出", it, 9, 28) }

        val result = ScheduledSuggestionEngine.suggest(samples, nowMillis = now(), zone = zone)

        val time = result.single { it.trigger == ScheduledTrigger.DAILY_TIME }
        assertEquals(9, time.hour)
        assertEquals(20, time.minute) // 09:28 提前 5 分钟并向下对齐到 5 分钟
        assertEquals(4, time.evidenceDays)
        assertEquals(listOf("账单"), time.keywords)
    }

    @Test
    fun `关键词时间分散时只给通知匹配不给定时`() {
        val samples = listOf(
            sample("com.eg.android.AlipayGphone", "信用卡账单已出", 1, 9, 0),
            sample("com.eg.android.AlipayGphone", "信用卡账单已出", 3, 15, 0),
            sample("com.eg.android.AlipayGphone", "信用卡账单已出", 5, 21, 0),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, nowMillis = now(), zone = zone)

        assertTrue(result.none { it.trigger == ScheduledTrigger.DAILY_TIME })
        assertEquals("账单", result.single { it.trigger == ScheduledTrigger.NOTIFICATION_MATCH }.keyword)
    }

    @Test
    fun `只有两天数据不构成习惯`() {
        val samples = (1..2).map { sample("com.eg.android.AlipayGphone", "信用卡账单已出", it, 9, 28) }

        val result = ScheduledSuggestionEngine.suggest(samples, nowMillis = now(), zone = zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `已经存在的同关键词任务不再建议`() {
        val samples = listOf(
            sample("com.eg.android.AlipayGphone", "信用卡账单已出", 1, 9, 0),
            sample("com.eg.android.AlipayGphone", "信用卡账单已出", 3, 15, 0),
            sample("com.eg.android.AlipayGphone", "信用卡账单已出", 5, 21, 0),
        )
        val existing = listOf(
            ScheduledTask(
                id = "eta-1",
                trigger = ScheduledTrigger.NOTIFICATION_MATCH,
                action = ScheduledActionKind.CUSTOM_PROMPT,
                enabled = true,
                keyword = "账单",
                prompt = "旧任务",
            ),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, existing, nowMillis = now(), zone = zone)

        assertTrue(result.none { it.keyword.equals("账单", ignoreCase = true) })
    }

    @Test
    fun `自己的通知不算证据`() {
        val samples = (1..5).map { sample("io.github.mangi.eta", "账单提醒", it, 9, 28) }

        val result = ScheduledSuggestionEngine.suggest(samples, nowMillis = now(), zone = zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `超过七天的旧通知不参与统计`() {
        val samples = (1..5).map { sample("com.eg.android.AlipayGphone", "账单提醒", it, 9, 28) }

        val result = ScheduledSuggestionEngine.suggest(
            samples,
            nowMillis = at(20, 12, 0),
            zone = zone,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `互为子串的关键词只留最具体的`() {
        val samples = listOf(
            sample("com.eg.android.AlipayGphone", "还款日提醒", 1, 9, 0),
            sample("com.eg.android.AlipayGphone", "还款日提醒", 3, 15, 0),
            sample("com.eg.android.AlipayGphone", "还款日提醒", 5, 21, 0),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, nowMillis = now(), zone = zone)

        val keywords = result.filter { it.trigger == ScheduledTrigger.NOTIFICATION_MATCH }.map { it.keyword }
        assertEquals(listOf("还款日"), keywords)
    }

    @Test
    fun `证据天数更少的定时建议不会被三条通知匹配挤掉`() {
        val samples = listOf(
            // 三个族：命中 5 天但时间分散 → 只会产生通知匹配
            sample("com.eg.android.AlipayGphone", "账单提醒", 1, 8, 0),
            sample("com.eg.android.AlipayGphone", "账单提醒", 2, 12, 0),
            sample("com.eg.android.AlipayGphone", "账单提醒", 3, 16, 0),
            sample("com.eg.android.AlipayGphone", "账单提醒", 4, 20, 0),
            sample("com.eg.android.AlipayGphone", "账单提醒", 5, 22, 0),
            sample("com.taobao.taobao", "快递派送", 1, 7, 0),
            sample("com.taobao.taobao", "快递派送", 2, 11, 0),
            sample("com.taobao.taobao", "快递派送", 3, 15, 0),
            sample("com.taobao.taobao", "快递派送", 4, 19, 0),
            sample("com.taobao.taobao", "快递派送", 5, 21, 0),
            sample("com.tencent.mobileqq", "打卡提醒", 1, 6, 0),
            sample("com.tencent.mobileqq", "打卡提醒", 2, 10, 0),
            sample("com.tencent.mobileqq", "打卡提醒", 3, 14, 0),
            sample("com.tencent.mobileqq", "打卡提醒", 4, 18, 0),
            sample("com.tencent.mobileqq", "打卡提醒", 5, 23, 0),
            // 一个族：只有 3 天，但集中在 09:00 → 会产生每天定时建议
            sample("com.tencent.wework", "会议提醒", 1, 9, 0),
            sample("com.tencent.wework", "会议提醒", 2, 9, 1),
            sample("com.tencent.wework", "会议提醒", 3, 9, 2),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, nowMillis = now(), zone = zone)

        assertEquals(ScheduledSuggestionEngine.MAX_SUGGESTIONS, result.size)
        assertTrue(result.any { it.trigger == ScheduledTrigger.DAILY_TIME })
    }

    @Test
    fun `最多只给三条建议`() {
        val samples = (1..5).flatMap { day ->
            listOf(
                sample("com.eg.android.AlipayGphone", "账单提醒", day, 8, 5),
                sample("com.taobao.taobao", "快递派送", day, 12, 5),
                sample("com.android.mms", "会议通知", day, 18, 5),
                sample("com.tencent.mobileqq", "打卡提醒", day, 21, 5),
            )
        }

        val result = ScheduledSuggestionEngine.suggest(samples, nowMillis = now(), zone = zone)

        assertEquals(ScheduledSuggestionEngine.MAX_SUGGESTIONS, result.size)
    }

    // 以下 4 个用例对应 2026-11-04 真机实测发现的两个缺陷，防止再退化。

    @Test
    fun `正文里的关键词也算证据`() {
        // 真机场景：快递通知标题是「芝麻粒限时奖励提醒」，正文才是「快递已送达」。
        val samples = (1..4).map {
            sample("com.eg.android.AlipayGphone", "芝麻粒限时奖励提醒", it, 12, 51, text = "你有10芝麻粒快递已送达")
        }

        val result = ScheduledSuggestionEngine.suggest(samples, nowMillis = now(), zone = zone)

        assertTrue(result.any { it.keywords.contains("快递") })
    }

    @Test
    fun `时段相近但不同事不算重复`() {
        // 真机场景：09:00 的权限检查把 09:20 的账单建议按 ±30 分钟判成重复，建议全被吞。
        val samples = (1..4).map { sample("com.sankuai.meituan", "【美团月付】账单已出", it, 9, 28) }
        val existing = listOf(
            ScheduledTask(
                id = "eta-perm-mon0900",
                trigger = ScheduledTrigger.DAILY_TIME,
                action = ScheduledActionKind.PERMISSIONS_CHECK,
                enabled = true,
                hour = 9,
                minute = 0,
            ),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, existing, nowMillis = now(), zone = zone)

        val time = result.single { it.trigger == ScheduledTrigger.DAILY_TIME }
        assertEquals(9, time.hour)
        assertEquals(20, time.minute)
    }

    @Test
    fun `同一时刻的自定义指令仍然算重复`() {
        val samples = (1..4).map { sample("com.sankuai.meituan", "【美团月付】账单已出", it, 9, 28) }
        val existing = listOf(
            ScheduledTask(
                id = "eta-1",
                trigger = ScheduledTrigger.DAILY_TIME,
                action = ScheduledActionKind.CUSTOM_PROMPT,
                enabled = true,
                hour = 9,
                minute = 20,
                prompt = "看一眼天气",
            ),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, existing, nowMillis = now(), zone = zone)

        assertTrue(result.none { it.trigger == ScheduledTrigger.DAILY_TIME })
    }

    @Test
    fun `已有任务覆盖同词族时连通知匹配建议也不给`() {
        // 真机场景（2026-11-04 用户反馈）：已有「09:35 打卡」自定义指令后，
        // 系统仍单独推「收到含『签到』的通知就处理」—— 同一件事占了两个名额。
        // 新判据按词族覆盖：打卡/考勤/签到同族，所以一条都不该再给。
        val samples = (1..4).map { sample("com.eg.android.AlipayGphone", "支付宝会员签到提醒", it, 9, 31) }
        val existing = listOf(
            ScheduledTask(
                id = "eta-clock-0935",
                trigger = ScheduledTrigger.DAILY_TIME,
                action = ScheduledActionKind.CUSTOM_PROMPT,
                enabled = true,
                hour = 9,
                minute = 35,
                prompt = "检查今天 09:35 前后关于 打卡 的通知，需要就提醒我。",
            ),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, existing, nowMillis = now(), zone = zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `关掉的账单还款任务仍然抑制账单建议`() {
        // 用户原话：「我有账单还款虽然是关闭的，但是没必要跳采用了，在分组里就没必要跳了，
        // 分组里没有的东西才跳采用」。关闭 ≠ 不存在。
        val samples = (1..4).map { sample("com.sankuai.meituan", "【美团月付】账单已出", it, 9, 28) }
        val existing = listOf(
            ScheduledTask(
                id = "eta-bill",
                trigger = ScheduledTrigger.DAILY_TIME,
                action = ScheduledActionKind.CUSTOM_PROMPT,
                enabled = false,
                hour = 9,
                minute = 20,
                name = "账单、还款",
                prompt = "看看今天有没有账单要还，还款日临近就提醒我。",
            ),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, existing, nowMillis = now(), zone = zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `通知匹配形态的打卡签到任务抑制每天的打卡定时建议`() {
        // 用户原话：「我已经有了打卡签到，他还会跳打卡，这算重复」。
        // 已有任务是 NOTIFICATION_MATCH + keyword「签到」，建议是 DAILY_TIME「打卡」——
        // 旧实现只拿同类触发方式比对，所以拦不住。
        val samples = (1..4).map { sample("com.alibaba.android.rimet", "考勤打卡提醒", it, 9, 25) }
        val existing = listOf(
            ScheduledTask(
                id = "eta-checkin",
                trigger = ScheduledTrigger.NOTIFICATION_MATCH,
                action = ScheduledActionKind.CUSTOM_PROMPT,
                enabled = true,
                keyword = "签到",
                name = "打卡签到",
                prompt = "通知含签到就提醒我打卡。",
            ),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, existing, nowMillis = now(), zone = zone)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `删掉任务后建议重新出现`() {
        // 覆盖判定只来自当前任务列表：删了就该重新提议，不能记在别处。
        val samples = (1..4).map { sample("com.alibaba.android.rimet", "考勤打卡提醒", it, 9, 25) }

        val result = ScheduledSuggestionEngine.suggest(samples, emptyList(), nowMillis = now(), zone = zone)

        assertTrue(result.isNotEmpty())
        assertEquals("打卡", result.first().keywords.first())
    }

    @Test
    fun `关闭的同分钟任务不占时段`() {
        // 关闭的任务不会真的跑模型，同分钟不算浪费；但它仍会按词族覆盖（上一条测试锁着）。
        val samples = (1..4).map { sample("com.sankuai.meituan", "【美团月付】账单已出", it, 9, 28) }
        val existing = listOf(
            ScheduledTask(
                id = "eta-1",
                trigger = ScheduledTrigger.DAILY_TIME,
                action = ScheduledActionKind.CUSTOM_PROMPT,
                enabled = false,
                hour = 9,
                minute = 20,
                prompt = "看一眼天气",
            ),
        )

        val result = ScheduledSuggestionEngine.suggest(samples, existing, nowMillis = now(), zone = zone)

        assertTrue(result.any { it.trigger == ScheduledTrigger.DAILY_TIME })
    }
}

package io.github.mangi.eta.agent.schedule

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * 一条被观察到的通知样本。
 *
 * 这是引擎与数据层之间唯一的契约：引擎不认识数据库，也不认识 Android，
 * 调用方（UI / Repository）负责把 7 天内的通知读成这个列表。
 */
internal data class NotificationSample(
    val packageName: String,
    val title: String,
    val postedAtMillis: Long,
    /**
     * 通知正文。
     *
     * **必须一起匹配**：真机数据里最该被发现的那些提醒，关键词恰恰不在标题上——
     * 快递通知标题是「芝麻粒限时奖励提醒」，正文才是「你有10芝麻粒快递已送达」。
     * 只匹配标题时快递族命中 0 条（正文 6 条），还款同理（标题 5 条 / 正文 16 条）。
     */
    val text: String = "",
)

/**
 * 引擎提出的一条候选规则。**只是建议**：不落库、不注册闹钟、不消耗额度。
 *
 * 刻意**不含任何展示文本**：标题、理由、prompt 都由 UI 层用 stringResource 拼，
 * 这样英文界面不会冒出中文（引擎一旦写死文案就做不到了）。
 * 唯一的中性信息是 [evidenceDays] —— UI 直接展示成「过去 7 天有 5 天如此」。
 *
 * @property keyword [ScheduledTrigger.NOTIFICATION_MATCH] 用：命中的那个词。
 * @property keywords [ScheduledTrigger.DAILY_TIME] 用：该时段要处理的全部词（可多个）。
 */
internal data class ScheduledSuggestion(
    val trigger: ScheduledTrigger,
    val hour: Int,
    val minute: Int,
    val keyword: String,
    val keywords: List<String>,
    val evidenceDays: Int,
) {
    /** UI 去重用的稳定标识：同一形状的建议在多次刷新间保持同一个 id。 */
    val id: String
        get() = buildString {
            append(trigger.id)
            append(':')
            append(keyword)
            append(':')
            append(hour.toString().padStart(2, '0'))
            append(minute.toString().padStart(2, '0'))
        }
}

/**
 * 从通知历史里提炼「值得变成定时任务」的候选规则。
 *
 * 设计约束：
 * 1. 纯函数：不读库、不写库、不联网、不依赖 Android，任何时间都从参数传入。
 * 2. 只提议不执行：引擎永远不创建任务，采纳动作在 UI 层，由用户点。
 * 3. 可解释：每条建议都带证据天数，不允许「模型觉得」。
 *
 * **规则一的教训**：最初按「同一应用每天相近时刻推送」出建议，真机 1000 条数据跑出来
 * 是「每天 18:43 汇总抖音」「每天 20:59 汇总小布助手」——那是刷手机，不是习惯。
 * 所以时段建议必须**由可行动关键词驱动**：只有「账单/打卡/快递」这类词反复出现在
 * 相近时刻，才说明那个时刻真的有事要做。纯包名时段规则已删除。
 */
internal object ScheduledSuggestionEngine {

    const val MAX_SUGGESTIONS = 3

    /** 只看最近这些天的通知。与 NotificationHistoryRepository 的保留期一致。 */
    private const val WINDOW_DAYS = 7

    /** 「相近时刻」半径：关键词命中落在某个时刻前后这么多分钟内，就算同一时段。 */
    private const val WINDOW_MINUTES = 30

    /**
     * 判「和已有任务撞死」的半径：只用来防止两个自定义指令在同一分钟各跑一次模型。
     *
     * 刻意远小于 [WINDOW_MINUTES]：时段相邻不代表是同一件事（09:00 权限检查 ≠ 09:20 账单）。
     */
    private const val SAME_SLOT_MINUTES = 5

    /** 至少要覆盖这么多天，才认为它是习惯而不是偶然。 */
    private const val MIN_EVIDENCE_DAYS = 3

    /** 建议时刻比习惯发生时刻提前的量。 */
    private const val LEAD_MINUTES = 5

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * 这些词出现时，用户大概率需要处理。刻意保持短：宁可漏，也不要制造噪音。
     *
     * 按**词族**归类而不是逐个词统计：真机数据里「快递」和「派送」是同一条消息的两种说法，
     * 逐词统计会一次冒出两条几乎一样的建议。族内取出现次数最多、同次数时取更具体的词
     * 作为 [ScheduledTask.keyword]（匹配时只认一个词），展示用族名。
     */
    private data class KeywordFamily(val label: String, val words: List<String>)

    private val KEYWORD_FAMILIES = listOf(
        KeywordFamily("账单", listOf("账单")),
        KeywordFamily("还款", listOf("还款", "还款日")),
        KeywordFamily("缴费", listOf("缴费", "续费", "到期")),
        KeywordFamily("快递", listOf("快递", "派送", "取件", "揽收")),
        KeywordFamily("打卡", listOf("打卡", "考勤", "签到")),
        KeywordFamily("会议", listOf("会议", "预约", "挂号")),
        KeywordFamily("行程", listOf("行程", "航班", "接机", "退票", "改签")),
    )

    /**
     * 生成候选规则。
     *
     * @param samples 最近的通知样本（超出 [WINDOW_DAYS] 的会被忽略）。
     * @param existingTasks 已有任务，用来避免重复建议。
     * @param nowMillis 当前时间，注入以便测试。
     * @param zone 拆分「第几天 / 一天中的第几分钟」用的时区。
     */
    fun suggest(
        samples: List<NotificationSample>,
        existingTasks: List<ScheduledTask> = emptyList(),
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<ScheduledSuggestion> {
        val clock = Clock(zone)
        val from = nowMillis - WINDOW_DAYS * DAY_MS
        val recent = samples.filter {
            it.packageName.isNotBlank() &&
                it.postedAtMillis in from..nowMillis &&
                !isExcluded(it.packageName)
        }
        if (recent.isEmpty()) return emptyList()

        val hits = KEYWORD_FAMILIES.mapNotNull { family ->
            val byWord = family.words
                .map { word -> word to recent.filter { it.matches(word) } }
                .filter { it.second.isNotEmpty() }
            if (byWord.isEmpty()) return@mapNotNull null

            // 族内出现最多的词作为匹配词；次数相同时取更具体的（更长的）那个。
            val keyword = byWord.maxWithOrNull(
                compareBy<Pair<String, List<NotificationSample>>> { it.second.size }
                    .thenBy { it.first.length }
            )!!.first
            val matched = byWord.flatMap { it.second }.distinctBy { it.postedAtMillis to it.title }
            if (matched.size < MIN_EVIDENCE_DAYS) return@mapNotNull null
            val days = matched.map { clock.dayKey(it.postedAtMillis) }.toSet()
            if (days.size < 2) return@mapNotNull null
            KeywordHit(family, keyword, matched, days.size)
        }
        if (hits.isEmpty()) return emptyList()

        // 先按已有任务过滤，再算「已被定时建议覆盖的词」。
        // 顺序反了会双杀：被 conflicts 吃掉的定时建议仍然把词标成已覆盖，
        // 规则二（收到就处理）也被排除，结果一条都不剩（2026-11-04 真机实测踩到）。
        val timed = timedSuggestions(hits, clock).filterNot { conflicts(it, existingTasks) }
        // 真正留下来的定时建议已经覆盖的词，不再单独给「收到就处理」：
        // 真机数据里「账单」会同时满足两条规则，同一件事占两个名额，看着像重复。
        val covered = timed.flatMap { it.keywords }.toSet()
        val matches = keywordSuggestions(hits.filterNot { it.family.label in covered })
        val suggestions = timed + matches

        return ensureDailySlot(
            suggestions
                .filterNot { conflicts(it, existingTasks) }
                .sortedWith(
                    compareByDescending<ScheduledSuggestion> { it.evidenceDays }
                        .thenBy { if (it.trigger == ScheduledTrigger.DAILY_TIME) 0 else 1 }
                        .thenBy { it.hour * 60 + it.minute }
                )
                .distinctBy { it.id }
        ).take(MAX_SUGGESTIONS)
    }

    /**
     * 规则一：某些关键词的命中集中在固定时段。
     *
     * 同一时段往往同时命中多个词（账单 / 还款 / 还款日），按「建议时刻」合并成一条，
     * 词全部交给 prompt —— 模型能理解一串词，而 `ScheduledTask.keyword` 只认一个词。
     */
    private fun timedSuggestions(
        hits: List<KeywordHit>,
        clock: Clock,
    ): List<ScheduledSuggestion> {
        data class Timed(val label: String, val centerMinute: Int, val days: Int)

        val timed = hits.mapNotNull { hit ->
            val points = hit.samples.map { clock.minuteOfDay(it.postedAtMillis) }
            val best = points
                .map { minute -> minute to points.count { circularDistance(minute, it) <= WINDOW_MINUTES } }
                .maxByOrNull { it.second }
                ?: return@mapNotNull null
            val center = best.first
            val days = hit.samples
                .filter { circularDistance(center, clock.minuteOfDay(it.postedAtMillis)) <= WINDOW_MINUTES }
                .map { clock.dayKey(it.postedAtMillis) }
                .toSet()
                .size
            if (days < MIN_EVIDENCE_DAYS) null else Timed(hit.family.label, center, days)
        }
        if (timed.isEmpty()) return emptyList()

        // 按「建议时刻」对齐到 5 分钟后再分组，避免 09:23 和 09:24 被拆成两条。
        return timed
            .groupBy { alignedSlot(it.centerMinute) }
            .map { (slot, group) ->
                ScheduledSuggestion(
                    trigger = ScheduledTrigger.DAILY_TIME,
                    hour = slot / 60,
                    minute = slot % 60,
                    keyword = "",
                    keywords = group.map { it.label }.distinct(),
                    evidenceDays = group.maxOf { it.days },
                )
            }
    }

    /** 规则二：关键词跨天出现，但时间不固定 → 命中就处理。 */
    private fun keywordSuggestions(hits: List<KeywordHit>): List<ScheduledSuggestion> =
        hits.sortedWith(
            compareByDescending<KeywordHit> { it.days }.thenByDescending { it.samples.size }
        ).map { hit ->
            ScheduledSuggestion(
                trigger = ScheduledTrigger.NOTIFICATION_MATCH,
                hour = 0,
                minute = 0,
                keyword = hit.keyword,
                keywords = listOf(hit.family.label),
                evidenceDays = hit.days,
            )
        }

    /**
     * 名额有限时给「每天定时」留一个位置。
     *
     * 它的证据天数可能比某条通知匹配少一天，被挤掉后界面会剩下三条「收到 X 就处理」——
     * 那是同一类建议，价值重复。这条规则只在候选超过 [MAX_SUGGESTIONS] 时生效。
     */
    private fun ensureDailySlot(sorted: List<ScheduledSuggestion>): List<ScheduledSuggestion> {
        if (sorted.size <= MAX_SUGGESTIONS) return sorted
        if (sorted.take(MAX_SUGGESTIONS).any { it.trigger == ScheduledTrigger.DAILY_TIME }) return sorted
        val daily = sorted.firstOrNull { it.trigger == ScheduledTrigger.DAILY_TIME } ?: return sorted
        return listOf(daily) + sorted.filterNot { it.trigger == ScheduledTrigger.DAILY_TIME }
    }

    /**
     * 已经存在「同一件事」的任务就不再建议。
     *
     * **判据不能只看时间**：真机上 09:00 是权限检查、09:35 是打卡检查，把 09:20 的账单建议
     * 按 ±30 分钟判成重复，结果一条建议都出不来。判断重复要看**做什么**，不是几点做。
     *
     * [ScheduledTrigger.DAILY_TIME] 只在两种情况下算重复：
     * 1. 已有任务的自定义指令里已经写了这个词族的词（真的是同一件事）；
     * 2. 已有任务也是自定义指令、且时间几乎撞死（≤[SAME_SLOT_MINUTES] 分钟）——同时跑两次模型纯浪费。
     */
    private fun conflicts(
        suggestion: ScheduledSuggestion,
        existingTasks: List<ScheduledTask>,
    ): Boolean = existingTasks.any { task ->
        if (!task.enabled) return@any false
        when {
            suggestion.trigger == ScheduledTrigger.NOTIFICATION_MATCH ->
                task.trigger == ScheduledTrigger.NOTIFICATION_MATCH &&
                    task.keyword.equals(suggestion.keyword, ignoreCase = true)

            suggestion.trigger == ScheduledTrigger.DAILY_TIME ->
                task.trigger == ScheduledTrigger.DAILY_TIME &&
                    (
                        sameJob(task, suggestion) ||
                            (
                                task.action == ScheduledActionKind.CUSTOM_PROMPT &&
                                    circularDistance(
                                        task.hour * 60 + task.minute,
                                        suggestion.hour * 60 + suggestion.minute,
                                    ) <= SAME_SLOT_MINUTES
                                )
                        )

            else -> false
        }
    }

    /** 已有任务是不是已经在做同一件事：它的指令文本里出现了这个词族的词。 */
    private fun sameJob(task: ScheduledTask, suggestion: ScheduledSuggestion): Boolean {
        if (task.action != ScheduledActionKind.CUSTOM_PROMPT || task.prompt.isBlank()) return false
        return suggestion.keywords
            .flatMap { label -> KEYWORD_FAMILIES.firstOrNull { it.label == label }?.words.orEmpty() }
            .any { task.prompt.contains(it, ignoreCase = true) }
    }

    /** 标题和正文都算命中。真机上最该被发现的关键词常常只出现在正文里（快递就是）。 */
    private fun NotificationSample.matches(word: String): Boolean =
        title.contains(word, ignoreCase = true) || text.contains(word, ignoreCase = true)

    /** 自身与系统包的噪音太大（会建议「汇总 Eta 自己的通知」），直接排除。 */
    private fun isExcluded(packageName: String): Boolean =
        packageName == "io.github.mangi.eta" ||
            packageName == "android" ||
            packageName.startsWith("com.android.")

    /** 建议时刻 = 命中中心提前 [LEAD_MINUTES]，向下对齐到 5 分钟。 */
    private fun alignedSlot(centerMinute: Int): Int {
        val raw = ((centerMinute - LEAD_MINUTES) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return (raw / 5) * 5
    }

    /** 一天之内的环形距离，范围 0..720。 */
    private fun circularDistance(a: Int, b: Int): Int {
        val diff = abs(a - b)
        return minOf(diff, MINUTES_PER_DAY - diff)
    }

    private data class KeywordHit(
        val family: KeywordFamily,
        /** 族内出现次数最多的具体词，用于建任务后的通知匹配。 */
        val keyword: String,
        val samples: List<NotificationSample>,
        val days: Int,
    )

    /** 把毫秒拆成「第几天」与「一天中的第几分钟」，只用传入的时区，不读系统默认值。 */
    private class Clock(private val zone: TimeZone) {
        private val calendar = Calendar.getInstance(zone)

        fun dayKey(millis: Long): Int {
            calendar.timeInMillis = millis
            // 年 × 400 + 年内第几天：跨年也不会把 1 月 1 日和去年 12 月 31 日算成同一天。
            return calendar.get(Calendar.YEAR) * 400 + calendar.get(Calendar.DAY_OF_YEAR)
        }

        fun minuteOfDay(millis: Long): Int {
            calendar.timeInMillis = millis
            return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        }
    }
}

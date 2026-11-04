package io.github.mangi.eta.agent.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务头衔的回退链：用户填的 → 关键词 → 指令正文里能看出来的事 → 空（交给调用方用动作名）。
 *
 * 这套规则直接决定「列表和通知栏好不好看」，所以钉死。
 */
class ScheduledTaskNamingTest {

    private fun task(
        name: String = "",
        keyword: String = "",
        prompt: String = "",
        action: ScheduledActionKind = ScheduledActionKind.CUSTOM_PROMPT,
    ) = ScheduledTask(
        id = "t1",
        trigger = ScheduledTrigger.NOTIFICATION_MATCH,
        action = action,
        enabled = true,
        keyword = keyword,
        name = name,
        prompt = prompt,
    )

    @Test
    fun nameWinsOverEverythingElse() {
        val result = ScheduledTaskNaming.nameCandidate(
            task(name = "账单", keyword = "账单通知", prompt = "随便写点什么")
        )
        assertEquals("账单", result)
    }

    @Test
    fun keywordIsTheSecondChoice() {
        val result = ScheduledTaskNaming.nameCandidate(
            task(keyword = "派送", prompt = "刚收到含「派送」的通知")
        )
        assertEquals("派送", result)
    }

    @Test
    fun quotedWordInPromptIsUsedWhenThereIsNoKeyword() {
        val result = ScheduledTaskNaming.nameCandidate(
            task(prompt = "刚收到含「账单」的通知，帮我看看是哪笔")
        )
        assertEquals("账单", result)
    }

    @Test
    fun aboutPatternIsRecognised() {
        // 真机上 09:20 那条就是这样写的：没有关键词，只能从正文里认。
        val result = ScheduledTaskNaming.nameCandidate(
            task(prompt = "检查今天 09:20 前后关于 账单、还款 的通知和待办，逐条判断要不要处理")
        )
        assertEquals("账单、还款", result)
    }

    @Test
    fun fallsBackToLeadingWordsWhenNothingMatches() {
        val result = ScheduledTaskNaming.nameCandidate(task(prompt = "看一下今天的待办和重要通知"))
        assertTrue("应当是正文开头", result.isNotEmpty())
        assertTrue("应当比正文短", result.length <= 16)
    }

    @Test
    fun blankEverythingGivesEmptySoCallerCanFallBackToActionName() {
        assertEquals("", ScheduledTaskNaming.nameCandidate(task()))
    }

    @Test
    fun backfillFillsOnlyBlankNames() {
        val tasks = listOf(
            task(name = "我自己取的", keyword = "签到"),
            task(keyword = "账单"),
        )
        val result = ScheduledTaskNaming.backfill(tasks)
        assertEquals("我自己取的", result[0].name)
        assertEquals("账单", result[1].name)
    }

    @Test
    fun backfillLeavesTasksAloneWhenThereIsNothingToDerive() {
        val tasks = listOf(task())
        assertEquals(tasks, ScheduledTaskNaming.backfill(tasks))
    }
}

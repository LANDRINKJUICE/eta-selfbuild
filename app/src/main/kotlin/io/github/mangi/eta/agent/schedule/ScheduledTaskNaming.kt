package io.github.mangi.eta.agent.schedule

/**
 * 任务「头衔」的统一来源：列表和通知栏都走这里，同一件事不会两处两个叫法。
 *
 * 为什么需要：`ScheduledTask.name` 是后加的字段，老任务没有；而自定义指令类任务
 * 如果没名字，通知栏里一路显示「自定义指令」（用户 2026-11-04 反馈「怪怪的」）。
 * 这里给出一条回退链，尽量从已有信息里推出一个人话名字。
 *
 * 纯逻辑、不碰 Context，便于直接写单测。
 */
internal object ScheduledTaskNaming {

    /**
     * 取一个能当标题用的名字，按顺序找第一个非空的：
     * 用户填的名称 → 通知关键词 → 指令正文里能看出来的事 → 空串（调用方回退到动作名）。
     */
    fun nameCandidate(task: ScheduledTask): String {
        task.name.trim().takeIf { it.isNotEmpty() }?.let { return it.take(MAX_NAME_CHARS) }
        task.keyword.trim().takeIf { it.isNotEmpty() }?.let { return it.take(MAX_NAME_CHARS) }
        return promptHint(task.prompt)
    }

    /**
     * 老任务补名称：只补 `name` 为空的，绝不覆盖用户已经填过的。
     *
     * 由界面在加载任务列表时调一次；返回新列表，调用方自行决定要不要落盘。
     */
    fun backfill(tasks: List<ScheduledTask>): List<ScheduledTask> = tasks.map { task ->
        if (task.name.isNotBlank()) {
            task
        } else {
            nameCandidate(task).takeIf { it.isNotEmpty() }?.let { task.copy(name = it) } ?: task
        }
    }

    /**
     * 从指令正文里猜一个短名字。只认两种最常见写法：
     * 被引号括起来的那件事（「」『』""''），或者「关于 X 的」。
     * 都认不出来就取开头几个字——总比在通知栏写「自定义指令」强。
     */
    private fun promptHint(prompt: String): String {
        val text = prompt.trim()
        if (text.isEmpty()) return ""
        QUOTED.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it.take(MAX_NAME_CHARS)
        }
        ABOUT.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it.take(MAX_NAME_CHARS)
        }
        return text.take(PROMPT_HINT_CHARS)
    }

    private val QUOTED = Regex("[「『\"'“”‘’]([^「」『』\"'“”‘’]{1,12})[」』\"'“”‘’]")
    private val ABOUT = Regex("关于\\s*([^，。的]{1,12})\\s*的")
    private const val MAX_NAME_CHARS = 16
    private const val PROMPT_HINT_CHARS = 10
}

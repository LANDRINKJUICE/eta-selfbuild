package io.github.mangi.eta.agent.schedule

/**
 * 新建任务时的「常用场景」。
 *
 * 定位：**只是起点，不是模板**。点一下会把名称、触发方式、关键词、指令一起填好，
 * 之后想改什么都能改（名称、关键词、指令、时间全部可编辑）。
 *
 * 关键词都取自本机真实通知里出现过的词（2026-11-04 从通知历史里挑的高频项），
 * 不是拍脑袋造的；指令也照着「用户会怎么跟自己说话」写，矫揉造作的模板不如没有。
 */
internal data class ScheduledPreset(
    val id: String,
    val name: String,
    val trigger: ScheduledTrigger,
    val prompt: String,
    val keyword: String = "",
)

internal object ScheduledPresets {

    val all: List<ScheduledPreset> = listOf(
        ScheduledPreset(
            id = "delivery",
            name = "快递派送",
            trigger = ScheduledTrigger.NOTIFICATION_MATCH,
            keyword = "派送",
            prompt = "刚收到含「派送」的通知。帮我看是哪家快递、送到哪儿了；如果是要取件，把取件码和驿站位置一起找出来。",
        ),
        ScheduledPreset(
            id = "pickup",
            name = "取件码",
            trigger = ScheduledTrigger.NOTIFICATION_MATCH,
            keyword = "取件",
            prompt = "刚收到含「取件」的通知。帮我把取件码、驿站位置和截止时间找出来。",
        ),
        ScheduledPreset(
            id = "bill",
            name = "账单",
            trigger = ScheduledTrigger.NOTIFICATION_MATCH,
            keyword = "账单",
            prompt = "刚收到含「账单」的通知。帮我看看是哪笔、金额多少、要不要现在处理，需要的话给出一两步具体操作。",
        ),
        ScheduledPreset(
            id = "repay",
            name = "还款",
            trigger = ScheduledTrigger.NOTIFICATION_MATCH,
            keyword = "还款",
            prompt = "刚收到含「还款」的通知。帮我确认金额和到期日，别让我错过时间。",
        ),
        ScheduledPreset(
            id = "checkin",
            name = "打卡签到",
            trigger = ScheduledTrigger.NOTIFICATION_MATCH,
            keyword = "签到",
            prompt = "刚收到含「签到」的通知。帮我看看是哪个 App、打没打上，需要的话提醒我补一下。",
        ),
        ScheduledPreset(
            id = "daily_todo",
            name = "每日待办",
            trigger = ScheduledTrigger.DAILY_TIME,
            prompt = "看一下今天的待办和重要通知，挑出真正需要我处理的，每件给出一两步具体操作；没有就一句话带过。",
        ),
    )
}

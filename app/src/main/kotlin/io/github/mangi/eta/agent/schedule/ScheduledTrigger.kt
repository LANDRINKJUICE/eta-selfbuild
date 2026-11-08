package io.github.mangi.eta.agent.schedule

import org.json.JSONObject

/**
 * 定时任务的触发条件。
 *
 * [DAILY_TIME] 是"每天几点"，其余是**事件驱动**：只要事件发生且条件命中就执行。
 * 事件来源都是普通广播（充电、电量、Wi-Fi、解锁）或通知监听，不需要新的系统权限。
 */
internal enum class ScheduledTrigger(val id: String) {
    /** 每天 [ScheduledTask.hour]:[ScheduledTask.minute]。 */
    DAILY_TIME("daily_time"),

    /** 插上充电器。 */
    POWER_CONNECTED("power_connected"),

    /** 拔掉充电器。 */
    POWER_DISCONNECTED("power_disconnected"),

    /** 电量降到阈值（含）以下。 */
    BATTERY_LOW("battery_low"),

    /** 电量回到阈值（含）以上。 */
    BATTERY_OK("battery_ok"),

    /** 屏幕解锁（用户开始使用手机）。 */
    SCREEN_UNLOCKED("screen_unlocked"),

    /** 连上 Wi-Fi。 */
    WIFI_CONNECTED("wifi_connected"),

    /** 断开 Wi-Fi。 */
    WIFI_DISCONNECTED("wifi_disconnected"),

    /** 收到匹配 [ScheduledTask.keyword] 的通知。 */
    NOTIFICATION_MATCH("notification_match"),
    ;

    /** 是否需要闹钟注册（只有按时间触发的才需要）。 */
    val usesAlarm: Boolean get() = this == DAILY_TIME

    companion object {
        fun fromId(value: String?): ScheduledTrigger? = entries.firstOrNull { it.id == value }
    }
}

/**
 * 一条自动化任务。
 *
 * [trigger] 决定"什么时候"，[action] + [prompt] 决定"做什么"。
 * 时间字段只对 [ScheduledTrigger.DAILY_TIME] 有意义；[keyword] 只对
 * [ScheduledTrigger.NOTIFICATION_MATCH] 有意义；[threshold] 只对电量两条有意义。
 */
internal data class ScheduledTask(
    val id: String,
    val trigger: ScheduledTrigger,
    val action: ScheduledActionKind,
    val enabled: Boolean,
    /** 每天触发时的时、分；事件触发时忽略。 */
    val hour: Int = 0,
    val minute: Int = 0,
    /** 电量阈值（百分比），仅 BATTERY_LOW / BATTERY_OK 使用。 */
    val threshold: Int = 20,
    /** 通知关键词，仅 NOTIFICATION_MATCH 使用；大小写不敏感。 */
    val keyword: String = "",
    /**
     * 任务名称：列表与通知栏的头衔（「账单」「快递派送」这种人话）。
     *
     * 留空时按 关键词 → 指令正文 → 动作名 回退，见 [ScheduledTaskNaming]。
     * 这个字段是后加的，老任务读出来是空串，不会破坏既有数据。
     */
    val name: String = "",
    /**
     * 后台执行时的权限级别。
     *
     * - `true` = **只读分析**：禁 GUI 操作、敏感操作、终端、浏览器、子代理；
     * - `false` = **完全开放**：和主会话一样；
     * - `null` = 按触发方式推断（通知匹配 → 只读，用户自建定时 → 开放）。
     *
     * 2026-11-04：通知匹配任务为了「看看签到没」直接打开并操作了支付宝，所以默认收紧；
     * 用户自己建的定时任务（明确要求帮忙做事）仍可完全开放。
     */
    val readOnly: Boolean? = null,
    val lastRunAtMillis: Long? = null,
    /** 仅 [ScheduledActionKind.CUSTOM_PROMPT] 使用：交给 Agent 执行的指令内容。 */
    val prompt: String = "",

    /**
     * 仅 [ScheduledActionKind.PLUGIN] 使用：要调用的插件 id。
     *
     * 后加字段，老任务读出来是空串（执行时按「没指定插件」处理），不破坏既有数据。
     */
    val pluginId: String = "",
    /**
     * 仅 [ScheduledActionKind.PLUGIN] 使用：传给插件的参数（JSON object 文本）。
     *
     * 2026-11-08 补：以前 PluginInvoker 硬编码 `argumentsJson = "{}"`，
     * 于是声明了必填参数的插件在定时任务里**永远失败**，且失败原因（缺参数）不明显。
     * 后加字段，老任务读出来是空串，按「不带参数」处理。
     */
    val pluginArguments: String = "",
    /** 发起该任务的会话；通知栏「回复」会回到这个会话。本地动作无会话，保持空串。 */
    val conversationId: String = "",
    /** 重复周期：每天 / 每周 / 每月 / 每 N 天 / 每 N 小时。 */
    val repeat: ScheduledRepeat = ScheduledRepeat.DAILY,
    /** 周几（Calendar 常量），仅 WEEKLY 使用。 */
    val dayOfWeek: Int = java.util.Calendar.MONDAY,
    /** 每月几号，仅 MONTHLY 使用。 */
    val dayOfMonth: Int = 1,
    /** N 的取值，仅 EVERY_N_DAYS / EVERY_N_HOURS 使用。 */
    val interval: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("trigger", trigger.id)
        .put("action", action.id)
        .put("enabled", enabled)
        .put("hour", hour)
        .put("minute", minute)
        .put("threshold", threshold)
        .put("keyword", keyword)
        .put("name", name)
        .put("read_only", readOnly ?: JSONObject.NULL)
        .put("last_run_at", lastRunAtMillis ?: JSONObject.NULL)
        .put("prompt", prompt)
        .put("pluginId", pluginId)
        .put("plugin_arguments", pluginArguments)
        .put("conversation_id", conversationId)
        .put("repeat", repeat.id)
        .put("day_of_week", dayOfWeek)
        .put("day_of_month", dayOfMonth)
        .put("interval", interval)

    /** 事件是否命中这条任务的条件（不含 enabled 判断，由调用方负责）。 */
    fun matchesEvent(event: ScheduledEvent): Boolean = when (trigger) {
        ScheduledTrigger.POWER_CONNECTED -> event is ScheduledEvent.PowerConnected
        ScheduledTrigger.POWER_DISCONNECTED -> event is ScheduledEvent.PowerDisconnected
        ScheduledTrigger.BATTERY_LOW -> event is ScheduledEvent.BatteryChanged && event.percent <= threshold
        ScheduledTrigger.BATTERY_OK -> event is ScheduledEvent.BatteryChanged && event.percent >= threshold &&
            event.charging
        ScheduledTrigger.SCREEN_UNLOCKED -> event is ScheduledEvent.ScreenUnlocked
        ScheduledTrigger.WIFI_CONNECTED -> event is ScheduledEvent.WifiChanged && event.connected
        ScheduledTrigger.WIFI_DISCONNECTED -> event is ScheduledEvent.WifiChanged && !event.connected
        ScheduledTrigger.NOTIFICATION_MATCH -> event is ScheduledEvent.NotificationPosted &&
            keyword.isNotBlank() && event.matches(keyword)
        ScheduledTrigger.DAILY_TIME -> false
    }

    /** 列表里显示"什么时候"：时间就是 HH:mm；其他给出触发条件摘要。 */
    fun triggerSummary(): TriggerSummary = when (trigger) {
        ScheduledTrigger.DAILY_TIME -> TriggerSummary("${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}")
        ScheduledTrigger.POWER_CONNECTED -> TriggerSummary("插入充电")
        ScheduledTrigger.POWER_DISCONNECTED -> TriggerSummary("拔掉充电")
        ScheduledTrigger.BATTERY_LOW -> TriggerSummary("电量 ≤ $threshold%")
        ScheduledTrigger.BATTERY_OK -> TriggerSummary("电量 ≥ $threshold% 且充电中")
        ScheduledTrigger.SCREEN_UNLOCKED -> TriggerSummary("解锁屏幕")
        ScheduledTrigger.WIFI_CONNECTED -> TriggerSummary("连上 Wi-Fi")
        ScheduledTrigger.WIFI_DISCONNECTED -> TriggerSummary("断开 Wi-Fi")
        ScheduledTrigger.NOTIFICATION_MATCH ->
            TriggerSummary(if (keyword.isBlank()) "通知（未设关键词）" else "通知含「$keyword」")
    }

    companion object {
        fun fromJson(json: JSONObject): ScheduledTask? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val action = ScheduledActionKind.fromId(json.optString("action")) ?: return null
            // 兼容早先只存 action/hour/minute 的旧数据：没有 trigger 字段时按"每天定时"读。
            val trigger = ScheduledTrigger.fromId(json.optString("trigger"))
                ?: ScheduledTrigger.DAILY_TIME
            val lastRun = if (json.isNull("last_run_at")) {
                null
            } else {
                json.optLong("last_run_at", 0L).takeIf { it > 0L }
            }
            return ScheduledTask(
                id = id,
                trigger = trigger,
                action = action,
                enabled = json.optBoolean("enabled", true),
                hour = json.optInt("hour", 0).coerceIn(0, 23),
                minute = json.optInt("minute", 0).coerceIn(0, 59),
                threshold = json.optInt("threshold", 20).coerceIn(1, 99),
                keyword = json.optString("keyword", ""),
                name = json.optString("name", ""),
                // 老任务没有这个字段 → null，交给 ScheduledAgentRunner 按触发方式推断。
                readOnly = if (json.has("read_only") && !json.isNull("read_only")) {
                    json.optBoolean("read_only")
                } else {
                    null
                },
                lastRunAtMillis = lastRun,
                prompt = json.optString("prompt", ""),
                pluginId = json.optString("pluginId", ""),
                pluginArguments = json.optString("plugin_arguments", ""),
                conversationId = json.optString("conversation_id", ""),
                repeat = ScheduledRepeat.fromId(json.optString("repeat")) ?: ScheduledRepeat.DAILY,
                dayOfWeek = json.optInt("day_of_week", java.util.Calendar.MONDAY),
                dayOfMonth = json.optInt("day_of_month", 1).coerceIn(1, 31),
                interval = json.optInt("interval", 1).coerceIn(1, 30),
            )
        }

        /** 触发条件的展示文本（时间或条件）。 */
        data class TriggerSummary(val text: String)
    }
}

/** 会被转发给定时任务系统的设备事件。 */
internal sealed interface ScheduledEvent {
    data object PowerConnected : ScheduledEvent
    data object PowerDisconnected : ScheduledEvent
    data class BatteryChanged(val percent: Int, val charging: Boolean) : ScheduledEvent
    data object ScreenUnlocked : ScheduledEvent
    data class WifiChanged(val connected: Boolean) : ScheduledEvent
    data class NotificationPosted(val packageName: String, val title: String, val text: String) : ScheduledEvent {
        fun matches(keyword: String): Boolean {
            val needle = keyword.trim()
            if (needle.isEmpty()) return false
            return title.contains(needle, ignoreCase = true) || text.contains(needle, ignoreCase = true) ||
                packageName.contains(needle, ignoreCase = true)
        }
    }

    companion object {
        /** 把一批事件序列化进 Intent（跨进程/跨组件传递用）。 */
        fun encode(event: ScheduledEvent): String = JSONObject().apply {
            when (event) {
                is PowerConnected -> put("type", "power_connected")
                is PowerDisconnected -> put("type", "power_disconnected")
                is BatteryChanged -> put("type", "battery").put("percent", event.percent).put("charging", event.charging)
                is ScreenUnlocked -> put("type", "unlocked")
                is WifiChanged -> put("type", "wifi").put("connected", event.connected)
                is NotificationPosted -> put("type", "notification")
                    .put("package", event.packageName).put("title", event.title).put("text", event.text)
            }
        }.toString()

        fun decode(raw: String?): ScheduledEvent? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                when (json.optString("type")) {
                    "power_connected" -> PowerConnected
                    "power_disconnected" -> PowerDisconnected
                    "battery" -> BatteryChanged(json.optInt("percent", 0), json.optBoolean("charging", false))
                    "unlocked" -> ScreenUnlocked
                    "wifi" -> WifiChanged(json.optBoolean("connected", false))
                    "notification" -> NotificationPosted(
                        json.optString("package"),
                        json.optString("title"),
                        json.optString("text"),
                    )
                    else -> null
                }
            }.getOrNull()
        }
    }
}

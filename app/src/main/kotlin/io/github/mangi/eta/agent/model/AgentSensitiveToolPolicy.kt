package io.github.mangi.eta.agent.model

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in sensitiveTools || toolName in io.github.mangi.eta.agent.context.PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.names

    private val sensitiveTools = setOf(
        // 这两个工具的参数里会带 api_key / bearer_token 原文。
        // 不标成敏感的话，参数会被原样写进会话 transcript 与对话库（2026-11-08 审查指出）。
        "manage_provider",
        "manage_mcp",
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        "get_device_environment",
        "list_alarms",
        "list_active_timers",
        "search_clipboard_history",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_coloros_notes",
        "search_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_system_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        "read_image",
        "save_screenshot",
        "set_setting",
        "memory_get",
        "memory_write",
        "character_memory_get",
        "character_memory_write",
    )
}

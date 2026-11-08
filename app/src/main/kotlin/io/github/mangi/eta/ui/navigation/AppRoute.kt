package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.Serializable
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data class Browser(val sessionKey: String = BrowserSessionRegistry.MANUAL_SESSION_KEY) : AppRoute

    @Serializable
    data object Terminal : AppRoute

    @Serializable
    data object Tools : AppRoute

    @Serializable
    data object WebToolsSettings : AppRoute

    @Serializable
    data object Skills : AppRoute

    @Serializable
    data object Subagents : AppRoute

    /** 说话风格：预设 + 自定义，只影响语气。 */
    @Serializable
    data object SpeechStyle : AppRoute

    /** GitHub 账号：连 token / 看限速 / 断开。以后加其他服务时再抽一级「账号」列表。 */
    @Serializable
    data object GitHubAccount : AppRoute

    @Serializable
    data object Characters : AppRoute

    @Serializable
    data class CharacterDetail(val characterId: String) : AppRoute

    @Serializable
    data class CharacterEditor(val characterId: String? = null) : AppRoute

    @Serializable
    data object CharacterPersona : AppRoute

    @Serializable
    data class CharacterMemory(val characterId: String) : AppRoute

    @Serializable
    data object Permissions : AppRoute

    @Serializable
    data object SystemEnhance : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object AppearanceSettings : AppRoute

    /** 定时任务页：每天到点自动做一件事（查设备状态、记忆状态、权限体检）；入口在设置页「定时任务」组。 */
    @Serializable
    data object ScheduledTasks : AppRoute

    /** 内置文件管理器；入口在设置页「上下文与扩展」与首页。 */
    @Serializable
    data object FileManager : AppRoute

    /**
     * 文件选择（附件按钮用）：同一个文件管理器，但底部带「确认选择」。
     *
     * 单独做一个路由而不是塞进 FileManager 的构造参数，是因为路由参数会被序列化进返回栈，
     * 而选择结果回调没法序列化；这里由 AgentAppRoot 在渲染时挂上 pending 回调即可。
     */
    @Serializable
    data class FilePicker(val initialPath: String = "/sdcard/Download") : AppRoute

    @Serializable
    data object SpeechSettings : AppRoute

    @Serializable
    data object SpeechRecognition : AppRoute

    @Serializable
    data object SpeechSynthesis : AppRoute

    @Serializable
    data object SpeechOss : AppRoute

    @Serializable
    data object DataBackup : AppRoute

    @Serializable
    data object Memory : AppRoute

    @Serializable
    data object LinuxEnvironment : AppRoute

    @Serializable
    data object SharedFolders : AppRoute

    @Serializable
    data object Workspace : AppRoute

    @Serializable
    data class LinuxFiles(val distribution: String) : AppRoute

    @Serializable
    data object ModelProviders : AppRoute

    @Serializable
    data object CommunityCatalog : AppRoute

    @Serializable
    data class CommunityCatalogProvider(val catalogId: String) : AppRoute

    @Serializable
    data object McpServers : AppRoute

    /** 插件列表（设置 → 扩展 → 插件）。 */
    @Serializable
    data object Plugins : AppRoute

    /** 插件详情：授权与开关。 */
    @Serializable
    data class PluginDetail(val pluginId: String) : AppRoute

    @Serializable
    data class McpServerDetail(val serverId: String) : AppRoute

    @Serializable
    data class ModelProviderDetail(val providerId: String) : AppRoute

    @Serializable
    data class ModelProviderNew(val providerType: NewProviderType) : AppRoute
}

@Serializable
enum class NewProviderType { OpenAiCompatible, Anthropic, Gemini }

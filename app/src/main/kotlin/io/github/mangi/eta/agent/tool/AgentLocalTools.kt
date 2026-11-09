package io.github.mangi.eta.agent.tool

import android.content.Context
import android.graphics.Rect
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.Display
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.device.DeviceControlUnavailableException
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.web.AgentWebTools
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.GestureIndicator
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.data.repository.NotificationHistoryRepository
import io.github.mangi.eta.agent.schedule.ScheduledActionKind
import io.github.mangi.eta.agent.schedule.ScheduledRepeat
import io.github.mangi.eta.agent.schedule.ScheduledSuggestionEngine
import io.github.mangi.eta.agent.schedule.ScheduledTask
import io.github.mangi.eta.agent.schedule.ScheduledTaskScheduler
import io.github.mangi.eta.agent.schedule.ScheduledTaskStore
import io.github.mangi.eta.agent.schedule.ScheduledTrigger
import io.github.mangi.eta.agent.terminal.SharedFolderMount
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillErrorJournal
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillInstallErrorCode
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillLoader
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.SkillResourceReader
import io.github.mangi.eta.agent.skill.SkillResourceReadResult
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.GitHubSkillInspection
import io.github.mangi.eta.agent.skill.GitHubSkillRepository
import io.github.mangi.eta.agent.skill.GitHubSkillSourceException
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.data.repository.AgentMemoryException
import io.github.mangi.eta.agent.memory.AgentMemoryValidator
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.WebToolsSettingsRepository
import java.util.Locale
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenCodeProviderSetting
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.model.CustomProviderSetting
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository

internal class AgentLocalTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val browserRunId: String = "",
    private val browserToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS)
    },
    private val terminalToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
    },
    private val deviceDirectToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS)
    },
    private val deviceSensitiveReadToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS)
    },
    private val deviceSensitiveActionToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)
    },
    /**
     * 「会动手」的 GUI 工具是否可用（打开应用／点屏／输入／按键）。
     *
     * 只读场景（定时任务的「只读分析」）会传 false：目录层已经把工具摘掉，
     * 这里再拦一次，防止模型凭历史工具名、MCP 或子代理绕过去。
     */
    private val interactionToolsEnabled: () -> Boolean = { true },
    private val memoryToolsEnabled: () -> Boolean = {
        runBlocking { AgentMemoryRepository.isEnabled() }
    },
    private val memoryWritable: Boolean = true,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val screenObservationProvider: (
        (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
    )? = null,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
    private val skillIndexService: SkillIndexService? = null,
    private val skillLoader: SkillLoader? = null,
    private val skillResourceReader: SkillResourceReader? = null,
    private val githubSkillSource: PublicGitHubSkillSource? = null,
    private val skillPackageInstaller: SkillPackageInstaller? = null,
    runAvailableSkillIds: Set<String> = emptySet(),
    pendingSkillConflict: PendingSkillConflictCapability? = null,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val virtualDisplayEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED)
    },
    private val lsposedAvailable: () -> Boolean = { EtaApp.serviceInstance != null },
    private val guiControl: GuiControlCoordinator.RunControl? = null,
    browserSession: AgentBrowserSession? = null,
) : AgentModelClient.ToolExecutor, AutoCloseable {

    private val closed = AtomicBoolean(false)
    @Volatile
    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY
    private val browserSessionRef = lazy { browserSession ?: AgentBrowserSession() }
    private val deviceController = RootShellDeviceController(logger, screenshotExcludedPackages, rootAvailable)
    private val rootCommandExecutor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
    private val structuredDeviceTools = AgentStructuredDeviceTools(
        context = context,
        logger = logger,
        root = rootCommandExecutor,
        rootAvailable = rootAvailable,
        foregroundAction = ::executeForegroundIntent,
    )
    private val imageTools = AgentImageTools(context, rootCommandExecutor, rootAvailable)
    private val screenshotTools = AgentScreenshotTools(context, deviceController, ::currentActiveDisplayId)
    private val webTools = AgentWebTools(
        transport = WebHttpTransport(AgentHttpClient.client),
        localNetworkAccess = { LocalNetworkPermission.accessState(context).name.lowercase(Locale.ROOT) },
        runtimeConfig = { runBlocking { WebToolsSettingsRepository.resolveRuntimeConfig(context) } },
    )
    val terminalExecutionContext = LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment.let { environment ->
        TerminalExecutionContext(
            linuxEnvironment = environment,
            linuxRootfsPath = LinuxEnvironmentPaths.rootfsDir(context, checkNotNull(environment.linuxDistribution)).absolutePath,
            sharedMounts = SharedFolderMounts.current(),
        )
    }
    private val terminalController = RootShellTerminalController(
        logger = logger,
        rootAvailable = rootAvailable,
        executionContext = terminalExecutionContext,
        backgroundJobs = io.github.mangi.eta.agent.terminal.TerminalRuntime.backgroundJobs(context),
        detachedSupervisor = DetachedTaskSupervisor(
            logger = logger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
            linuxRootfsPath = terminalExecutionContext.linuxRootfsPath,
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
            rootAvailable = rootAvailable,
        ),
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )

    private val publishedObservation = AtomicReference(PublishedObservation())
    private val runAvailableSkillIds = runAvailableSkillIds
        .mapTo(mutableSetOf(), SkillParser::normalizeSkillLookup)
    private val mutatedSkillIds = ConcurrentHashMap.newKeySet<String>()
    private val skillTreeMutationUncertain = AtomicBoolean(false)
    private val pendingSkillConflict = AtomicReference(pendingSkillConflict)
    private val inspectedGitHubSnapshots =
        ConcurrentHashMap<String, GitHubInspectionSnapshot>()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        publishedObservation.set(PublishedObservation())
        if (browserSessionRef.isInitialized()) browserSessionRef.value.interruptAgentAction(browserRunId)
        webTools.close()
        terminalController.interruptAll()
        deviceController.close()
        rootCommandExecutor.close()
        githubSkillSource?.close()
        inspectedGitHubSnapshots.clear()
        guiControl?.close()
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (closed.get()) throw AgentRunCancelledException()
        val control = guiControl
        val result = if (control != null && AgentToolRequirements.requiresGuiControl(toolCall.name)) {
            control.withAction { generation -> executeLocal(toolCall, generation) }
        } else {
            executeLocal(toolCall, null)
        }
        // 失败自动归档：SKILL.md 承诺过「运行时自动记录」，但此前没实现。
        // 放在这里而不是各个分支里，是为了覆盖所有失败路径，且绝不影响返回值。
        recordFailureIfNeeded(toolCall, result)
        return result
    }

    /** 只在结果确实是失败时写日志；[SkillErrorJournal] 内部自己判 ok/code 并做限流。 */
    private fun recordFailureIfNeeded(
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_SKILL_ERROR_JOURNAL)) return
        SkillErrorJournal.record(
            context = context,
            logger = logger,
            toolName = toolCall.name,
            argumentsJson = toolCall.argumentsJson,
            rawResult = result.content,
        )
    }

    private fun executeLocal(
        toolCall: AgentModelClient.ToolCall,
        guiGeneration: Long?,
    ): AgentModelClient.ToolResult =
        runCatching {
            if (closed.get()) throw AgentRunCancelledException()
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            if (AgentToolRequirements.find(toolCall.name) != null &&
                AgentToolRequirements.rootDenied(toolCall.name, args, rootAvailable())
            ) {
                return@runCatching textResult(errorResult("ROOT_REQUIRED", "此操作需要 Root 授权，本次未执行"))
            }
            deviceToolPermissionError(toolCall.name)?.let { return@runCatching it }
            memoryToolPermissionError(toolCall.name)?.let { return@runCatching it }
            if (guiGeneration != null && publishedObservation.get().generation != guiGeneration) {
                publishedObservation.set(PublishedObservation())
                if (AgentToolRequirements.requiresFreshObservation(toolCall.name)) {
                    return@runCatching textResult(errorResult(
                        "NO_OBSERVATION", "手机操作权已变化，请先调用 observe_screen 重新观察，再操作当前页面",
                    ))
                }
            }
            val preparation = if (AgentToolRequirements.hasForegroundIntentFallback(toolCall.name)) {
                ToolExecutionDecision.Allow
            } else {
                beforeToolExecution(toolCall.name)
            }
            when (val decision = preparation) {
                ToolExecutionDecision.Allow -> Unit
                is ToolExecutionDecision.Reject -> {
                    if (decision.code.startsWith("ACCESSIBILITY_")) publishedObservation.set(PublishedObservation())
                    return@runCatching textResult(
                        errorResult(
                            code = decision.code,
                            message = decision.message,
                        ),
                    )
                }
            }
            // 危险操作闸门：命中策略就挂起等用户选；超时或用户拒绝都按拒绝处理。
            val dangerous = DangerousOperationPolicy.evaluate(toolCall.name, args)
            if (dangerous.dangerous) {
                val decision = DangerousOperationGuard.requestApproval(
                    toolName = toolCall.name,
                    reason = dangerous.reason,
                    rule = dangerous.rule,
                    impact = dangerous.impact,
                    recovery = dangerous.recovery,
                    critical = dangerous.severity == DangerousOperationPolicy.Severity.CRITICAL,
                    detail = dangerousDetail(toolCall.name, args),
                    // 这里是系统级危险，规则粒度可控（如「写入系统启动链目录」），允许本会话放行。
                    allowSessionOption = true,
                )
                DangerousOperationGuard.log(context, toolCall.name, dangerous.reason, decision)
                if (decision == DangerousOperationGuard.Decision.DENY) {
                    return@runCatching textResult(
                        errorResult(
                            code = "DANGEROUS_OPERATION_DENIED",
                            message = "用户没有批准这个操作（${dangerous.reason}）。" +
                                "不要原样重试，也不要换个写法绕过去；" +
                                "改用不触碰系统分区的做法，或者先跟用户说清你要做什么、为什么非这样做不可。",
                        ),
                    )
                }
            }
            when (toolCall.name) {
                "get_current_context" -> textResult(DeviceContextTool.current(context))
                "search_apps" -> textResult(searchApps(args))
                "launch_app" -> {
                    publishedObservation.set(PublishedObservation())
                    textResult(launchApp(args))
                }
                "present_to_user" -> {
                    publishedObservation.set(PublishedObservation())
                    textResult(presentToUser(args))
                }
                "open_uri" -> {
                    publishedObservation.set(PublishedObservation())
                    textResult(openUri(args))
                }
                "browser_use" -> browserUse(args, toolCall.id)
                "web_search", "fetch_url" -> {
                    if (!browserToolsEnabled()) textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页搜索、读取与浏览器工具"))
                    else textResult(webTools.execute(toolCall.name, args))
                }
                "observe_screen" -> observeScreen(args, guiGeneration)
                "release_gui" -> {
                    publishedObservation.set(PublishedObservation())
                    guiControl?.release()
                    textResult(JSONObject().put("ok", true).put("tool", "release_gui").toString())
                }
                "tap" -> afterAction(tap(args))
                "tap_area" -> afterAction(tapArea(args))
                "tap_element" -> afterAction(tapElement(args))
                "long_press" -> afterAction(longPress(args))
                "long_press_element" -> afterAction(longPressElement(args))
                "swipe" -> afterAction(swipe(args))
                "scroll" -> afterAction(deviceController.scroll(args.optString("direction"), args.optString("amount"), currentActiveDisplayId()))
                "scroll_element" -> afterAction(scrollElement(args))
                "type_text" -> afterAction(typeText(args))
                // 旧会话或旧入口仍可能请求这些工具名；保留执行路径，但不再出现在模型目录里。
                "input_text" -> afterAction(inputText(args))
                "replace_text" -> afterAction(replaceText(args))
                "clear_text" -> afterAction(clearText(args))
                "set_clipboard" -> textResult(setClipboard(args))
                "get_clipboard" -> textResult(getClipboard())
                "paste_text" -> afterAction(pasteText(args))
                "press_key" -> afterAction(deviceController.pressKey(args.optString("button"), currentActiveDisplayId()))
                "wait" -> textResult(deviceController.waitMs(args.optInt("duration_ms", 1_000)))
                "wait_for_text" -> textResult(waitForText(args))
                "wait_for_package" -> textResult(waitForPackage(args))
                "gui_macro" -> guiMacro(args)
                "ask_user" -> textResult(askUser(args))
                "open_system_panel" -> {
                    publishedObservation.set(PublishedObservation())
                    activeDisplayId = Display.DEFAULT_DISPLAY
                    textResult(deviceController.openSystemPanel(args.optString("panel")))
                }
                // 三个管理工具必须排在 `in DEVICE_TOOL_NAMES` **之前**。
                // 它们被登记进 DEVICE_SENSITIVE_ACTION_TOOL_NAMES（只读模式要挡住），
                // 而 DEVICE_TOOL_NAMES = DIRECT + SENSITIVE_READ + SENSITIVE_ACTION ——
                // 放在后面会被那个分支先命中，落到 structuredDeviceTools 里
                // 返回 UNKNOWN_TOOL（2026-11-09 真机实测踩到）。
                "manage_scheduled_task" -> textResult(manageScheduledTask(args))
                "manage_provider" -> textResult(manageProvider(args))
                "manage_mcp" -> textResult(manageMcp(args))
                in DEVICE_TOOL_NAMES ->
                    structuredDeviceTools.execute(toolCall.name, args)
                        ?: textResult(errorResult("UNKNOWN_TOOL", "未知设备工具"))
                "suggest_scheduled_tasks" -> textResult(suggestScheduledTasks())
                "read_app_logs" -> textResult(readAppLogs(args))
                "share_folder" -> textResult(shareFolder(args))
                "read_image" -> fileVisionTool { imageTools.readImage(args) }
                "save_screenshot" -> screenshotTools.saveScreenshot(args)
                in AgentTerminalToolCatalog.names -> textResult(terminalTool {
                    if (toolCall.name == "terminal" && args.optString("action").equals("check_root", ignoreCase = true)) checkRoot()
                    else terminalController.executeTool(toolCall.name, args)
                })
                in AgentFileToolCatalog.names -> textResult(terminalTool { terminalController.fileTool(toolCall.name, args) })
                "memory_get" -> textResult(memoryGet(args))
                "memory_write" -> textResult(memoryWrite(args))
                "skills_list" -> textResult(skillsList(args))
                "skills_read" -> textResult(skillsRead(args))
                "skills_read_resource" -> textResult(skillsReadResource(args))
                "skills_list_curated" -> textResult(skillsListCurated())
                "skills_inspect_github" -> textResult(skillsInspectGitHub(args))
                "skills_install_from_github" -> textResult(skillsInstallFromGitHub(args))
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${toolCall.name}"
                    )
                )
            }
        }.getOrElse { throwable ->
            if (throwable is AgentRunCancelledException) throw throwable
            textResult(
                errorResult(
                    code = when (throwable) {
                        is InvalidToolArgumentException, is org.json.JSONException -> "INVALID_ARGUMENT"
                        is DeviceControlUnavailableException -> "ACCESSIBILITY_UNAVAILABLE"
                        else -> "TOOL_ERROR"
                    },
                    message = throwable.message ?: throwable.javaClass.simpleName
                )
            )
        }.let { result ->
            if (result.sensitive || !AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                result
            } else {
                result.copy(sensitive = true)
            }
        }

    private fun executeForegroundIntent(toolName: String, action: () -> JSONObject): JSONObject {
        fun execute(): JSONObject {
            if (closed.get()) throw AgentRunCancelledException()
            deviceToolPermissionError(toolName)?.let { return JSONObject(it.content) }
            return when (val decision = beforeToolExecution(toolName)) {
                ToolExecutionDecision.Allow -> {
                    publishedObservation.set(PublishedObservation())
                    action()
                }
                is ToolExecutionDecision.Reject -> JSONObject(errorResult(decision.code, decision.message))
            }
        }
        return guiControl?.withAction { execute() } ?: execute()
    }

    private fun deviceToolPermissionError(
        toolName: String,
    ): AgentModelClient.ToolResult? {
        val error = when {
            toolName in DEVICE_DIRECT_TOOL_NAMES && !deviceDirectToolsEnabled() ->
                "DEVICE_DIRECT_TOOLS_DISABLED" to "请先启用设备直达工具"
            toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES && !deviceSensitiveReadToolsEnabled() ->
                "DEVICE_SENSITIVE_READ_TOOLS_DISABLED" to "请先允许读取敏感设备信息"
            toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES && !deviceSensitiveActionToolsEnabled() ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作"
            toolName in io.github.mangi.eta.agent.model.AgentToolCatalog.INTERACTION_TOOL_NAMES &&
                !interactionToolsEnabled() ->
                "INTERACTION_TOOLS_DISABLED" to "当前为只读模式：不能打开应用、点击屏幕或输入文字"
            else -> null
        } ?: return null
        return AgentModelClient.ToolResult(
            content = errorResult(error.first, error.second),
            sensitive = toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES ||
                toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES,
        )
    }

    private fun terminalTool(block: () -> String): String {
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        return block()
    }

    private fun fileVisionTool(block: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!terminalToolsEnabled()) {
            return textResult(errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具"))
        }
        return block()
    }

    private fun memoryToolPermissionError(toolName: String): AgentModelClient.ToolResult? {
        if (toolName == "memory_write" && !memoryWritable) {
            return AgentModelClient.ToolResult(
                content = errorResult("REAL_MEMORY_READ_ONLY", "角色会话的现实记忆只读；剧情请使用角色记忆工具"),
                sensitive = true,
            )
        }
        if (toolName !in MEMORY_TOOL_NAMES || memoryToolsEnabled()) return null
        return AgentModelClient.ToolResult(
            content = errorResult("MEMORY_DISABLED", "记忆已在设置中关闭"),
            sensitive = true,
        )
    }

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memoryWrite(args: JSONObject): String = try {
        val revision = args.getString("revision")
        val mutation = when (args.getString("mode")) {
            "replace_range" -> AgentMemoryMutation.ReplaceRange(
                revision = revision,
                startLine = args.getInt("start_line"),
                endLine = args.getInt("end_line"),
                content = args.requiredText("content"),
            )
            "append" -> AgentMemoryMutation.Append(
                revision = revision,
                content = args.requiredText("content"),
            )
            "clear" -> AgentMemoryMutation.Clear(revision)
            else -> error("不支持的记忆写入模式")
        }
        // [状态] 必须带日期，否则过期提示机制空转。只校验本次写入的片段，不回溯历史正文。
        val segment = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> mutation.content
            is AgentMemoryMutation.Append -> mutation.content
            else -> ""
        }
        AgentMemoryValidator.validate(segment)?.let { violation ->
            return errorResult(
                "MEMORY_STATUS_MISSING_DATE",
                "${violation.message}。${violation.hint}",
            )
        }
        when (val result = AgentMemoryRepository.mutate(mutation)) {
            is AgentMemoryWriteResult.Success -> JSONObject()
                .put("ok", true)
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
            is AgentMemoryWriteResult.Conflict -> JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_CONFLICT")
                .put("message", "记忆已发生变化，请先调用 memory_get 获取最新内容")
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
        }
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆写入失败")
    }

    private fun browserUse(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        val result = browserSessionRef.value.execute(
            context = context,
            args = args,
            runId = browserRunId,
            toolCallId = toolCallId,
            checkCancellation = { if (closed.get()) throw AgentRunCancelledException() },
        )
        return AgentModelClient.ToolResult(
            content = result.content,
            images = result.images.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = "agent_browser",
                    preserveOriginal = true,
                )
            },
        )
    }

    private fun currentActiveDisplayId(): Int {
        val id = activeDisplayId
        if (!CanvasRoutingPolicy.isVirtualDisplay(id)) return Display.DEFAULT_DISPLAY
        val vd = VirtualDisplayController.snapshots.value
        return if (vd.active && vd.displayId == id) {
            id
        } else {
            activeDisplayId = Display.DEFAULT_DISPLAY
            Display.DEFAULT_DISPLAY
        }
    }

    private fun observeScreen(args: JSONObject, generation: Long?): AgentModelClient.ToolResult {
        publishedObservation.set(PublishedObservation())
        val startedAt = SystemClock.elapsedRealtime()
        val options = AgentScreenObservationContract.resolve(args)
        val displayId = currentActiveDisplayId()
        val observation = screenObservationProvider?.invoke(options)
            ?: deviceController.observe(
                includeScreenshot = options.includeScreenshot,
                includeUiTree = options.includeUiTree,
                maxNodes = options.maxNodes,
                displayId = displayId,
            )
        publishedObservation.set(
            PublishedObservation(
                elements = observation.elementObservation,
                coordinateSpace = observation.coordinateSpace,
                generation = generation.takeIf { JSONObject(observation.content).optBoolean("ok") },
            ),
        )
        logger.debug {
            "Agent local tool action=observe_screen outcome=completed " +
                "display=$displayId observation=${observation.elementObservation?.id} " +
                "nodes=${observation.elementObservation?.nodes?.size ?: 0} " +
                "image=${observation.image?.bytes ?: 0} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "coordinate=${observation.coordinateSpace?.summary()}"
        }
        return AgentModelClient.ToolResult(
            content = observation.content,
            images = listOfNotNull(observation.image)
        )
    }

    /**
     * 成功的 GUI 动作附带一次轻量观察：只读 UI 树、不截图，节点数减半。
     * 模型据此确认动作生效并直接用新的 observation_id 继续操作，不必再单独 observe_screen；
     * 失败或结果未知的动作不附带，保持"先重新观察"的既有约束。
     */
    private fun afterAction(raw: String): AgentModelClient.ToolResult {
        if (closed.get()) throw AgentRunCancelledException()
        val result = runCatching { JSONObject(raw) }.getOrNull()
        if (result == null || !result.optBoolean("ok")) return textResult(raw)
        val before = publishedObservation.get().elements
        val displayId = currentActiveDisplayId()
        // 等布局稳定再抓：动画中途抓到的快照坐标会整体偏一屏，模型拿它去点必然白烧一轮。
        val elements = runCatching {
            settledObservation(displayId, AFTER_ACTION_SETTLE_MAX_MS)
        }.getOrElse { throwable ->
            logger.debug { "Agent local tool after-action observation failed: type=${throwable.javaClass.simpleName}" }
            return textResult(raw)
        } ?: return textResult(raw)
        // 新快照取代动作前的快照：旧 index 在界面变化后本就不可靠，继续保留只会让模型误用。
        publishedObservation.set(publishedObservation.get().copy(elements = elements))
        result.put("after", AgentAfterActionSummary.build(before, elements))
        return textResult(result.toString())
    }

    private fun tap(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        return clearOverlay(displayId, point) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            }
            showTap(point.x, point.y, displayId)
            deviceController.tap(point.x, point.y, displayId)
        }
    }

    private fun tapArea(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val x1 = args.getInt("x1")
        val y1 = args.getInt("y1")
        val x2 = args.getInt("x2")
        val y2 = args.getInt("y2")
        val coordinateSpace = args.optString("coordinate_space")
        val first = convertPoint(x1, y1, coordinateSpace, displayId)
        val second = convertPoint(x2, y2, coordinateSpace, displayId)
        val point = ScreenPoint(
            x = ((first.x.toLong() + second.x.toLong()) / 2L).toInt(),
            y = ((first.y.toLong() + second.y.toLong()) / 2L).toInt(),
        )
        return clearOverlay(displayId, point) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            }
            showTap(point.x, point.y, displayId)
            deviceController.tap(point.x, point.y, displayId)
        }
    }

    private fun tapElement(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        // 不可逆文案（删除/支付/卸载…）先确认：我认错一行也点不下去。
        uiRiskDenial("tap_element", node.text, node.desc)?.let { denial -> return denial }
        val result = clearOverlay(displayId, ScreenPoint(node.centerX, node.centerY)) {
            deviceController.tapElement(observation, index)
        }
        if (result.isOkJson()) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            }
            showTap(node.centerX, node.centerY, displayId)
        }
        return result
    }

    private fun longPressElement(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        val durationMs = args.optInt("duration_ms", 800)
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        uiRiskDenial("long_press_element", node.text, node.desc)?.let { denial -> return denial }
        val result = clearOverlay(displayId, ScreenPoint(node.centerX, node.centerY)) {
            deviceController.longPressElement(observation, index, durationMs)
        }
        if (result.isOkJson()) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            }
            showLongPress(node.centerX, node.centerY, durationMs, displayId)
        }
        return result
    }

    private fun longPress(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val point = convertPoint(
            x = args.getInt("x"),
            y = args.getInt("y"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 800)
        return clearOverlay(displayId, point) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            }
            showLongPress(point.x, point.y, durationMs, displayId)
            deviceController.longPress(point.x, point.y, durationMs, displayId)
        }
    }

    private fun swipe(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val start = convertPoint(
            x = args.getInt("x1"),
            y = args.getInt("y1"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val end = convertPoint(
            x = args.getInt("x2"),
            y = args.getInt("y2"),
            coordinateSpace = args.optString("coordinate_space"),
            displayId = displayId,
        )
        val durationMs = args.optInt("duration_ms", 500)
        return clearOverlay(displayId, start, end) {
            if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
            }
            showSwipe(start.x, start.y, end.x, end.y, durationMs, displayId)
            deviceController.swipe(
                start.x,
                start.y,
                end.x,
                end.y,
                durationMs,
                displayId,
            )
        }
    }

    private fun scrollElement(args: JSONObject): String {
        val observation = requireElementObservation(args) ?: return observationError(args)
        return deviceController.scrollElement(
            observation = observation,
            index = args.optInt("index", -1),
            direction = args.optString("direction"),
            amount = args.optString("amount"),
        )
    }

    /**
     * 统一的文本输入：replace（默认）整体替换为 text，text 为空即清空；append 在光标处插入。
     * 指定 index 时先确认节点来自最近一次观察；不指定时作用于当前输入焦点。
     * submit=true 在写入成功后按输入法回车，用于搜索、发送等提交动作。
     */
    private fun typeText(args: JSONObject): String {
        val displayId = currentActiveDisplayId()
        val text = args.requiredText("text")
        val mode = args.optString("mode", "replace").trim().lowercase(Locale.ROOT).ifBlank { "replace" }
        val written = when (mode) {
            "replace" -> replaceText(args)
            "append" -> {
                if (text.isEmpty()) return errorResult("INVALID_ARGUMENT", "append 模式的 text 不能为空")
                if (args.optNullableInt("index") != null) {
                    return errorResult("INVALID_ARGUMENT", "append 只作用于当前输入焦点；要写入指定输入框请用 mode=replace")
                }
                // 长文本走选区插入加粘贴回退，比逐字键入更稳定。
                if (text.length > TYPE_TEXT_INCREMENTAL_CHARS) pasteText(args) else deviceController.inputText(text, displayId)
            }
            else -> return errorResult("INVALID_ARGUMENT", "mode 只能是 replace 或 append")
        }
        val json = runCatching { JSONObject(written) }.getOrNull() ?: return written
        json.put("tool", "type_text").put("mode", mode)
        if (!json.optBoolean("ok") || !args.optBoolean("submit")) return json.toString()
        val submitted = runCatching { JSONObject(deviceController.pressKey("ENTER", displayId)) }.getOrNull()
        json.put("submitted", submitted?.optBoolean("ok") == true)
        if (submitted?.optBoolean("ok") != true) {
            // 文本已写入但提交失败：整体仍算成功，避免模型重复输入；由 submitted=false 提示单独补按回车或点按钮。
            json.put("submit_error", submitted?.optString("code").orEmpty().ifBlank { "SUBMIT_FAILED" })
        }
        return json.toString()
    }

    private fun inputText(args: JSONObject): String {
        val text = args.requiredText("text")
        if (text.length > 1_000) {
            return errorResult("TEXT_TOO_LONG", "input_text 最多支持 1000 个字符")
        }
        return when (args.optString("mode", "append").lowercase(Locale.ROOT)) {
            "replace" -> replaceText(args)
            "paste" -> pasteText(args)
            else -> deviceController.inputText(text, currentActiveDisplayId())
        }
    }

    private fun replaceText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.replaceText(
            text = args.requiredText("text"),
            index = index,
            observation = observation,
            displayId = currentActiveDisplayId(),
        )
    }

    private fun clearText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.clearText(index = index, observation = observation, displayId = currentActiveDisplayId())
    }

    private fun setClipboard(args: JSONObject): String =
        deviceController.clipboardSet(requireContext(), args.requiredText("text"))

    private fun getClipboard(): String =
        deviceController.clipboardGet(requireContext())

    private fun pasteText(args: JSONObject): String =
        deviceController.pasteText(args.requiredText("text"), currentActiveDisplayId())

    private fun waitForText(args: JSONObject): String =
        deviceController.waitForText(
            text = args.requiredText("text"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            includeDesc = args.optBoolean("include_desc", true),
            matchMode = args.optString("match", "contains"),
            displayId = currentActiveDisplayId(),
        )

    /**
     * 「不确定时先问」：把问题丢给用户，**阻塞等确认**。
     *
     * 复用危险操作那道确认卡片（[DangerousOperationGuard]）：点「继续」= 同意，
     * 「取消」或超时无人应答 = 否决。**超时按否决是刻意的**：宁可不做，
     * 也不要靠猜往下推（用户原话：你们有时候不会问我）。
     */
    private fun askUser(args: JSONObject): String {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_ASK_BEFORE_ASSUME)) {
            return errorResult(
                "ASK_USER_DISABLED",
                "「不确定时先问」没开（设置 → 智能体）。请按最保守的方式继续，并在结论里写明你的假设",
            )
        }
        val question = args.optString("question").trim().ifBlank {
            return errorResult("INVALID_ARGUMENT", "question 不能为空：写清「现状 + 两个可选方向 + 你建议哪个」")
        }
        if (question.length > MAX_ASK_CHARS) {
            return errorResult("INVALID_ARGUMENT", "question 太长（上限 $MAX_ASK_CHARS 字符）")
        }
        val decision = DangerousOperationGuard.requestApproval(ASK_USER_TOOL, question)
        DangerousOperationGuard.log(context, ASK_USER_TOOL, question, decision)
        // ask_user 只有「同意 / 否决」两种语义，不提供「这类别问了」——问答不该被静默跳过。
        val approved = decision != DangerousOperationGuard.Decision.DENY
        return JSONObject()
            .put("ok", true)
            .put("tool", ASK_USER_TOOL)
            .put("answer", if (approved) "approved" else "denied")
            .put(
                "message",
                if (approved) {
                    "用户点了「继续」：可以按你问的方案往下做。"
                } else {
                    "用户点了「取消」或没回应：不要执行这一步。" +
                        "改成在最终回复里只写这个疑问、两个可选方案和你的建议，等用户回答。"
                },
            )
            .toString()
    }

    /**
     * 界面风险闸门：命中「删除／支付」这类不可逆文案就先确认，超时按否决。
     *
     * 返回 null 表示放行；非 null 是要回给模型的拒绝结果。
     * 这一层和 [DangerousOperationPolicy] 互补：那个看命令，这个看**要点的控件文案**。
     */
    private fun uiRiskDenial(toolName: String, vararg labels: String): String? {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_UI_RISK_GUARD)) return null
        val reason = UiRiskPolicy.evaluate(labels.toList()) ?: return null
        val decision = DangerousOperationGuard.requestApproval(toolName, reason)
        DangerousOperationGuard.log(context, toolName, reason, decision)
        // UI 风险（「删除」这类按钮文案）粒度太粗，记住它等于以后所有删除都不再拦，所以不给「这类别问了」。
        val approved = decision != DangerousOperationGuard.Decision.DENY
        if (approved) return null
        return errorResult(
            "UI_RISK_DENIED",
            "该界面动作被判定为不可逆（$reason），用户未确认，已拒绝执行。" +
                "如果确实要做，让用户自己点，或先用 ask_user 说清楚再问。",
        )
    }

    private fun waitForPackage(args: JSONObject): String =
        deviceController.waitForPackage(
            packageName = args.optString("package_name"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            displayId = currentActiveDisplayId(),
        )

    /**
     * GUI 宏：一次调用按顺序执行一串界面动作，**每一步都在设备侧本地校验**。
     *
     * 为什么做：实测「手上」一次点击只要 50–100ms，但两步之间隔着一次模型回合（约 4 秒），
     * 所以十步流程 ≈ 40 秒，其中 39 秒不是在点。宏把「点 A → 等文字出现 → 点 B → 滚一下」
     * 这类固定流程压成一次调用，十步 ≈ 1–2 秒。
     *
     * 安全边界（每条都对应一个真实事故形态）：
     * 1. **每步校验**：会动界面的步骤执行后，界面在 900ms 内毫无变化就立刻中止并回报现场——
     *    不做校验的盲点序列就是这个项目踩过的坑（点错地方的代价远大于多点一次观察）。
     * 2. **能用文字就用文字**：`tap_text` 按文本找节点、可点击的优先，不依赖上一次快照的 index。
     * 3. **上限**：最多 [AgentGuiMacroSpec.MAX_STEPS] 步、整体 60 秒，超了在动手前就报参数错误。
     * 4. **中止也带回快照**：不管成功还是中止，`after` 都附上当前界面，模型不用再补一次 observe_screen。
     * 5. **先解析后执行**：参数一次全验，模型写错一步不会「点了一半才发现」。
     */
    private fun guiMacro(args: JSONObject): AgentModelClient.ToolResult {
        val steps = when (val plan = AgentGuiMacroSpec.parse(args)) {
            is GuiMacroParse.Invalid -> return textResult(errorResult(plan.code, plan.message))
            is GuiMacroParse.Ok -> plan.steps
        }
        val displayId = currentActiveDisplayId()
        val before = publishedObservation.get().elements
        val startedAt = SystemClock.elapsedRealtime()
        val stepTimings = JSONArray()
        var completed = 0
        var failure: MacroStepOutcome.Failed? = null
        val unverifiedSteps = JSONArray()
        for (step in steps) {
            if (closed.get()) throw AgentRunCancelledException()
            val elapsedBeforeStep = SystemClock.elapsedRealtime() - startedAt
            if (elapsedBeforeStep > AgentGuiMacroSpec.MAX_TOTAL_MS) {
                failure = MacroStepOutcome.Failed(
                    "MACRO_TIMEOUT",
                    "整个宏超过 ${AgentGuiMacroSpec.MAX_TOTAL_MS / 1000} 秒已中止；已完成的 $completed 步有效",
                )
                break
            }
            // 虚拟屏可能中途被回收、或用户切了画布；displayId 一变就必须停 ——
            // 否则会出现「在 A 屏观察、在 B 屏点下去」这种最典型的错屏误点。
            val stepDisplayId = currentActiveDisplayId()
            if (stepDisplayId != displayId) {
                failure = MacroStepOutcome.Failed(
                    "DISPLAY_CHANGED",
                    "宏执行期间目标屏幕变了（$displayId → $stepDisplayId），已中止",
                )
                break
            }
            // 单步等待不能超过整体剩余预算，否则「整体 60 秒」形同虚设。
            val remainingBudget = (AgentGuiMacroSpec.MAX_TOTAL_MS - elapsedBeforeStep)
                .coerceAtMost(AgentGuiMacroSpec.MAX_STEP_TIMEOUT_MS.toLong())
                .toInt()
            val stepStarted = SystemClock.elapsedRealtime()
            val outcome = runMacroStep(step, displayId, remainingBudget)
            stepTimings.put(
                JSONObject()
                    .put("step", step.position + 1)
                    .put("action", step.action)
                    .put("ms", SystemClock.elapsedRealtime() - stepStarted)
                    .put("ok", outcome !is MacroStepOutcome.Failed),
            )
            if (outcome is MacroStepOutcome.Failed) {
                failure = outcome
                break
            }
            if (outcome is MacroStepOutcome.OkUnverified) {
                unverifiedSteps.put(step.position + 1)
            }
            completed++
        }
        val after = settledObservation(displayId, MACRO_AFTER_SETTLE_MAX_MS)
        if (after != null) {
            // 和单动作保持一致：新快照取代旧快照，避免模型接着用已经失效的 index。
            publishedObservation.set(publishedObservation.get().copy(elements = after))
        }
        val result = JSONObject()
            .put("ok", failure == null)
            .put("tool", "gui_macro")
            .put("steps_done", completed)
            .put("steps_total", steps.size)
            .put("total_ms", SystemClock.elapsedRealtime() - startedAt)
            .put("step_ms", stepTimings)
        failure?.let { failed ->
            result.put("code", failed.code)
            result.put("message", failed.message)
            result.put("failed_step", completed + 1)
        }
        if (unverifiedSteps.length() > 0) {
            // 这些步骤「做了，但没法判断有没有生效」（例如 Canvas 界面没有节点）。明写出来，
            // 比假装通过或假装失败都强：模型知道下一步要不要自己看一眼。
            result.put("unverified_steps", unverifiedSteps)
        }
        after?.let { elements -> result.put("after", AgentAfterActionSummary.build(before, elements)) }
        return textResult(result.toString())
    }

    /** 执行一步：先记前快照（要校验变化的步骤才记），再派发，再校验。 */
    private fun runMacroStep(step: GuiMacroStep, displayId: Int, budgetMs: Int): MacroStepOutcome {
        val beforeSignature = if (step.interacts) {
            observeElements(displayId)?.nodes?.let { nodes -> AgentAfterActionSummary.signature(nodes) }
        } else {
            null
        }
        if (step.action == AgentGuiMacroSpec.ACTION_TAP_TEXT) {
            // 按文字定位要自己抓快照，所以单独一条路。
            macroTapText(step, displayId)?.let { failure -> return failure }
            return macroVerify(step, beforeSignature, displayId, budgetMs)
        }
        val raw = try {
            when (step.action) {
                AgentGuiMacroSpec.ACTION_TAP_ELEMENT -> tapElement(step.raw)
                "tap" -> tap(step.raw)
                "long_press" -> longPress(step.raw)
                "scroll" -> deviceController.scroll(step.direction, step.amount, displayId)
                "press_key" -> deviceController.pressKey(step.button, displayId)
                "type_text" -> typeText(step.raw)
                // 不走 waitForText(step.raw)：那条路的 match 区分大小写、timeout 上限 60s，
                // 和宏内其它文字判据不一致，也会把「整体 60 秒」捅穿。
                "wait_text" -> waitForMacroText(step, displayId, budgetMs)
                "wait" -> deviceController.waitMs(step.durationMs.coerceAtMost(budgetMs.coerceAtLeast(100)))
                else -> errorResult("INVALID_ARGUMENT", "未知动作 ${step.action}")
            }
        } catch (cancelled: AgentRunCancelledException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            // 被取消打断时要把中断标志还回去，否则后续 sleep 会立刻返回，取消等于没取消。
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } catch (failure: Exception) {
            return MacroStepOutcome.Failed(
                "STEP_FAILED",
                "${step.action} 执行失败：${failure.message?.take(120).orEmpty()}",
            )
        }
        if (!raw.isOkJson()) {
            val error = runCatching { JSONObject(raw) }.getOrNull()
            return MacroStepOutcome.Failed(
                error?.optString("code").orEmpty().ifBlank { "STEP_FAILED" },
                "${step.action} 失败：${error?.optString("message").orEmpty().ifBlank { "未返回原因" }}",
            )
        }
        return macroVerify(step, beforeSignature, displayId, budgetMs)
    }

    /** 宏内的等文字：判据和 tap_text 同一套（[AgentGuiMacroSpec.matchesText]），超时受整体预算约束。 */
    private fun waitForMacroText(step: GuiMacroStep, displayId: Int, budgetMs: Int): String {
        val timeout = step.timeoutMs.coerceIn(
            AgentGuiMacroSpec.MIN_STEP_TIMEOUT_MS,
            maxOf(AgentGuiMacroSpec.MIN_STEP_TIMEOUT_MS, budgetMs),
        )
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            if (closed.get()) throw AgentRunCancelledException()
            val nodes = observeElements(displayId, MACRO_OBSERVE_OPTIONS)?.nodes
            if (nodes != null && nodes.any { node -> macroNodeMatches(node, step) }) {
                return JSONObject().put("ok", true).put("tool", "wait_text").toString()
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return errorResult("TIMEOUT", "等「${step.text}」超过 ${timeout}ms 还没出现")
            }
            Thread.sleep(MACRO_TAP_TEXT_POLL_MS)
        }
    }

    /**
     * 按文字找节点并点击；返回 null 表示点成功了，非 null 是失败原因。
     *
     * **命中多个就中止，不瞎挑一个**：屏幕上有 5 行都叫「删除」时，随便点第一个就是误操作。
     * 需要消歧时由调用方给 `view_id` 或 `nth`，并在失败信息里把候选列清楚。
     */
    private fun macroTapText(step: GuiMacroStep, displayId: Int): MacroStepOutcome.Failed? {
        val deadline = SystemClock.elapsedRealtime() + step.timeoutMs
        val nthProvided = step.raw.has("nth")
        while (true) {
            if (closed.get()) throw AgentRunCancelledException()
            val observation = observeElements(displayId, MACRO_OBSERVE_OPTIONS)
                ?: return MacroStepOutcome.Failed("NO_OBSERVATION", "拿不到界面快照，无法按文字定位")
            val matched = observation.nodes.filter { node -> macroNodeMatches(node, step) }
            val candidates = if (step.viewId.isNotEmpty()) {
                matched.filter { node -> node.viewId.substringAfterLast('/') == step.viewId }
            } else {
                matched
            }
            if (candidates.isNotEmpty()) {
                val target = when {
                    step.viewId.isNotEmpty() || nthProvided ->
                        candidates.getOrNull(step.nth) ?: return MacroStepOutcome.Failed(
                            "TARGET_NOT_FOUND",
                            "「${step.text}」只有 ${candidates.size} 个候选，取不到 nth=${step.nth}",
                        )

                    candidates.size == 1 -> candidates.first()

                    else -> return MacroStepOutcome.Failed(
                        "AMBIGUOUS_TARGET",
                        "「${step.text}」命中 ${candidates.size} 个节点，为免点错没有点。" +
                            "请补 view_id 或 nth 消歧；候选：" + describeCandidates(candidates),
                    )
                }
                // 宏里更危险：一次调用可能连点好几下，所以命中不可逆文案时整串中止。
                val riskReason = if (Prefs.isEnabled(Prefs.Keys.AGENT_UI_RISK_GUARD)) {
                    UiRiskPolicy.evaluate(listOf(target.text, target.desc))
                } else {
                    null
                }
                if (riskReason != null) {
                    val decision = DangerousOperationGuard.requestApproval("gui_macro", riskReason)
                    DangerousOperationGuard.log(context, "gui_macro", riskReason, decision)
                    // 宏里每一步都可能是「删除」这类文案，粒度太粗，同样不给「这类别问了」。
                    if (decision == DangerousOperationGuard.Decision.DENY) {
                        return MacroStepOutcome.Failed(
                            "UI_RISK_DENIED",
                            "这一步要点的「${target.text.ifBlank { step.text }}」属于不可逆操作（$riskReason），" +
                                "用户没有批准，宏已中止",
                        )
                    }
                }
                val clicked = clearOverlay(displayId, ScreenPoint(target.centerX, target.centerY)) {
                    deviceController.tapElement(observation, target.index)
                }
                if (clicked.isOkJson()) {
                    if (!CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
                        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
                    }
                    showTap(target.centerX, target.centerY, displayId)
                    return null
                }
                val code = runCatching { JSONObject(clicked).optString("code") }.getOrDefault("")
                return MacroStepOutcome.Failed(
                    code.ifBlank { "TAP_FAILED" },
                    "找到「${step.text}」但点不动（${code.ifBlank { "未返回原因" }}）",
                )
            }
            if (step.timeoutMs <= 0 || SystemClock.elapsedRealtime() >= deadline) {
                return MacroStepOutcome.Failed("TARGET_NOT_FOUND", "当前界面上找不到「${step.text}」")
            }
            Thread.sleep(MACRO_TAP_TEXT_POLL_MS)
        }
    }

    /** 把候选节点压成一行，方便模型直接换个 view_id/nth 重发。 */
    private fun describeCandidates(nodes: List<RootShellDeviceController.UiNode>): String =
        nodes.take(5).joinToString("；") { node ->
            val label = node.text.ifBlank { node.desc }.take(24)
            "[${node.index}] ${node.className.substringAfterLast('.')}" +
                (if (node.viewId.isNotBlank()) " #${node.viewId.substringAfterLast('/')}" else "") +
                " 文本=$label 可点击=${node.clickable} 位置=${node.centerX},${node.centerY}"
        }

    private fun macroNodeMatches(node: RootShellDeviceController.UiNode, step: GuiMacroStep): Boolean =
        AgentGuiMacroSpec.matchesText(node.text, step.text, step.match) ||
            (step.includeDesc && AgentGuiMacroSpec.matchesText(node.desc, step.text, step.match))

    /**
     * 单步校验：先看 expect_text 有没有出现，再看界面有没有变化。
     *
     * 变化判定分三态：变了 / 没变 / **无法判定**（界面没有节点，例如 Canvas、视频、地图）。
     * 无法判定时不算失败 —— 否则每个坐标类步骤都会假报「没生效」，模型会被误导着重复点。
     */
    private fun macroVerify(
        step: GuiMacroStep,
        beforeSignature: List<String>?,
        displayId: Int,
        budgetMs: Int,
    ): MacroStepOutcome {
        if (step.expectText.isNotEmpty()) {
            val timeout = (step.timeoutMs.takeIf { it > 0 } ?: AgentGuiMacroSpec.DEFAULT_TEXT_TIMEOUT_MS)
                .coerceAtMost(maxOf(AgentGuiMacroSpec.MIN_STEP_TIMEOUT_MS, budgetMs))
            // 走宏内自己的等文字：match 语义与 tap_text 同一套（否则 expect_text 区分大小写、
            // tap_text 不区分，同一份参数会得出相反结论）。
            val expectation = GuiMacroStep(
                position = step.position,
                action = "wait_text",
                text = step.expectText,
                match = step.match,
                includeDesc = step.includeDesc,
                timeoutMs = timeout,
            )
            if (!waitForMacroText(expectation, displayId, timeout).isOkJson()) {
                return MacroStepOutcome.Failed(
                    "EXPECT_NOT_FOUND",
                    "这一步之后等「${step.expectText}」超过 ${timeout}ms 还没出现；已停在当前界面",
                )
            }
        }
        if (beforeSignature == null || !step.verifyChange) return MacroStepOutcome.Ok
        return when (waitForScreenChange(beforeSignature, displayId)) {
            MacroScreenChange.CHANGED -> MacroStepOutcome.Ok
            MacroScreenChange.UNKNOWN -> MacroStepOutcome.OkUnverified
            MacroScreenChange.UNCHANGED -> MacroStepOutcome.Failed(
                "NO_VISIBLE_CHANGE",
                "${step.action} 执行后界面没有变化，动作可能没生效；已停在当前界面" +
                    "（本来就该没有可见变化的步骤，请设 verify_change=false）",
            )
        }
    }

    private fun waitForScreenChange(before: List<String>, displayId: Int): MacroScreenChange {
        // 动之前就没有节点（Canvas、纯图片界面）：根本没有判据可用，别硬判「没生效」。
        if (before.isEmpty()) return MacroScreenChange.UNKNOWN
        val deadline = SystemClock.elapsedRealtime() + MACRO_CHANGE_TIMEOUT_MS
        var sawNodes = false
        while (true) {
            if (closed.get()) throw AgentRunCancelledException()
            val nodes = observeElements(displayId)?.nodes
            if (nodes != null && nodes.isNotEmpty()) {
                sawNodes = true
                if (AgentAfterActionSummary.signature(nodes) != before) return MacroScreenChange.CHANGED
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                return if (sawNodes) MacroScreenChange.UNCHANGED else MacroScreenChange.UNKNOWN
            }
            Thread.sleep(MACRO_CHANGE_POLL_MS)
        }
    }

    private fun observeElements(
        displayId: Int,
        options: AgentScreenObservationContract.Options = AFTER_ACTION_OPTIONS,
    ): RootShellDeviceController.ElementObservation? = runCatching {
        val observation = screenObservationProvider?.invoke(options)
            ?: deviceController.observe(
                includeScreenshot = options.includeScreenshot,
                includeUiTree = options.includeUiTree,
                maxNodes = options.maxNodes,
                displayId = displayId,
            )
        observation.elementObservation
    }.getOrNull()

    /**
     * 抓一份「布局已经稳定」的快照。
     *
     * 为什么需要：动作后的快照如果正好抓在窗口动画中途，节点坐标会整体偏一屏
     * （2026-11-05 实测：同一节点 after 与重新观察的 x 差 +1240，正好一个虚拟屏宽度），
     * 模型拿它去点就吃到 STALE_CONTENT，白烧一整轮。
     *
     * 判据只看**根节点 bounds**：窗口位移会让它变，而内容刷新（转圈、计时器、光标）不会 ——
     * 所以不会因为界面一直在动而每次都干等满。
     */
    private fun settledObservation(
        displayId: Int,
        maxWaitMs: Long,
    ): RootShellDeviceController.ElementObservation? {
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        var previousKey: String? = null
        var latest: RootShellDeviceController.ElementObservation? = null
        while (true) {
            latest = observeElements(displayId) ?: return latest
            val key = stabilityKey(latest)
            if (key == previousKey) return latest
            previousKey = key
            if (closed.get() || SystemClock.elapsedRealtime() >= deadline) return latest
            Thread.sleep(SETTLE_POLL_MS)
        }
    }

    /**
     * 布局稳定的判据：包名 + 窗口 + 截断标志 + **前三个节点**的 bounds。
     *
     * 为什么不是只看「根节点」：`collectNodes` 会按「有用」过滤（有文本/可点击…），
     * 纯容器 FrameLayout/DecorView 根本不会进快照，`nodes.first()` 其实是第一个「有用」的节点，
     * 长得像时钟、进度条时会永远不稳。取前三个更耐瞬时抖动；`truncated` 参与判断，
     * 避免「只看到前 30 个节点、恰好没变化」被误判成稳定。
     */
    private fun stabilityKey(observation: RootShellDeviceController.ElementObservation): String =
        buildString {
            append(observation.packageName).append('|')
            append(observation.windowId).append('|')
            append(observation.truncated).append('|')
            observation.nodes.take(3).forEach { node ->
                append(node.bounds.toShortString()).append(';')
            }
        }

    /**
     * 坐标系必须显式声明。旧版按"上一次观察是否带截图"隐式切换默认值，模型无从得知，
     * 经常把截图像素当成屏幕像素；缺省时直接报参数错误，让模型在下一步改正。
     */
    private fun convertPoint(
        x: Int,
        y: Int,
        coordinateSpace: String,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): ScreenPoint {
        val space = publishedObservation.get().coordinateSpace
        val requestedSpace = coordinateSpace.trim().lowercase(Locale.ROOT)
        if (requestedSpace.isBlank()) {
            throw InvalidToolArgumentException(
                "缺少 coordinate_space：看截图定位用 normalized（0–999），坐标来自 ui_nodes 用 screen",
            )
        }
        if (requestedSpace == "normalized") {
            if (x !in 0..NORMALIZED_MAX || y !in 0..NORMALIZED_MAX) {
                throw InvalidToolArgumentException("normalized 坐标必须在 0–$NORMALIZED_MAX 之间：($x,$y)")
            }
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions(displayId)
            return ScreenPoint(
                x = (x.toLong() * (width - 1) / NORMALIZED_MAX).toInt(),
                y = (y.toLong() * (height - 1) / NORMALIZED_MAX).toInt(),
            )
        }
        if (requestedSpace == "screen") {
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions(displayId)
            if (x !in 0 until width || y !in 0 until height) {
                throw InvalidToolArgumentException(
                    "屏幕坐标超出范围：($x,$y) not in ${width}x$height",
                )
            }
            return ScreenPoint(x, y)
        }
        if (requestedSpace != "screenshot") {
            throw InvalidToolArgumentException("coordinate_space 只能是 normalized、screen 或 screenshot")
        }
        if (space == null) {
            throw InvalidToolArgumentException(
                "最近一次观察没有附图，没有 screenshot 坐标系；看截图定位请用 normalized，或先带截图重新观察",
            )
        }
        val point = runCatching { space.fromScreenshot(x, y) }
            .getOrElse { throwable ->
                throw InvalidToolArgumentException(
                    throwable.message ?: "截图坐标超出范围",
                )
            }
        return ScreenPoint(point.x, point.y)
    }

    /**
     * 从最近 7 天通知里给出「可以做成定时任务」的候选规则。
     *
     * 纯本地统计：不联网、不调模型、**不创建任何任务**。只返回结构化字段，
     * 文案交给模型自己组织（引擎刻意不产出展示文本，避免中文硬编码进英文界面）。
     */
    /**
     * 读 Eta 自己落盘的诊断日志。
     *
     * 为什么要有这个工具：logcat 的环形缓冲在真机上只留一分钟左右（实测 3201 行 ≈ 56 秒），
     * 出问题回头去看证据早就没了 —— 只能靠 `dumpsys` 快照猜，或者读源码猜路径。
     * 日志文件由 [io.github.mangi.eta.core.DiagnosticLogFiles] 写：最多 6 份、单份 2MB、写了脱敏。
     */
    private fun readAppLogs(args: JSONObject): String {
        val dir = io.github.mangi.eta.core.DiagnosticLogFiles.directoryPath()
        if (dir.isBlank()) {
            return errorResult("LOGS_UNAVAILABLE", "诊断日志还没就绪（进程刚启动或初始化失败）")
        }
        val level = args.optString("level", "W").trim().uppercase().takeIf { it.isNotEmpty() && it[0] in "IWEF" }
            ?.take(1) ?: "W"
        val minutes = args.optInt("minutes", 60).coerceIn(1, 7 * 24 * 60)
        val query = args.optString("query").trim()
        val maxLines = args.optInt("max_lines", 100).coerceIn(1, 500)
        val minRank = "IWEF".indexOf(level).coerceAtLeast(0)
        val since = System.currentTimeMillis() - minutes * 60_000L

        val builder = StringBuilder()
        builder.append("诊断日志目录：").append(dir).append('\n')
        builder.append("筛选：级别 ≥").append(level).append("，最近 ").append(minutes).append(" 分钟")
        if (query.isNotEmpty()) builder.append("，关键词「").append(query).append('」')
        builder.append('\n')

        var collected = 0
        io.github.mangi.eta.core.DiagnosticLogFiles.files().forEach { file ->
            if (collected >= maxLines) return@forEach
            if (file.lastModified() < since) return@forEach
            val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            val picked = ArrayList<String>()
            for (line in lines.asReversed()) {
                if (collected + picked.size >= maxLines) break
                val lineLevel = LOG_LINE_LEVEL.find(line)?.groupValues?.getOrNull(1) ?: continue
                if ("IWEF".indexOf(lineLevel) < minRank) continue
                if (query.isNotEmpty() && !line.contains(query, ignoreCase = true)) continue
                picked += line
            }
            if (picked.isEmpty()) return@forEach
            builder.append('\n').append(file.name).append('\n')
            picked.asReversed().forEach { line -> builder.append("  ").append(line).append('\n') }
            collected += picked.size
        }
        if (collected == 0) builder.append("\n没有匹配的日志行（可能刚装好还没产生日志）")
        return builder.toString()
    }

    private fun suggestScheduledTasks(): String {        val suggestions = runCatching {
            ScheduledSuggestionEngine.suggest(
                samples = NotificationHistoryRepository(context).recentSamples(),
                existingTasks = ScheduledTaskStore.load(context),
                nowMillis = System.currentTimeMillis(),
            )
        }.getOrDefault(emptyList())

        val items = JSONArray()
        suggestions.forEach { suggestion ->
            items.put(
                JSONObject()
                    .put("trigger", suggestion.trigger.id)
                    .put("hour", suggestion.hour)
                    .put("minute", suggestion.minute)
                    .put("keyword", suggestion.keyword)
                    .put("keywords", JSONArray(suggestion.keywords))
                    .put("evidence_days", suggestion.evidenceDays),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "suggest_scheduled_tasks")
            .put("count", items.length())
            .put("items", items)
            .put("hint", "候选规则，不是已创建的任务；需要用户到「定时任务」页点采用才会生效。")
            .toString()
    }

    /**
     * 定时任务的增删改查（2026-11-08 加）。
     *
     * 为什么需要这个工具：以前**只能**在界面里点出来。用户的原话是
     * 「为什么你们不能直接导入定时任务必须要走界面，这个也很奇怪」——
     * 底层 ScheduledTaskStore 一直有 upsert/setEnabled/delete，只是没暴露给模型。
     *
     * 安全边界：
     *  · 只动 Eta 自己的任务表（scheduled_tasks.json），不碰系统闹钟；
     *  · 创建后立即 schedule() 注册，不用再去界面点；
     *  · action=plugin 的任务在无人值守时不能弹确认卡，需要确认的插件会被跳过，
     *    这一点在返回里明确写出来，避免用户以为「设了就会跑」。
     */
    private fun manageScheduledTask(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        if (action.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 action")

        return when (action) {
            "list" -> {
                val tasks = ScheduledTaskStore.load(context)
                val array = JSONArray()
                tasks.forEach { task ->
                    array.put(
                        JSONObject()
                            .put("task_id", task.id)
                            .put("enabled", task.enabled)
                            .put("trigger", task.trigger.id)
                            .put("task_action", task.action.id)
                            .put("hour", task.hour)
                            .put("minute", task.minute)
                            .put("repeat", task.repeat.id)
                            .put("name", task.name)
                            .put("prompt", task.prompt.take(120))
                            .put("plugin_id", task.pluginId)
                            .put("last_run_at", task.lastRunAtMillis ?: JSONObject.NULL),
                    )
                }
                JSONObject()
                    .put("ok", true)
                    .put("tool", "manage_scheduled_task")
                    .put("count", array.length())
                    .put("tasks", array)
                    .toString()
            }

            "create", "update" -> upsertScheduledTask(args, action)

            "set_enabled" -> {
                val id = args.optString("task_id").trim()
                if (id.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 task_id")
                if (!args.has("enabled")) return failureJson("INVALID_ARGUMENT", "缺少 enabled")
                val exists = ScheduledTaskStore.load(context).any { it.id == id }
                if (!exists) return failureJson("TASK_NOT_FOUND", "没有这个任务：$id")
                val enabled = args.optBoolean("enabled")
                ScheduledTaskStore.setEnabled(context, id, enabled)
                ScheduledTaskScheduler.rescheduleAll(context)
                JSONObject()
                    .put("ok", true)
                    .put("tool", "manage_scheduled_task")
                    .put("task_id", id)
                    .put("enabled", enabled)
                    .toString()
            }

            "delete" -> {
                val id = args.optString("task_id").trim()
                if (id.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 task_id")
                val exists = ScheduledTaskStore.load(context).any { it.id == id }
                if (!exists) return failureJson("TASK_NOT_FOUND", "没有这个任务：$id")
                ScheduledTaskStore.delete(context, id)
                ScheduledTaskScheduler.rescheduleAll(context)
                JSONObject()
                    .put("ok", true)
                    .put("tool", "manage_scheduled_task")
                    .put("deleted", id)
                    .toString()
            }

            else -> failureJson("INVALID_ARGUMENT", "不支持的 action：$action")
        }
    }

    /** create / update 共用：update 时保留原任务里没传的字段。 */
    private fun upsertScheduledTask(args: JSONObject, action: String): String {
        val existing = if (action == "update") {
            val id = args.optString("task_id").trim()
            if (id.isBlank()) return failureJson("INVALID_ARGUMENT", "update 需要 task_id")
            ScheduledTaskStore.load(context).firstOrNull { it.id == id }
                ?: return failureJson("TASK_NOT_FOUND", "没有这个任务：$id")
        } else {
            null
        }

        val triggerId = args.optString("trigger").trim().ifBlank { existing?.trigger?.id ?: "daily_time" }
        val trigger = ScheduledTrigger.fromId(triggerId)
            ?: return failureJson("INVALID_ARGUMENT", "不支持的 trigger：$triggerId（可用：daily_time / power_connected / power_disconnected / battery_low / battery_ok / screen_unlocked / wifi_connected / wifi_disconnected / notification_match）")

        val keywordForCheck = if (hasValue(args, "keyword")) args.optString("keyword") else existing?.keyword.orEmpty()

        val actionId = args.optString("task_action").trim()
            .ifBlank { existing?.action?.id ?: ScheduledActionKind.CUSTOM_PROMPT.id }
        val taskAction = ScheduledActionKind.fromId(actionId)
            ?: return failureJson("INVALID_ARGUMENT", "不支持的 task_action：$actionId")

        val hour = if (hasValue(args, "hour")) args.optInt("hour") else existing?.hour ?: 8
        val minute = if (hasValue(args, "minute")) args.optInt("minute") else existing?.minute ?: 0
        if (hour !in 0..23) return failureJson("INVALID_ARGUMENT", "hour 必须是 0-23")
        if (minute !in 0..59) return failureJson("INVALID_ARGUMENT", "minute 必须是 0-59")

        val repeatId = args.optString("repeat").trim().ifBlank { existing?.repeat?.id ?: "daily" }
        val repeat = ScheduledRepeat.fromId(repeatId)
            ?: return failureJson("INVALID_ARGUMENT", "不支持的 repeat：$repeatId")

        val prompt = if (hasValue(args, "prompt")) args.optString("prompt") else existing?.prompt.orEmpty()
        val pluginId = if (hasValue(args, "plugin_id")) args.optString("plugin_id") else existing?.pluginId.orEmpty()
        val pluginArguments =
            if (hasValue(args, "plugin_arguments")) args.optString("plugin_arguments") else existing?.pluginArguments.orEmpty()
        val name = if (hasValue(args, "name")) args.optString("name") else existing?.name.orEmpty()

        // 动作与参数的自洽性检查：与其建出个永远跑不起来的任务，不如当场说清。
        when (taskAction) {
            ScheduledActionKind.CUSTOM_PROMPT -> if (prompt.isBlank()) {
                return failureJson("INVALID_ARGUMENT", "action=custom_prompt 需要 prompt")
            }

            ScheduledActionKind.PLUGIN -> {
                if (pluginId.isBlank()) return failureJson("INVALID_ARGUMENT", "action=plugin 需要 plugin_id")
                if (pluginArguments.isNotBlank()) {
                    runCatching { JSONObject(pluginArguments) }.getOrNull()
                        ?: return failureJson("INVALID_ARGUMENT", "plugin_arguments 必须是 JSON 对象文本")
                }
            }

            else -> Unit
        }
        if (trigger == ScheduledTrigger.NOTIFICATION_MATCH && keywordForCheck.isBlank()) {
            return failureJson(
                "INVALID_ARGUMENT",
                "trigger=notification_match 必须给 keyword，否则这条任务永远不会触发",
            )
        }

        val task = ScheduledTask(
            id = existing?.id ?: ScheduledTaskStore.newId(),
            trigger = trigger,
            action = taskAction,
            enabled = if (hasValue(args, "enabled")) args.optBoolean("enabled") else existing?.enabled ?: true,
            hour = hour,
            minute = minute,
            threshold = if (hasValue(args, "threshold")) args.optInt("threshold") else existing?.threshold ?: 20,
            keyword = keywordForCheck,
            name = name,
            readOnly = existing?.readOnly,
            lastRunAtMillis = existing?.lastRunAtMillis,
            prompt = if (taskAction == ScheduledActionKind.CUSTOM_PROMPT) prompt else "",
            pluginId = if (taskAction == ScheduledActionKind.PLUGIN) pluginId else "",
            pluginArguments = if (taskAction == ScheduledActionKind.PLUGIN) pluginArguments else "",
            conversationId = existing?.conversationId.orEmpty(),
            repeat = repeat,
            // 1=周一 … 7=周日 转成 Calendar 常量（MONDAY=2 … SUNDAY=1）。
            dayOfWeek = if (hasValue(args, "day_of_week")) {
                val d = args.optInt("day_of_week").coerceIn(1, 7)
                if (d == 7) java.util.Calendar.SUNDAY else d + 1
            } else {
                existing?.dayOfWeek ?: java.util.Calendar.MONDAY
            },
            dayOfMonth = if (hasValue(args, "day_of_month")) {
                args.optInt("day_of_month").coerceIn(1, 31)
            } else {
                existing?.dayOfMonth ?: 1
            },
            interval = if (hasValue(args, "interval")) {
                args.optInt("interval").coerceIn(1, 30)
            } else {
                existing?.interval ?: 1
            },
        )

        ScheduledTaskStore.upsert(context, task)
        // 立即注册闹钟：否则要等下次 App 启动或用户进页面才生效，
        // 用户会以为「设了没反应」。
        ScheduledTaskScheduler.rescheduleAll(context)

        val note = if (taskAction == ScheduledActionKind.PLUGIN) {
            "注意：无人值守执行时无法弹确认卡；需要确认的插件会被跳过并写明原因。"
        } else if (taskAction == ScheduledActionKind.CUSTOM_PROMPT) {
            "注意：到点会调用模型，消耗额度。"
        } else {
            ""
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "manage_scheduled_task")
            .put("action", action)
            .put("task_id", task.id)
            .put("enabled", task.enabled)
            .put("trigger", task.trigger.id)
            .put("task_action", task.action.id)
            .put("hour", task.hour)
            .put("minute", task.minute)
            .put("note", note)
            .toString()
    }

    /** `args.has(k)` 对 JSON null 也返回 true；判断"真的传了值"要用这个。 */
    private fun hasValue(args: JSONObject, key: String): Boolean = args.has(key) && !args.isNull(key)

    private fun failureJson(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    /**
     * 模型服务商与模型的增删改查（2026-11-08 加）。
     *
     * 为什么加：用户的原话是「你们对 eta 明显一窍不通，还没实际操控权」。
     * 以前只能看不能改，加模型必须去界面点。底层 ProviderRepository 一直有
     * addProvider/updateProvider/deleteProvider，只是没暴露给模型。
     *
     * 安全设计（用户 2026-11-08 定的口径：**只在真正危险时弹卡，别频繁打扰**）：
     *  · apiKey 永远打码回显，绝不返回原文；
     *  · 删除 provider 走危险确认卡（不可逆）；
     *  · 改 baseUrl / 换 key 这类"会让当前会话突然不可用"的改动，先执行、事后如实报告；
     *  · 每次写入后读回校验，不一致就报错，不假装成功。
     */
    private fun manageProvider(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        if (action.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 action")

        return runBlocking(Dispatchers.IO) {
            // when 各分支都是 String，这里直接返回它（Kotlin 需要显式 return 才认）。
            when (action) {
                "list" -> {
                    val providers = ProviderRepository.allProviders()
                    val settings = ProviderRepository.settings()
                    val array = JSONArray()
                    providers.forEach { provider ->
                        val models = JSONArray()
                        provider.models.forEach { model ->
                            models.put(
                                JSONObject()
                                    .put("model_id", model.modelId)
                                    .put("display_name", model.displayName)
                                    .put("enabled", model.isEnabled)
                                    .put("context_window", model.contextWindow ?: JSONObject.NULL),
                            )
                        }
                        array.put(
                            JSONObject()
                                .put("provider_id", provider.id)
                                .put("name", provider.name)
                                .put("type", provider.sourceType)
                                .put("base_url", provider.baseUrl)
                                .put("api_key", maskSecret(provider.apiKey))
                                .put("enabled", provider.isEnabled)
                                .put("built_in", provider.isBuiltIn)
                                .put("models", models),
                        )
                    }
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "manage_provider")
                        .put("count", array.length())
                        .put("active_provider", settings.selectedProviderId ?: JSONObject.NULL)
                        .put("active_model", settings.selectedModelId ?: JSONObject.NULL)
                        .put("providers", array)
                        .toString()
                }

                "create" -> createProvider(args)
                "update" -> updateProviderConfig(args)
                "delete" -> deleteProviderConfig(args)
                "set_active" -> setActiveModel(args)
                else -> failureJson("INVALID_ARGUMENT", "不支持的 action：$action")
            }
        }
    }

    private suspend fun createProvider(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 name")
        val baseUrl = args.optString("base_url").trim()
        if (baseUrl.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 base_url")
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return failureJson("INVALID_ARGUMENT", "base_url 必须以 http(s):// 开头")
        }
        val type = args.optString("type").trim().ifBlank { ProviderTypes.OPENAI_COMPATIBLE }
        val apiKey = args.optString("api_key")
        val models = parseModelList(args.optJSONArray("models"))
            ?: return failureJson("INVALID_ARGUMENT", "models 格式不对（需要 model_id 数组或对象数组）")

        val provider = when (type) {
            ProviderTypes.ANTHROPIC -> AnthropicProviderSetting(
                id = ProviderRepository.newId(),
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                models = models,
            )

            ProviderTypes.GEMINI -> GeminiProviderSetting(
                id = ProviderRepository.newId(),
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                models = models,
            )

            else -> OpenAiCompatibleProviderSetting(
                id = ProviderRepository.newId(),
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                models = models,
            )
        }

        val saved = ProviderRepository.addProvider(provider)
        // 读回校验：写完必须能查回来，否则报错而不是假装成功。
        val reread = ProviderRepository.providerById(saved.id)
            ?: return failureJson("WRITE_NOT_CONFIRMED", "写入后查不到这个 provider，可能没保存成功")

        return JSONObject()
            .put("ok", true)
            .put("tool", "manage_provider")
            .put("action", "create")
            .put("provider_id", reread.id)
            .put("name", reread.name)
            .put("base_url", reread.baseUrl)
            .put("api_key", maskSecret(reread.apiKey))
            .put("models", reread.models.size)
            .put("note", "已保存并读回确认。要切到它请用 action=set_active。")
            .toString()
    }

    private suspend fun updateProviderConfig(args: JSONObject): String {
        val id = args.optString("provider_id").trim()
        if (id.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 provider_id")
        val existing = ProviderRepository.providerById(id)
            ?: return failureJson("PROVIDER_NOT_FOUND", "没有这个 provider：$id")

        // 只改传了的字段，其余保留。key 传空串表示「不改」，避免手滑清空。
        val newBaseUrl = args.optString("base_url").trim()
        if (newBaseUrl.isNotBlank() &&
            !newBaseUrl.startsWith("http://") && !newBaseUrl.startsWith("https://")
        ) {
            return failureJson("INVALID_ARGUMENT", "base_url 必须以 http(s):// 开头")
        }
        val newKey = args.optString("api_key")
        val newModels = if (args.has("models")) {
            parseModelList(args.optJSONArray("models"))
                ?: return failureJson("INVALID_ARGUMENT", "models 格式不对")
        } else {
            existing.models
        }
        val updated = when (existing) {
            is OpenAiCompatibleProviderSetting -> existing.copy(
                name = args.optString("name").trim().ifBlank { existing.name },
                baseUrl = newBaseUrl.ifBlank { existing.baseUrl },
                apiKey = newKey.ifBlank { existing.apiKey },
                models = newModels,
                isEnabled = if (args.has("enabled")) args.optBoolean("enabled") else existing.isEnabled,
            )

            is AnthropicProviderSetting -> existing.copy(
                name = args.optString("name").trim().ifBlank { existing.name },
                baseUrl = newBaseUrl.ifBlank { existing.baseUrl },
                apiKey = newKey.ifBlank { existing.apiKey },
                models = newModels,
                isEnabled = if (args.has("enabled")) args.optBoolean("enabled") else existing.isEnabled,
            )

            is GeminiProviderSetting -> existing.copy(
                name = args.optString("name").trim().ifBlank { existing.name },
                baseUrl = newBaseUrl.ifBlank { existing.baseUrl },
                apiKey = newKey.ifBlank { existing.apiKey },
                models = newModels,
                isEnabled = if (args.has("enabled")) args.optBoolean("enabled") else existing.isEnabled,
            )

            is CustomProviderSetting -> existing.copy(
                name = args.optString("name").trim().ifBlank { existing.name },
                baseUrl = newBaseUrl.ifBlank { existing.baseUrl },
                apiKey = newKey.ifBlank { existing.apiKey },
                models = newModels,
                isEnabled = if (args.has("enabled")) args.optBoolean("enabled") else existing.isEnabled,
            )

            is OpenCodeProviderSetting -> existing.copy(
                name = args.optString("name").trim().ifBlank { existing.name },
                baseUrl = newBaseUrl.ifBlank { existing.baseUrl },
                apiKey = newKey.ifBlank { existing.apiKey },
                models = newModels,
                isEnabled = if (args.has("enabled")) args.optBoolean("enabled") else existing.isEnabled,
            )
        }
        ProviderRepository.updateProvider(updated)
        // 关键（2026-11-08 审查发现）：updateProvider 只更新 provider 行，
        // **不碰 provider_models 表**。以前这里传了 models 也白传，还返回 ok:true ——
        // 假的成功。改模型列表必须走 replaceModels。
        if (args.has("models")) {
            ProviderRepository.replaceModels(id, newModels)
        }
        val reread = ProviderRepository.providerById(id)
            ?: return failureJson("WRITE_NOT_CONFIRMED", "更新后查不到这个 provider")
        if (args.has("models") && reread.models.size != newModels.size) {
            return failureJson(
                "WRITE_NOT_CONFIRMED",
                "模型列表没写进去：期望 ${newModels.size} 个，实际 ${reread.models.size} 个",
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "manage_provider")
            .put("action", "update")
            .put("provider_id", reread.id)
            .put("name", reread.name)
            .put("base_url", reread.baseUrl)
            .put("api_key", maskSecret(reread.apiKey))
            .toString()
    }

    private suspend fun deleteProviderConfig(args: JSONObject): String {
        val id = args.optString("provider_id").trim()
        if (id.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 provider_id")
        val existing = ProviderRepository.providerById(id)
            ?: return failureJson("PROVIDER_NOT_FOUND", "没有这个 provider：$id")
        ProviderRepository.deleteProvider(id)
        val stillThere = ProviderRepository.providerById(id) != null
        if (stillThere) return failureJson("WRITE_NOT_CONFIRMED", "删除后还能查到，没删成功")
        return JSONObject()
            .put("ok", true)
            .put("tool", "manage_provider")
            .put("action", "delete")
            .put("deleted", id)
            .put("name", existing.name)
            .toString()
    }

    private suspend fun setActiveModel(args: JSONObject): String {
        // 注意：切换用的是 Model.id（行 id），不是 modelId 字符串（如 "deepseek-chat"）。
        // 两者容易混，所以这里两种都收：先按行 id 找，找不到再按 modelId 字符串找。
        val wanted = args.optString("model_id").trim().ifBlank { args.optString("model").trim() }
        if (wanted.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 model_id")
        // 只找**启用中**的 provider 与模型：以前不过滤，切到已停用的会触发
        // repairSelection 把当前选择清空再随便挑一个 —— 副作用比报错更糟。
        val providers = ProviderRepository.allProviders().filter { it.isEnabled }
        val hit = providers.firstNotNullOfOrNull { provider ->
            provider.models.firstOrNull { it.isEnabled && (it.id == wanted || it.modelId == wanted) }
                ?.let { model -> provider to model }
        } ?: return failureJson(
            "MODEL_NOT_FOUND",
            "没有模型：$wanted（先用 action=list 看有哪些）",
        )
        RuntimeConfigRepository.setSelectedModelId(hit.second.id)
        val settings = ProviderRepository.settings()
        if (settings.selectedModelId != hit.second.id) {
            return failureJson(
                "WRITE_NOT_CONFIRMED",
                "切换后读回不一致：现在是 ${settings.selectedModelId ?: "空"}（模型可能被禁用了）",
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "manage_provider")
            .put("action", "set_active")
            .put("provider", hit.first.name)
            .put("model", hit.second.displayName)
            .put("model_id", hit.second.modelId)
            .toString()
    }

    /** `["m1","m2"]` 或 `[{"model_id":"m1","display_name":"M1"}]` 两种写法都认。 */
    private fun parseModelList(array: JSONArray?): List<Model>? {
        if (array == null) return emptyList()
        val models = mutableListOf<Model>()
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val parsed: Model? = when (val item = array.opt(index)) {
                is String -> if (item.isNotBlank()) {
                    val mid = item.trim()
                    Model(
                        id = UUID.randomUUID().toString(),
                        modelId = mid,
                        displayName = mid,
                        // sortOrder 必须给，否则全是 0，界面里顺序由 SQLite 决定（会乱）。
                        sortOrder = index,
                    )
                } else {
                    null
                }

                is JSONObject -> {
                    val modelId = item.optString("model_id").trim()
                    if (modelId.isBlank()) return null
                    val window = if (item.has("context_window")) item.optInt("context_window") else null
                    Model(
                        id = UUID.randomUUID().toString(),
                        modelId = modelId,
                        displayName = item.optString("display_name").trim().ifBlank { modelId },
                        sortOrder = index,
                        // effectiveContextWindow 只读 override，只给 contextWindow 等于没给。
                        contextWindow = window,
                        contextWindowOverride = window,
                    )
                }

                else -> return null
            }
            if (parsed != null) {
                // 查重：两个同名 modelId 会让 set_active 的"按 modelId 找"产生歧义。
                if (!seen.add(parsed.modelId.lowercase())) return null
                models += parsed
            }
        }
        return models
    }

    /** 凭据一律打码：只留前 4 位与后 4 位，中间用 *** 代替。 */
    private fun maskSecret(secret: String): String {
        // 空串要能和"配了但很短"区分开：否则模型会以为没配 key，又去覆盖一遍。
        if (secret.isBlank()) return "(未设置)"
        // 阈值 12：11-12 位的 key 若按首4尾4处理会露出 8 个字符，
        // 对自定义网关发的短 token 几乎等于全泄（2026-11-08 审查指出）。
        if (secret.length <= 12) return "***"
        return secret.take(4) + "***" + secret.takeLast(4)
    }

    /**
     * MCP 服务器的增删改查（2026-11-08 加）。
     *
     * 之前 MCP 也是只能在界面点：`McpServerRepository` 有 add/update/delete/setToolEnabled，
     * 但一个都没暴露给模型。
     *
     * 安全设计（同 manage_provider 的口径：只在真正危险时弹卡）：
     *  · bearer token 永远打码回显；
     *  · 删除走危险确认卡（不可逆）；
     *  · 新增/修改后读回校验。
     */
    private fun manageMcp(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        if (action.isBlank()) return failureJson("INVALID_ARGUMENT", "缺少 action")

        return runBlocking(Dispatchers.IO) {
            when (action) {
                "list" -> {
                    val servers = McpServerRepository.servers()
                    val array = JSONArray()
                    servers.forEach { server ->
                        val tools = JSONArray()
                        server.tools.forEach { tool ->
                            tools.put(
                                JSONObject()
                                    .put("name", tool.name)
                                    .put("description", tool.description.take(120))
                                    .put("enabled", tool.name in server.enabledToolNames),
                            )
                        }
                        array.put(
                            JSONObject()
                                .put("server_id", server.id)
                                .put("name", server.name)
                                .put("url", server.url)
                                .put("enabled", server.enabled)
                                .put("authorization", server.authorizationType)
                                .put("bearer_token", maskSecret(McpServerRepository.bearerToken(server.id).orEmpty()))
                                .put("tool_count", server.tools.size)
                                .put("enabled_tools", server.enabledToolNames.size)
                                .put("tools", tools),
                        )
                    }
                    return@runBlocking JSONObject()
                        .put("ok", true)
                        .put("tool", "manage_mcp")
                        .put("count", array.length())
                        .put("servers", array)
                        .toString()
                }

                "create" -> {
                    val name = args.optString("name").trim()
                    if (name.isBlank()) return@runBlocking failureJson("INVALID_ARGUMENT", "缺少 name")
                    val url = args.optString("url").trim()
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return@runBlocking failureJson("INVALID_ARGUMENT", "url 必须以 http(s):// 开头")
                    }
                    val token = args.optString("bearer_token")
                    val server = McpServerSetting(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        url = url,
                        enabled = if (args.has("enabled")) args.optBoolean("enabled") else true,
                        authorizationType = if (token.isBlank()) McpAuthorizationType.NONE else McpAuthorizationType.BEARER,
                    )
                    val added = McpServerRepository.add(server, token)
                    val reread = McpServerRepository.serverById(added.id)
                        ?: return@runBlocking failureJson("WRITE_NOT_CONFIRMED", "写入后查不到这个 MCP 服务器")
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "manage_mcp")
                        .put("action", "create")
                        .put("server_id", reread.id)
                        .put("name", reread.name)
                        .put("url", reread.url)
                        .put("bearer_token", maskSecret(token))
                        .put(
                            "note",
                            "已保存。工具列表要等下一次连接时才会刷新（首次为空是正常的）。",
                        )
                        .toString()
                }

                "update" -> {
                    val id = args.optString("server_id").trim()
                    if (id.isBlank()) return@runBlocking failureJson("INVALID_ARGUMENT", "缺少 server_id")
                    val existing = McpServerRepository.serverById(id)
                        ?: return@runBlocking failureJson("SERVER_NOT_FOUND", "没有这个 MCP 服务器：$id")
                    val newUrl = args.optString("url").trim()
                    if (newUrl.isNotBlank() &&
                        !newUrl.startsWith("http://") && !newUrl.startsWith("https://")
                    ) {
                        return@runBlocking failureJson("INVALID_ARGUMENT", "url 必须以 http(s):// 开头")
                    }
                    // token 传空表示不改（与 manage_provider 的 api_key 一致）。
                    val token = args.optString("bearer_token").ifBlank { null }
                    val updated = existing.copy(
                        name = args.optString("name").trim().ifBlank { existing.name },
                        url = newUrl.ifBlank { existing.url },
                        enabled = if (args.has("enabled")) args.optBoolean("enabled") else existing.enabled,
                        // 关键修复（2026-11-08 审查发现）：给原本无鉴权的服务器补 token 时，
                        // 若 authorizationType 仍是 NONE，McpServerRepository.update 会走
                        // secretStore.clear() —— token 被静默丢掉，服务端收到的是无鉴权请求。
                        authorizationType = if (token != null) McpAuthorizationType.BEARER else existing.authorizationType,
                    )
                    McpServerRepository.update(updated, token)
                    val reread = McpServerRepository.serverById(id)
                        ?: return@runBlocking failureJson("WRITE_NOT_CONFIRMED", "更新后查不到这个服务器")
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "manage_mcp")
                        .put("action", "update")
                        .put("server_id", reread.id)
                        .put("name", reread.name)
                        .put("url", reread.url)
                        .put("enabled", reread.enabled)
                        .toString()
                }

                "delete" -> {
                    val id = args.optString("server_id").trim()
                    if (id.isBlank()) return@runBlocking failureJson("INVALID_ARGUMENT", "缺少 server_id")
                    val existing = McpServerRepository.serverById(id)
                        ?: return@runBlocking failureJson("SERVER_NOT_FOUND", "没有这个 MCP 服务器：$id")
                    McpServerRepository.delete(id)
                    if (McpServerRepository.serverById(id) != null) {
                        return@runBlocking failureJson("WRITE_NOT_CONFIRMED", "删除后还能查到，没删成功")
                    }
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "manage_mcp")
                        .put("action", "delete")
                        .put("deleted", id)
                        .put("name", existing.name)
                        .toString()
                }

                "set_tool" -> {
                    val id = args.optString("server_id").trim()
                    val toolName = args.optString("tool_name").trim()
                    if (id.isBlank()) return@runBlocking failureJson("INVALID_ARGUMENT", "缺少 server_id")
                    if (toolName.isBlank()) return@runBlocking failureJson("INVALID_ARGUMENT", "缺少 tool_name")
                    if (McpServerRepository.serverById(id) == null) {
                        return@runBlocking failureJson("SERVER_NOT_FOUND", "没有这个 MCP 服务器：$id")
                    }
                    if (!args.has("enabled")) return@runBlocking failureJson("INVALID_ARGUMENT", "缺少 enabled")
                    val enabled = args.optBoolean("enabled")
                    McpServerRepository.setToolEnabled(id, toolName, enabled)
                    val reread = McpServerRepository.serverById(id)
                    val nowEnabled = reread?.enabledToolNames?.contains(toolName) == true
                    if (nowEnabled != enabled) {
                        return@runBlocking failureJson(
                            "WRITE_NOT_CONFIRMED",
                            "设置后读回不一致（工具名可能不在服务器的工具列表里）",
                        )
                    }
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "manage_mcp")
                        .put("action", "set_tool")
                        .put("server_id", id)
                        .put("tool_name", toolName)
                        .put("enabled", enabled)
                        .toString()
                }

                else -> failureJson("INVALID_ARGUMENT", "不支持的 action：$action")
            }
        }
    }

    private fun shareFolder(args: JSONObject): String {        // 配置存在 localAgent 里；没初始化时 current() 会返回空列表，直接当成「没有挂载」会误导。
        if (Prefs.localAgentPreferences() == null) {
            return errorResult("CONFIG_UNAVAILABLE", "配置存储尚未就绪，请在 App 内重试")
        }
        val action = args.optString("action").trim().lowercase().ifBlank { "list" }
        val current = SharedFolderMounts.current()
        return when (action) {
            "list" -> shareFolderList(current)
            "add" -> shareFolderAdd(args, current)
            "remove" -> shareFolderRemove(args, current)
            else -> errorResult("INVALID_ARGUMENT", "action 仅支持 list / add / remove")
        }
    }

    private fun shareFolderList(current: List<SharedFolderMount>): String {
        val items = JSONArray()
        current.forEach { mount ->
            items.put(
                JSONObject()
                    .put("name", mount.name)
                    .put("source", mount.sourcePath)
                    .put("readonly", mount.readOnly)
                    .put("mount_point", "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}")
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "share_folder")
            .put("action", "list")
            .put("count", items.length())
            .put("items", items)
            .put("hint", "配置改动对下一条 Linux 命令生效；chroot 后端支持只读，proot 后端无法只读。")
            .toString()
    }

    private fun shareFolderAdd(args: JSONObject, current: List<SharedFolderMount>): String {
        val name = args.optString("name").trim()
        val source = args.optString("source").trim()
        if (name.isEmpty() || source.isEmpty()) {
            return errorResult("INVALID_ARGUMENT", "add 需要 name 与 source")
        }
        when (SharedFolderMounts.validateName(name, current)) {
            SharedFolderMounts.NameError.INVALID ->
                return errorResult("INVALID_NAME", "挂载名只能用字母、数字、点、下划线、连字符，最多 48 字符")
            SharedFolderMounts.NameError.DUPLICATE ->
                return errorResult("DUPLICATE_NAME", "挂载名已存在：$name")
            null -> Unit
        }
        when (SharedFolderMounts.validateSource(source, current, allowedDataRoots = allowedSharedDataRoots())) {
            SharedFolderMounts.SourceError.INVALID_PATH ->
                return errorResult("INVALID_PATH", "源目录必须是绝对路径")
            SharedFolderMounts.SourceError.FORBIDDEN_ROOT ->
                return errorResult("FORBIDDEN_PATH", "该目录不允许共享：$source")
            SharedFolderMounts.SourceError.DUPLICATE ->
                return errorResult("DUPLICATE_SOURCE", "该源目录已经共享过：$source")
            null -> Unit
        }
        if (current.size >= SharedFolderMounts.MAX_MOUNTS) {
            return errorResult("LIMIT_REACHED", "最多 ${SharedFolderMounts.MAX_MOUNTS} 个共享文件夹")
        }
        val normalized = SharedFolderMounts.normalizeSourcePath(source) ?: source
        // 目录不存在的话，挂载会在会话建立时静默跳过（只有 ETA_MOUNT_FAILED 提示），
        // 在 add 这一步就拒绝更直接。
        if (!File(normalized).isDirectory) {
            return errorResult("SOURCE_MISSING", "源目录不存在或不是目录：$normalized")
        }
        val readOnly = args.optBoolean("readonly", true)
        val updated = current + SharedFolderMount(name = name, sourcePath = normalized, readOnly = readOnly)
        if (!SharedFolderMounts.save(updated)) {
            return errorResult("SAVE_FAILED", "写入共享文件夹配置失败")
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "share_folder")
            .put("action", "add")
            .put("name", name)
            .put("source", normalized)
            .put("readonly", readOnly)
            .put("mount_point", "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/$name")
            .put("hint", "下一条 Linux 命令即可看到该目录。")
            .toString()
    }

    private fun shareFolderRemove(args: JSONObject, current: List<SharedFolderMount>): String {
        val name = args.optString("name").trim()
        val target = current.firstOrNull { it.name == name }
            ?: return errorResult("NOT_FOUND", "没有名为 $name 的共享文件夹")
        val updated = current.filterNot { it.name == name }
        if (!SharedFolderMounts.save(updated)) {
            return errorResult("SAVE_FAILED", "写入共享文件夹配置失败")
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "share_folder")
            .put("action", "remove")
            .put("name", target.name)
            .put("source", target.sourcePath)
            .put("remaining", updated.size)
            .toString()
    }

    /** /data/data 下只放行 Eta 自己的目录，避免把别的应用私有数据暴露给 Linux 环境。 */
    private fun allowedSharedDataRoots(): List<String> {
        val packageName = runCatching { context.packageName }.getOrNull().orEmpty()
        return if (packageName.isBlank()) emptyList() else listOf("/data/data/$packageName")
    }

    private fun searchApps(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空")
        }
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val apps = findAppsByName(query, includeSystem).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_apps")
            .put("query", query)
            .put("apps", apps.toJsonArray())
            .toString()
    }

    private fun launchApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim().ifBlank { null }
        val appName = args.optString("app_name").trim().ifBlank { null }
        val requestedDisplay = CanvasRoutingPolicy.RequestedDisplay.parse(
            args.optString("display", "auto"),
        ) ?: return errorResult("INVALID_ARGUMENT", "display 仅支持 auto、virtual 或 main")

        val app = if (packageName != null) {
            findAppByPackage(packageName) ?: AppInfo(packageName = packageName, appName = appName ?: packageName)
        } else {
            if (appName == null) {
                return errorResult("INVALID_ARGUMENT", "package_name 和 app_name 至少提供一个")
            }
            val matches = findAppsByName(appName, includeSystem = false)
            val exactMatches = matches.filter { it.appName.equals(appName, ignoreCase = true) }
            when {
                exactMatches.size == 1 -> exactMatches.single()
                matches.size == 1 -> matches.single()
                matches.isEmpty() -> return errorResult(
                    code = "APP_NOT_FOUND",
                    message = "未找到应用：$appName"
                )
                else -> return JSONObject()
                    .put("ok", false)
                    .put("code", "AMBIGUOUS_APP")
                    .put("message", "匹配到多个应用，请指定 package_name")
                    .put("candidates", matches.take(10).toJsonArray())
                    .toString()
            }
        }

        val context = requireContext()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            return errorResult(
                code = "APP_NOT_LAUNCHABLE",
                message = "应用不可启动或未安装：${app.packageName}"
            )
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        val mainForegroundPkg = AgentAccessibilityService.current()?.currentPackageName(Display.DEFAULT_DISPLAY)
        val targetCanvas = CanvasRoutingPolicy.decideLaunchTarget(
            virtualDisplayEnabled = virtualDisplayEnabled(),
            backendAvailable = VirtualDisplayController.isBackendAvailable(rootAvailable(), lsposedAvailable()),
            requested = requestedDisplay,
            targetPackage = app.packageName,
            foregroundPackageOnMainDisplay = mainForegroundPkg,
            ownPackage = context.packageName,
        )

        val launchedVirtualId = if (targetCanvas == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            VirtualDisplayController.launchAppOnVirtualDisplay(
                context = context,
                packageName = app.packageName,
                appName = app.appName,
                launchIntent = launchIntent,
                rootAvailable = rootAvailable,
            )
        } else {
            null
        }

        val finalDisplayId = if (launchedVirtualId != null && CanvasRoutingPolicy.isVirtualDisplay(launchedVirtualId)) {
            activeDisplayId = launchedVirtualId
            launchedVirtualId
        } else {
            activeDisplayId = Display.DEFAULT_DISPLAY
            context.startActivity(launchIntent)
            Display.DEFAULT_DISPLAY
        }
        val finalCanvas = if (CanvasRoutingPolicy.isVirtualDisplay(finalDisplayId)) {
            CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName
        } else {
            CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName
        }

        logger.info("Agent local tool action=launch_app outcome=started canvas=$finalCanvas display=$finalDisplayId")
        return JSONObject()
            .put("ok", true)
            .put("tool", "launch_app")
            .put("app_name", app.appName)
            .put("package_name", app.packageName)
            .put("canvas", finalCanvas)
            .put("display_id", finalDisplayId)
            .toString()
    }

    private fun presentToUser(args: JSONObject): String {
        val context = requireContext()
        val reason = args.optString("reason").trim()
        val vdSnapshot = VirtualDisplayController.snapshots.value
        val wasVirtual = vdSnapshot.active || CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId)
        val presented = if (wasVirtual) {
            VirtualDisplayController.handoffToMainDisplay(context, rootAvailable)
        } else {
            true
        }
        activeDisplayId = Display.DEFAULT_DISPLAY
        return JSONObject()
            .put("ok", true)
            .put("tool", "present_to_user")
            .put("canvas", CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName)
            .put("display_id", Display.DEFAULT_DISPLAY)
            .put("presented", presented)
            .apply {
                if (vdSnapshot.appName.isNotBlank()) put("app_name", vdSnapshot.appName)
                if (vdSnapshot.packageName.isNotBlank()) put("package_name", vdSnapshot.packageName)
                if (reason.isNotBlank()) put("reason", reason)
            }
            .toString()
    }

    private fun openUri(args: JSONObject): String {
        val uriText = args.optString("uri").trim()
        if (uriText.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 不能为空")
        }
        val uri = Uri.parse(uriText)
        if (uri.scheme.isNullOrBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 缺少 scheme")
        }
        val context = requireContext()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!HookSupport.resolvesActivity(context, intent)) {
            return errorResult("NO_ACTIVITY", "没有应用可以处理该 URI")
        }
        val resolvedPkg = context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        val mainForegroundPkg = AgentAccessibilityService.current()?.currentPackageName(Display.DEFAULT_DISPLAY)
        val targetCanvas = CanvasRoutingPolicy.decideLaunchTarget(
            virtualDisplayEnabled = virtualDisplayEnabled(),
            backendAvailable = VirtualDisplayController.isBackendAvailable(rootAvailable(), lsposedAvailable()),
            requested = CanvasRoutingPolicy.RequestedDisplay.AUTO,
            targetPackage = resolvedPkg,
            foregroundPackageOnMainDisplay = mainForegroundPkg,
            ownPackage = context.packageName,
        )
        val virtualId = if (targetCanvas == CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY) {
            VirtualDisplayController.openUriOnVirtualDisplay(
                context = context,
                intent = intent,
                targetLabel = uri.host ?: uri.scheme.orEmpty(),
                rootAvailable = rootAvailable,
            )
        } else {
            null
        }
        val finalDisplayId = if (virtualId != null && CanvasRoutingPolicy.isVirtualDisplay(virtualId)) {
            activeDisplayId = virtualId
            virtualId
        } else {
            activeDisplayId = Display.DEFAULT_DISPLAY
            context.startActivity(intent)
            Display.DEFAULT_DISPLAY
        }
        val finalCanvas = if (CanvasRoutingPolicy.isVirtualDisplay(finalDisplayId)) {
            CanvasRoutingPolicy.CanvasTarget.VIRTUAL_DISPLAY.wireName
        } else {
            CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY.wireName
        }
        logger.info("Agent local tool action=open_uri outcome=started canvas=$finalCanvas")
        return JSONObject()
            .put("ok", true)
            .put("tool", "open_uri")
            .put("scheme", uri.scheme?.lowercase(Locale.ROOT))
            .put("canvas", finalCanvas)
            .put("display_id", finalDisplayId)
            .also { result ->
                if (uri.scheme.equals("https", true)) {
                    result.put("display_uri", uriText)
                }
            }
            .toString()
    }

    private fun checkRoot(): String {
        val probe = RootAccess.request(requireContext())
        kotlinx.coroutines.runBlocking {
            while (probe.isActive && !closed.get()) kotlinx.coroutines.delay(100)
        }
        if (closed.get()) return errorResult("CANCELLED", "已取消等待 Root 检测")
        val state = RootAccess.state.value
        return JSONObject().put("ok", true).put("tool", "terminal").put("action", "check_root")
            .put("root_available", state.isGranted).put("status", state.status.name.lowercase(Locale.ROOT))
            .put("checking", state.isChecking).put("su_present", state.suPresent)
            .put("uid", if (state.isGranted) 0 else JSONObject.NULL)
            .put("message", if (state.isGranted) "已确认 Root 授权；后续新命令默认使用 Root" else
                "尚未确认 Root 授权；请根据 status 检查授权或检测结果，默认 user 不表示终端不支持 Root")
            .toString()
    }

    private fun findAppByPackage(packageName: String): AppInfo? =
        installedLauncherApps().firstOrNull { it.packageName == packageName }

    private fun findAppsByName(query: String, includeSystem: Boolean): List<AppInfo> {
        val normalizedQuery = query.normalized()
        return installedLauncherApps()
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .mapNotNull { app ->
                val score = app.matchScore(query, normalizedQuery)
                if (score == Int.MAX_VALUE) null else score to app
            }
            .sortedWith(compareBy<Pair<Int, AppInfo>> { it.first }.thenBy { it.second.appName })
            .map { it.second }
            .toList()
    }

    private fun AppInfo.matchScore(rawQuery: String, normalizedQuery: String): Int {
        val normalizedName = appName.normalized()
        val normalizedPackage = packageName.normalized()
        return when {
            packageName.equals(rawQuery, ignoreCase = true) -> 0
            appName.equals(rawQuery, ignoreCase = true) -> 1
            normalizedName == normalizedQuery -> 2
            normalizedPackage.contains(normalizedQuery) -> 3
            normalizedName.contains(normalizedQuery) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun installedLauncherApps(): List<AppInfo> {
        val context = requireContext()
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L)
        )
        val apps = linkedMapOf<String, AppInfo>()
        resolveInfos.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val applicationInfo = activityInfo.applicationInfo ?: return@forEach
            val packageName = applicationInfo.packageName ?: return@forEach
            val appName = resolveInfo.loadLabel(packageManager).toString().trim()
                .takeIf { it.isNotBlank() }
                ?: packageName
            apps.putIfAbsent(
                packageName,
                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            )
        }
        return apps.values.toList()
    }

    private fun requireContext(): Context =
        AgentAppContext.resolve()
            ?: error("无法获取 Android 进程 Context")

    private fun List<AppInfo>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { app ->
                array.put(
                    JSONObject()
                        .put("app_name", app.appName)
                        .put("package_name", app.packageName)
                        .put("is_system_app", app.isSystemApp)
                )
            }
        }

    private fun String.normalized(): String =
        trim().lowercase(Locale.ROOT)

    private fun String.isOkJson(): Boolean =
        runCatching { JSONObject(this).optBoolean("ok", false) }.getOrDefault(false)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun requireElementObservation(
        args: JSONObject,
    ): RootShellDeviceController.ElementObservation? {
        val current = publishedObservation.get().elements ?: return null
        val requestedId = args.optString("observation_id").trim()
        return current.takeIf {
            ObservationReferencePolicy.validate(current.id, requestedId) ==
                ObservationReferencePolicy.Status.MATCH
        }
    }

    private fun observationError(args: JSONObject): String {
        val current = publishedObservation.get().elements
        val requestedId = args.optString("observation_id").trim()
        return when (ObservationReferencePolicy.validate(current?.id, requestedId)) {
            ObservationReferencePolicy.Status.NO_OBSERVATION ->
                errorResult("NO_OBSERVATION", "请先调用 observe_screen 获取 UI 节点")
            ObservationReferencePolicy.Status.ID_REQUIRED -> errorResult(
                "OBSERVATION_ID_REQUIRED",
                "节点动作必须携带同一次 observe_screen 返回的 observation_id",
            )
            ObservationReferencePolicy.Status.STALE -> errorResult(
                "STALE_OBSERVATION",
                "observation_id=$requestedId 已过期；当前为 ${current?.id}，请重新观察屏幕",
            )
            ObservationReferencePolicy.Status.MATCH -> errorResult(
                "OBSERVATION_ERROR",
                "观察快照状态异常，请重新观察屏幕",
            )
        }
    }

    // ==================== Skills tools ====================

    private fun skillsList(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val query = args.optString("query").trim().lowercase()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val entries = indexService.listInstalledSkills()
            .filter { entry -> SkillCompatibilityChecker.evaluate(entry).available }
            .filter { entry -> isVisibleInCurrentRun(entry.id) }
            .filter { entry ->
                if (query.isBlank()) true
                else listOf(entry.id, entry.name, entry.description, entry.skillFilePath, entry.rootPath)
                    .any { it.lowercase().contains(query) }
            }
            .take(limit)
        val items = JSONArray()
        entries.forEach { entry ->
            val capabilities = JSONArray()
            if (entry.hasScripts) capabilities.put("scripts")
            if (entry.hasReferences) capabilities.put("references")
            if (entry.hasAssets) capabilities.put("assets")
            if (entry.hasEvals) capabilities.put("evals")
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("description", entry.description)
                    .put("enabled", entry.enabled)
                    .put("source", entry.source)
                    .put("rootPath", entry.rootPath)
                    .put("skillFilePath", entry.skillFilePath)
                    .put("capabilities", capabilities)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", entries.size)
            .put("items", items)
            .toString()
    }

    private fun skillsRead(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val loader = skillLoader
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能加载器未初始化")
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到 skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return errorResult("INCOMPATIBLE", compat.reason ?: "当前环境不可用")
        val resolved = loader.load(entry, "agent 主动读取 skill")
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val body = if (resolved.bodyMarkdown.length <= maxChars) {
            resolved.bodyMarkdown
        } else {
            resolved.bodyMarkdown.take(maxChars) + "\n..."
        }
        val references = JSONArray()
        resolved.loadedReferences.forEach { references.put(it) }
        val frontmatter = JSONObject()
        resolved.frontmatter.forEach { (k, v) -> frontmatter.put(k, v) }
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("skillFilePath", entry.skillFilePath)
            .put("scriptsDir", resolved.scriptsDir ?: JSONObject.NULL)
            .put("assetsDir", resolved.assetsDir ?: JSONObject.NULL)
            .put("references", references)
            .put("frontmatter", frontmatter)
            .put("bodyMarkdown", body)
            .toString()
    }

    private fun skillsReadResource(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val reader = skillResourceReader
            ?: return errorResult("SKILLS_UNAVAILABLE", "Skill 资源读取器未初始化")
        val skillId = args.getString("skillId").trim()
        val relativePath = args.getString("relativePath").trim()
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到已启用 Skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return errorResult(
                "INCOMPATIBLE",
                compatibility.reason ?: "当前环境不可用",
            )
        }
        return when (val result = reader.readText(entry, relativePath)) {
            is SkillResourceReadResult.Success -> {
                val truncated = result.text.length > maxChars
                val visibleText = if (truncated) {
                    result.text.take(maxChars).let { prefix ->
                        if (prefix.lastOrNull()?.isHighSurrogate() == true) {
                            prefix.dropLast(1)
                        } else {
                            prefix
                        }
                    }
                } else {
                    result.text
                }
                JSONObject()
                    .put("ok", true)
                    .put("skillId", entry.id)
                    .put("relativePath", result.relativePath)
                    .put("text", visibleText)
                    .put("truncated", truncated)
                    .put("totalChars", result.text.length)
                    .toString()
            }
            is SkillResourceReadResult.Failure -> errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private fun skillsListCurated(): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val inspection = source.listCurated()
            rememberInspection(
                repository = GitHubSkillRepositoryParser.parse(inspection.repository),
                inspection = inspection,
                rememberDefault = true,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInspectGitHub(args: JSONObject): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val repository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = args.optString("path").takeIf { args.has("path") },
            )
            val inspection = source.inspect(repository)
            rememberInspection(
                repository = repository,
                inspection = inspection,
                rememberDefault = repository.ref == null,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInstallFromGitHub(args: JSONObject): String {
        val replaceExisting = args.optBoolean("replaceExisting", false)
        return skillSourceResult {
            val requestedRepository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = null,
            )
            val pathsJson = args.getJSONArray("paths")
            val selectedPaths = (0 until pathsJson.length()).map { index ->
                GitHubSkillRepositoryParser.normalizeRelativePath(pathsJson.getString(index))
            }
            if (replaceExisting && selectedPaths.size != 1) {
                return@skillSourceResult errorResult(
                    "SKILL_REPLACE_SCOPE_TOO_BROAD",
                    "一次只能替换一个 Skill 路径；请逐个重试",
                )
            }
            val expectedReplacementId = args.optString("expectedReplacementId").trim()
            val repository = if (replaceExisting) {
                validateReplacementReplay(
                    requestedRepository = requestedRepository,
                    selectedPaths = selectedPaths,
                    expectedReplacementId = expectedReplacementId,
                )?.let { return@skillSourceResult it }
                requestedRepository.copy(ref = pendingSkillConflict.get()!!.commitSha)
            } else {
                val snapshot = inspectedGitHubSnapshots[
                    inspectionKey(requestedRepository.slug, requestedRepository.ref)
                ] ?: return@skillSourceResult errorResult(
                    "SKILL_INSPECTION_REQUIRED",
                    "安装前必须在本轮先检查同一仓库与 ref 的 Skill 候选",
                )
                val invalidSelection = selectedPaths.firstOrNull {
                    it !in snapshot.candidatesByPath
                }
                if (invalidSelection != null) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查返回的候选中：$invalidSelection",
                    )
                }
                val snapshotPrefix = snapshot.prefix
                if (
                    snapshotPrefix != null &&
                    selectedPaths.any {
                        it != snapshotPrefix && !it.startsWith("$snapshotPrefix/")
                    }
                ) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查的目录范围内",
                    )
                }
                requestedRepository.copy(ref = snapshot.commitSha)
            }
            val prefix = requestedRepository.path?.takeUnless { it == "." }
            if (
                prefix != null &&
                selectedPaths.any { it != prefix && !it.startsWith("$prefix/") }
            ) {
                return@skillSourceResult errorResult(
                    "INVALID_SKILL_SELECTION",
                    "所选路径不在 GitHub URL 指定目录内",
                )
            }
            val source = githubSkillSource
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "GitHub Skill 服务未初始化",
                )
            val installer = skillPackageInstaller
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "Skill 安装器未初始化",
                )
            source.downloadArchive(repository).use { archive ->
                if (closed.get()) {
                    return@skillSourceResult errorResult(
                        "SKILL_INSTALL_CANCELLED",
                        "Skill 安装已取消，未提交文件",
                    )
                }
                val result = installer.installRepositoryZip(
                    openStream = { archive.file.inputStream() },
                    selectedPaths = selectedPaths,
                    replaceUserSkills = replaceExisting,
                    expectedReplacementIds = if (replaceExisting) {
                        setOf(expectedReplacementId)
                    } else {
                        emptySet()
                    },
                    isCancelled = closed::get,
                )
                installResult(
                    result = result,
                    repository = archive.repository,
                    ref = archive.ref,
                    commitSha = archive.commitSha,
                    selectedPaths = selectedPaths,
                )
            }
        }
    }

    private fun inspectionResult(
        inspection: io.github.mangi.eta.agent.skill.GitHubSkillInspection,
    ): String {
        val installedIds = skillIndexService
            ?.listSkillsForManagement()
            .orEmpty()
            .filter { it.installed }
            .mapTo(mutableSetOf()) { SkillParser.normalizeSkillLookup(it.id) }
        val items = JSONArray()
        inspection.candidates.forEach { candidate ->
            items.put(
                JSONObject()
                    .put("name", candidate.name)
                    .put("path", candidate.path)
                    .put(
                        "installed",
                        SkillParser.normalizeSkillLookup(candidate.name) in installedIds,
                    ),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("repository", inspection.repository)
            .put("ref", inspection.ref)
            .put("commitSha", inspection.commitSha)
            .put("prefix", inspection.prefix ?: JSONObject.NULL)
            .put("count", inspection.candidates.size)
            .put("items", items)
            .toString()
    }

    private fun rememberInspection(
        repository: GitHubSkillRepository,
        inspection: GitHubSkillInspection,
        rememberDefault: Boolean,
    ) {
        val snapshot = GitHubInspectionSnapshot(
            commitSha = inspection.commitSha,
            prefix = inspection.prefix,
            candidatesByPath = inspection.candidates.associate { it.path to it.name },
        )
        inspectedGitHubSnapshots[inspectionKey(repository.slug, repository.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.commitSha)] = snapshot
        if (rememberDefault) {
            inspectedGitHubSnapshots[inspectionKey(repository.slug, null)] = snapshot
        }
    }

    private fun inspectionKey(repository: String, ref: String?): String =
        "${repository.lowercase(Locale.ROOT)}@${ref.orEmpty()}"

    private fun validateReplacementReplay(
        requestedRepository: GitHubSkillRepository,
        selectedPaths: List<String>,
        expectedReplacementId: String,
    ): String? {
        val pending = pendingSkillConflict.get() ?: return errorResult(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            "没有可供精确重放的 Skill 冲突",
        )
        if (
            !requestedRepository.slug.equals(pending.repository, ignoreCase = true) ||
            requestedRepository.ref != pending.commitSha ||
            selectedPaths.singleOrNull() != pending.selectedPath ||
            expectedReplacementId != pending.expectedReplacementId
        ) {
            return errorResult(
                "SKILL_REPLACE_CAPABILITY_MISMATCH",
                "覆盖参数必须精确重放冲突结果中的仓库、commitSha、路径与 Skill ID",
            )
        }
        return null
    }

    private fun isVisibleInCurrentRun(skillId: String): Boolean {
        val normalized = SkillParser.normalizeSkillLookup(skillId)
        return normalized in runAvailableSkillIds && normalized !in mutatedSkillIds
    }

    private fun nextTurnRequired(skillId: String): String = errorResult(
        "NEXT_TURN_REQUIRED",
        "Skill $skillId 在本轮已安装或变更，将从下一轮对话开始可用",
    )

    private fun installResult(
        result: SkillInstallResult,
        repository: String,
        ref: String,
        commitSha: String,
        selectedPaths: List<String>,
    ): String = when (result) {
        is SkillInstallResult.Success -> {
            pendingSkillConflict.set(null)
            val installed = JSONArray()
            result.installed.forEach { skill ->
                mutatedSkillIds += SkillParser.normalizeSkillLookup(skill.id)
                installed.put(
                    JSONObject()
                        .put("id", skill.id)
                        .put("name", skill.name),
                )
            }
            JSONObject()
                .put("ok", true)
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("installed", installed)
                .put("available", "next_turn")
                .put("scriptsExecuted", false)
                .put("message", "Skill 已安装并启用，将从下一轮对话开始可用；安装过程未执行脚本")
                .toString()
        }
        is SkillInstallResult.Conflict -> {
            val conflicts = JSONArray()
            result.conflicts.forEach { conflict ->
                conflicts.put(
                    JSONObject()
                        .put("id", conflict.id)
                        .put("name", conflict.name)
                        .put("replaceAllowed", conflict.replaceAllowed),
                )
            }
            pendingSkillConflict.set(
                result.conflicts.singleOrNull()
                    ?.takeIf { it.replaceAllowed && selectedPaths.size == 1 }
                    ?.let { conflict ->
                        PendingSkillConflictCapability(
                            repository = repository,
                            commitSha = commitSha,
                            selectedPath = selectedPaths.single(),
                            expectedReplacementId = conflict.id,
                            expectedReplacementName = conflict.name,
                        )
                    },
            )
            JSONObject()
                .put("ok", false)
                .put("code", "SKILL_CONFLICT")
                .put("message", "Skill 已存在；可替换的单个用户 Skill 可按返回参数直接重试，内置 Skill 不可覆盖")
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("conflicts", conflicts)
                .toString()
        }
        is SkillInstallResult.Failure -> {
            if (result.error.code == SkillInstallErrorCode.COMMIT_FAILED) {
                skillTreeMutationUncertain.set(true)
            }
            errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private inline fun skillSourceResult(block: () -> String): String = try {
        block()
    } catch (failure: GitHubSkillSourceException) {
        errorResult(failure.code, failure.message ?: "GitHub Skill 请求失败")
    }

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content)

    private data class ScreenPoint(val x: Int, val y: Int)

    private class InvalidToolArgumentException(message: String) : IllegalArgumentException(message)

    private data class PublishedObservation(
        val elements: RootShellDeviceController.ElementObservation? = null,
        val coordinateSpace: RootShellDeviceController.CoordinateSpace? = null,
        val generation: Long? = null,
    )

    private data class GitHubInspectionSnapshot(
        val commitSha: String,
        val prefix: String?,
        val candidatesByPath: Map<String, String>,
    )

    /** 手势经过状态胶囊时让它暂时穿透并淡出；在虚拟屏内操作时不干扰物理屏胶囊。 */
    private fun <T> clearOverlay(displayId: Int, vararg points: ScreenPoint, gesture: () -> T): T {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            return gesture()
        }
        val area = Rect(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x } + 1,
            points.maxOf { it.y } + 1,
        )
        return AgentOverlayClearance.around(area, gesture)
    }

    private fun showTap(x: Int, y: Int, displayId: Int = Display.DEFAULT_DISPLAY) {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            VirtualDisplayController.emitTapEffect(x, y, longPress = false)
        } else {
            GestureIndicator.showTap(context, x, y)
        }
    }

    private fun showLongPress(
        x: Int,
        y: Int,
        durationMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            VirtualDisplayController.emitTapEffect(x, y, longPress = true)
        } else {
            GestureIndicator.showLongPress(context, x, y, durationMs)
        }
    }

    private fun showSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        if (CanvasRoutingPolicy.isVirtualDisplay(displayId)) {
            VirtualDisplayController.emitSwipeEffect(x1, y1, x2, y2, durationMs)
        } else {
            GestureIndicator.showSwipe(context, x1, y1, x2, y2, durationMs)
        }
    }

    private data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean = false
    )

    private companion object {
        /** 诊断日志行的级别位：`11-04 17:41:33.123 W TAG: msg` → `W`。 */
        private val LOG_LINE_LEVEL = Regex("^\\S+ \\S+ ([IWEF]) ")

        val DEVICE_DIRECT_TOOL_NAMES = io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.direct + setOf(            "set_alarm",
            "set_timer",
            "device_status",
            "inspect_app",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "media_control",
            "set_volume",
        )
        val DEVICE_SENSITIVE_READ_TOOL_NAMES = io.github.mangi.eta.agent.context.PersonalSearchTools.names + io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.reads + setOf(
            "search_notes", "search_system_memories",
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
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_coloros_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
        )
        val DEVICE_SENSITIVE_ACTION_TOOL_NAMES = io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.writes + setOf(
            "set_setting",
            "set_device_state",
            "app_state_control",
            // 三个管理工具会改 Eta 自己的配置（删服务商 / 删 MCP / 改任务表），
            // 属于"会动手"，只读模式必须挡住（2026-11-08 审查发现：以前它们挂在
            // 敏感读取组里，只读任务照样能改配置）。
            "manage_provider",
            "manage_mcp",
            "manage_scheduled_task",
        )
        val DEVICE_TOOL_NAMES =
            DEVICE_DIRECT_TOOL_NAMES + DEVICE_SENSITIVE_READ_TOOL_NAMES +
                DEVICE_SENSITIVE_ACTION_TOOL_NAMES
        val MEMORY_TOOL_NAMES = setOf("memory_get", "memory_write")
    }
}

private val AFTER_ACTION_OPTIONS = AgentScreenObservationContract.Options(
    includeScreenshot = false,
    includeUiTree = true,
    maxNodes = 30,
)

/** 宏里按文字找节点用的快照：60 个节点，长列表也够用。 */
private val MACRO_OBSERVE_OPTIONS = AgentScreenObservationContract.Options(
    includeScreenshot = false,
    includeUiTree = true,
    maxNodes = 60,
)

/** 按文字定位时的轮询间隔（毫秒）。 */
private const val MACRO_TAP_TEXT_POLL_MS = 200L

/**
 * 把待执行的命令 / 路径摘出来，给确认卡片显示。
 *
 * 用户批准的是「一个具体动作」，所以必须让他看见动作本身长什么样；
 * 只写一句「会写系统分区」等于让人盲批。超长时截断，免得撑爆弹窗。
 */
private fun dangerousDetail(toolName: String, args: JSONObject?): String {
    val raw = when (toolName) {
        "write_file", "edit_file" -> args?.optString("path").orEmpty()
        else -> listOf(
            args?.optString("command").orEmpty(),
            args?.optString("cmd").orEmpty(),
        ).filter { it.isNotBlank() }.joinToString(" ")
    }.trim()
    return when {
        raw.isEmpty() -> ""
        raw.length <= MAX_DETAIL_CHARS -> raw
        else -> raw.take(MAX_DETAIL_CHARS) + " …（还有 ${raw.length - MAX_DETAIL_CHARS} 字）"
    }
}

/** 卡片里命令原文的字符上限：只防极端超长，截断展示交给 UI（2026-11-07 由 400 放宽）。 */
private const val MAX_DETAIL_CHARS = 4000

/** ask_user 的问题长度上限：再长就不该用确认卡片，而该直接问。 */
private const val MAX_ASK_CHARS = 400

/** ask_user 在确认卡片上显示的「工具名」。 */
private const val ASK_USER_TOOL = "ask_user"

/** 动作执行后等「界面有变化」的上限；超过就判定这一步没生效。 */
private const val MACRO_CHANGE_TIMEOUT_MS = 900L
private const val MACRO_CHANGE_POLL_MS = 120L

/** 抓快照时等布局稳定的上限与轮询间隔。 */
private const val MACRO_AFTER_SETTLE_MAX_MS = 1_200L
private const val AFTER_ACTION_SETTLE_MAX_MS = 900L
/**
 * 稳定轮询间隔。2026-11-07 由 120ms 调到 60ms。
 *
 * 背景：`settledObservation()` 要求「连续两次快照的 stabilityKey 相同」才算稳定，
 * 所以每一步动作最少要读两遍完整无障碍树，中间还要 sleep 一次这个间隔。实测
 * gui_macro 连点 9 步约 5.9s（每步约 660ms），其中约 500ms 是固定开销，主要在这里。
 * 120 → 60 后每步省约 60ms；判据仍是「两次一致」而不是「等了多久」，所以不会更不可靠。
 */
private const val SETTLE_POLL_MS = 60L

/** 宏的一步执行结果；[Failed] 会带着原因中止整个宏，[OkUnverified] 表示做了但无法判断是否生效。 */
private sealed interface MacroStepOutcome {
    data object Ok : MacroStepOutcome

    data object OkUnverified : MacroStepOutcome

    data class Failed(val code: String, val message: String) : MacroStepOutcome
}

/** 界面变化判定的三态：没有「无法判定」这一档，就只能靠猜。 */
private enum class MacroScreenChange { CHANGED, UNCHANGED, UNKNOWN }

/** append 超过该长度改走粘贴路径；逐字键入的增量重建对长文本既慢又容易被输入法打断。 */
private const val TYPE_TEXT_INCREMENTAL_CHARS = 200

/** 归一化坐标上界：0–999，与主流 GUI 模型的输出习惯一致。 */
private const val NORMALIZED_MAX = 999

package io.github.mangi.eta

import io.github.mangi.eta.agent.voice.SpeechOssUpload
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.schedule.ScheduledTaskScheduler
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        // 最先接通诊断日志落盘：之后任何一步初始化失败都能留下证据。
        // （logcat 环形缓冲真机上只保留一分钟左右，出了问题回头就看不到。）
        io.github.mangi.eta.core.DiagnosticLogFiles.init(this)
        io.github.mangi.eta.core.DiagnosticLogFiles.installCrashHandler()
        val processName = Application.getProcessName()
        // 定时任务的 :schedule 进程只需要最基础的 SharedPreferences（读任务表）；
        // 完整运行时（终端、Root、仓库）在主进程初始化，两者互不干扰。
        if (processName != null && processName.endsWith(":schedule")) {
            // 定时任务进程只补必需项，其余完整运行时（终端、后台任务、MCP）仍只在主进程初始化。
            RootAccess.initialize(this)
            // 「自定义指令」类任务要在本进程解析当前模型配置（ScheduledAgentRunner 调
            // RuntimeConfigRepository.currentRuntimeConfig()），所以这两项也得初始化。
            // 2026-11-04 修复：原先只初始化 RootAccess，一访问 ProviderRepository 就抛
            // IllegalStateException（"ProviderRepository.init(context) must be called in
            // Application.onCreate()"），被 ScheduledAgentRunner 的 runCatching 吞成
            // 「没有可用的模型配置」——自定义指令类定时任务因此从未成功执行过。
            SettingsDataStore.init(this)
            ProviderRepository.init(this)
            // 定时任务也要知道「自己是哪个版本的 Eta」：注入系统提示的身份段来自这里。
            io.github.mangi.eta.agent.model.AgentRuntimeIdentity.init(this)
            return
        }
        if (!AppProcessPolicy.shouldInitializeFullRuntime(processName, packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        applicationScope.launch { TerminalRuntime.backgroundJobs(this@EtaApp).resume() }
        SettingsDataStore.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        // 「我是谁、装的是哪一版」：读 PackageManager 注入系统提示（versionName 里的 +commit
        // 就是这份 APK 的源码提交号）。放在这里而不是每次 run 现读，省一次 IPC。
        io.github.mangi.eta.agent.model.AgentRuntimeIdentity.init(this)
        // 定时任务：AlarmManager 的闹钟不跨重启存活，进程起来后按当前配置重建。
        ScheduledTaskScheduler.rescheduleAll(this)
        // 通知渠道：跑完要弹窗提示的渠道必须是 HIGH + 声音，这里统一建好并做版本升级。
        io.github.mangi.eta.agent.notify.EtaNotificationChannels.ensure(this)
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            try {
                SpeechOssUpload(this@EtaApp).retryPending(this@EtaApp)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Eta speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            BrowserSessionRegistry.releaseIdlePages()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        BrowserSessionRegistry.releaseIdlePages()
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}

package io.github.mangi.eta.hook.system

import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 虚拟屏上的输入法：**观察版**（只记不改，2026-11-05）。
 *
 * ## 现象
 *
 * Eta 的后台虚拟屏（实测 display 11）里，Settings 的 EditText 获得焦点后输入法被显示在**主屏**：
 * `mCurTokenDisplayId=0`、`Window #18 InputMethod: mDisplayId=0`。
 *
 * ## 根因（AOSP android16-release 源码）
 *
 * `InputMethodManagerService.computeImeDisplayIdForTargetInner()` 查
 * `WindowManagerInternal.getDisplayImePolicy(displayId)`：
 * 非系统所有的虚拟屏拿到 FALLBACK，于是回落到 `FALLBACK_DISPLAY_ID = DEFAULT_DISPLAY` ——
 * 输入法就被摆到主屏去了。`showSoftInput` / `startInputOrWindowGainedFocus` 的参数里**没有**
 * displayId，所以没法在入口用参数判断，必须看窗口 token 落在哪个屏。
 *
 * ## 为什么先观察
 *
 * 候选入口有一串（`showSoftInput`、`showSoftInputLocked`、`showCurrentInput*`、
 * `startInputOrWindowGainedFocus`、`setImeVisibilityOnFocusedWindowClient`……），
 * 加上 `Flags.refactorInsetsController()` 的取值会把路径掰到另一支上，
 * 而设备 framework.jar 是 compact dex，静态提取方法名不可靠。
 * 猜错两种后果都糟：挂不上（修了等于没修）或挂错位置（误拦主屏输入）。
 * 所以先只记录「谁被调用、参数是什么、窗口在哪个 display」，拿到确凿路径后再加拦截。
 *
 * ## 这一版只做三件事
 *
 * 1. 把 IMMS 里名字匹配 `SoftInput` / `startInput` / `showCurrentInput` / `setImeVisibility`
 *    的声明方法全部挂上**只读日志**拦截器：记方法名、参数类型、窗口 token 所在 display、
 *    调用方 uid/pid，然后原样 `proceed()`。**不改变任何行为。**
 * 2. 安装时记一行 `Flags.refactorInsetsController()` 的取值（拿不到就记 warning）。
 * 3. 名字对不上、类找不到时走 `hooks.missing()`，留痕而不是静默。
 *
 * ## 日志去哪看
 *
 * `module.log` → logcat 与 LSPosed 的 modules 日志：
 * `/data/adb/lspd/log/modules_*.log`（落盘，重启后还在，比 logcat 环形缓冲靠谱）。
 *
 * ## 生效条件
 *
 * hook 改动要 **system_server 重启**（重启手机）才生效。
 */
internal object InputMethodDisplayHooks {

    private const val IMMS_CLASS = "com.android.server.inputmethod.InputMethodManagerService"
    private const val LOCAL_SERVICES_CLASS = "com.android.server.LocalServices"
    private const val WMI_CLASS = "com.android.server.wm.WindowManagerInternal"
    private const val IME_FLAGS_CLASS = "android.view.inputmethod.Flags"

    /**
     * 要扫的容器类。
     *
     * 为什么不止 IMMS 一个：AOSP 16 里面向 Binder 的 `showSoftInput` 挂在 IMMS 的内部实现类上
     * （名字形如 `InputMethodManagerService$IInputMethodManagerImpl`），
     * 只扫 IMMS 自己的 declaredMethods 可能一条都抓不到。所以额外登记候选类，
     * 并把 IMMS 的内部类也一起扫（见 [collectTargets]）。
     */
    private val CANDIDATE_CLASSES = listOf(
        IMMS_CLASS,
        "com.android.server.inputmethod.ImeVisibilityStateComputer",
        "com.android.server.inputmethod.InputMethodBindingController",
    )

    /** 内部类只往下扫两层就够：再深就是监听器／回调之类。 */
    private const val NESTED_SCAN_DEPTH = 2

    /** 主屏那条路的日志按这个窗口限流，非默认屏的每次都记（那才是要看的东西）。 */
    private const val DEFAULT_DISPLAY_LOG_WINDOW_MS = 3_000L

    /** 模型侧可读的参数值上限，避免把已有 EditorInfo 之类的大对象整串塞进日志。 */
    private const val ARG_SUMMARY_CHARS = 240

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "InputMethodDisplay")
        return hooks.install {
            val immsClass = HookSupport.findClassOrNull(classLoader, IMMS_CLASS)
            if (immsClass == null) {
                hooks.missing(
                    id = "system.ime-display-observe",
                    description = "InputMethodManagerService 输入法显示路径",
                    detail = "未找到 $IMMS_CLASS（ROM 可能改过类名）",
                )
                return@install
            }
            logInsetsControllerFlag(hooks.logger, classLoader)
            observeShowPaths(hooks, classLoader, WindowDisplayResolver(classLoader))
        }
    }

    /** 观察目标：名字沾上输入法显示/隐藏的声明方法。按名字筛，不按签名猜。 */
    private fun observeShowPaths(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        resolver: WindowDisplayResolver,
    ) {
        val targets = collectTargets(classLoader)
        if (targets.isEmpty()) {
            hooks.missing(
                id = "system.ime-display-observe",
                description = "IMMS 输入法显示方法",
                detail = "候选类里没有名字匹配 SoftInput/startInput/showCurrentInput/setImeVisibility 的方法",
            )
            return
        }
        var installed = 0
        targets.forEach { (container, method) ->
            val description = "${container.simpleName}.${method.name}/${method.parameterCount}"
            // 每个方法单独兜住：框架拒绝挂某一条（ROM 改签名、native 方法）抛的是 Error，
            // 会穿过 HookRegistrar 的 Exception 捕获一路冒到 SystemServerHooks.install，
            // 把同一组里排后面的 VirtualDisplay / 无障碍保护 hook 一起带没。
            // 观察类 hook 丢掉无所谓，连累别人不行。
            val handle = runCatching {
                hooks.intercept(
                    id = observeHookId(container, method),
                    executable = method,
                    description = description,
                ) { chain ->
                    runCatching { logCall(hooks.logger, container, method, chain.args, resolver) }
                    chain.proceed()
                }
            }.onFailure { failure ->
                hooks.logger.warn("输入法显示观察: 挂 $description 失败 type=${failure.javaClass.simpleName}")
            }.getOrNull()
            if (handle != null) installed++
        }
        hooks.logger.info(
            "输入法显示观察: 已挂 $installed/${targets.size} 个方法 -> " +
                targets.joinToString(", ") { (container, method) ->
                    "${container.simpleName}.${method.name}/${method.parameterCount}"
                },
        )
    }

    /** 容器类 + 其内部类（限深）里所有命中名字判据的声明方法。 */
    private fun collectTargets(classLoader: ClassLoader): List<Pair<Class<*>, Method>> {
        val containers = linkedSetOf<Class<*>>()
        CANDIDATE_CLASSES.forEach { name ->
            HookSupport.findClassOrNull(classLoader, name)?.let(containers::add)
        }
        var frontier = containers.toList()
        repeat(NESTED_SCAN_DEPTH) {
            val nested = frontier.flatMap(::declaredClassesOf)
            frontier = nested.filter { containers.add(it) }
            if (frontier.isEmpty()) return@repeat
        }
        return containers.flatMap { container ->
            HookSupport.findDeclaredMethods(container, makeAccessible = true, predicate = ::isObserveTarget)
                .map { method -> container to method }
        }
    }

    private fun declaredClassesOf(type: Class<*>): List<Class<*>> = try {
        type.declaredClasses.toList()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: LinkageError) {
        emptyList()
    }

    private fun isObserveTarget(method: Method): Boolean {
        // 抽象/native/合成方法挂不上，挂了也没意义；提前排除，少给框架添麻烦。
        if (Modifier.isAbstract(method.modifiers) ||
            Modifier.isNative(method.modifiers) ||
            method.isSynthetic
        ) {
            return false
        }
        return method.name.let { name ->
            name.contains("SoftInput") ||
                name.startsWith("startInput") ||
                name.startsWith("showCurrentInput") ||
                name.startsWith("setImeVisibility")
        }
    }

    /**
     * Hook id 只允许 `[a-z0-9._-]`。
     * 用「类名-方法名-参数类型」而不是序号：方法集合一变（换 ROM/换版本）时 id 不会整体移位，
     * 安装报告与日志才能跨版本对比。
     */
    private fun observeHookId(container: Class<*>, method: Method): String = buildString {
        append("system.ime-observe-")
        append(sanitize(container.simpleName))
        append('-')
        append(sanitize(method.name))
        method.parameterTypes.forEach { type ->
            append('-')
            append(sanitize(type.simpleName))
        }
    }

    private fun sanitize(raw: String): String = raw.lowercase().map { character ->
        val allowed = character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '.' ||
            character == '_' ||
            character == '-'
        if (allowed) character else '-'
    }.joinToString("")

    private fun logCall(
        logger: ModuleLogger,
        container: Class<*>,
        method: Method,
        args: List<Any?>,
        resolver: WindowDisplayResolver,
    ) {
        val windowToken = windowTokenOf(method, args)
        val displayId = windowToken?.let(resolver::displayIdOf)
        val summary = buildString {
            append("输入法显示观察: ")
            append(container.simpleName)
            append('.')
            append(method.name)
            append(" 参数=[")
            append(method.parameterTypes.joinToString(",") { it.simpleName })
            append("] windowDisplay=")
            append(displayId?.toString() ?: "未知")
            append(" 调用方=")
            append(Binder.getCallingUid())
            append('/')
            append(Binder.getCallingPid())
            append(" 参数值=")
            append(describeArgs(args))
        }
        if (displayId != null && displayId != Display.DEFAULT_DISPLAY) {
            // 非默认屏（虚拟屏那次）重点记，但也限流：虚拟屏里连续打字会一串一串刷进来。
            logger.warnThrottled(
                key = "ime-observe-vd-${container.simpleName}-${method.name}",
                windowMs = 500L,
            ) { summary }
        } else {
            logger.warnThrottled(
                key = "ime-observe-${method.name}",
                windowMs = DEFAULT_DISPLAY_LOG_WINDOW_MS,
            ) { summary }
        }
    }

    /**
     * 取窗口 token：声明类型**正好是** `android.os.IBinder` 的那个参数。
     *
     * 不能取「第一个 IBinder」—— `showSoftInput` 的第 0 个参数是 `IInputMethodClient`，
     * 它也是 Binder，但那是客户端句柄，不是窗口。
     */
    private fun windowTokenOf(method: Method, args: List<Any?>): IBinder? {
        method.parameterTypes.forEachIndexed { index, type ->
            if (type == IBinder::class.java) {
                (args.getOrNull(index) as? IBinder)?.let { return it }
            }
        }
        return null
    }

    private fun describeArgs(args: List<Any?>): String {
        val rendered = args.mapIndexed { index, argument ->
            val text = when (argument) {
                null -> "null"
                is IBinder -> "IBinder@${Integer.toHexString(System.identityHashCode(argument))}"
                is Int -> argument.toString()
                is Boolean -> argument.toString()
                is String -> argument.take(40)
                else -> argument.javaClass.simpleName
            }
            "$index:$text"
        }.joinToString(" ")
        return rendered.take(ARG_SUMMARY_CHARS)
    }

    private fun logInsetsControllerFlag(logger: ModuleLogger, classLoader: ClassLoader) {
        val value = runCatching {
            val flagsClass = Class.forName(IME_FLAGS_CLASS, false, classLoader)
            val method = flagsClass.getDeclaredMethod("refactorInsetsController").apply { isAccessible = true }
            method.invoke(null)?.toString()
        }.getOrNull()
        if (value == null) {
            logger.warn("输入法显示观察: refactorInsetsController 取值不可读，路径需靠日志确认")
        } else {
            logger.info("输入法显示观察: refactorInsetsController=$value")
        }
    }

    /**
     * 窗口 token → display id。
     *
     * 用的是 `WindowManagerInternal.getDisplayIdForWindow(IBinder)`——AOSP 自己的
     * `resolveImeUserIdFromWindowLocked()` 就在同一把锁里调它，所以这个调用方向是系统认可的。
     *
     * 只读、拿不到就返回 null，绝不让观察代码影响输入法本身。
     */
    private class WindowDisplayResolver(private val classLoader: ClassLoader) {

        @Volatile private var service: Any? = null

        @Volatile private var displayIdMethod: Method? = null

        /** 解析失败后的退避时间：避免每次输入法显示都在 ImfLock 里重做一遍反射。 */
        @Volatile private var nextRetryAtMs = 0L

        fun displayIdOf(windowToken: IBinder): Int? {
            resolveIfNeeded()
            val method = displayIdMethod ?: return null
            val target = service ?: return null
            return runCatching { method.invoke(target, windowToken) as? Int }.getOrNull()
        }

        private fun resolveIfNeeded() {
            if (displayIdMethod != null) return
            if (SystemClock.elapsedRealtime() < nextRetryAtMs) return
            synchronized(this) {
                if (displayIdMethod != null) return
                if (SystemClock.elapsedRealtime() < nextRetryAtMs) return
                val resolved = runCatching {
                    val localServices = HookSupport.findClassOrNull(classLoader, LOCAL_SERVICES_CLASS)
                        ?: return@runCatching null
                    val windowManagerInternal = HookSupport.findClassOrNull(classLoader, WMI_CLASS)
                        ?: return@runCatching null
                    val getService = localServices
                        .getDeclaredMethod("getService", Class::class.java)
                        .apply { isAccessible = true }
                    val instance = getService.invoke(null, windowManagerInternal)
                        ?: return@runCatching null
                    val method = windowManagerInternal.getMethod("getDisplayIdForWindow", IBinder::class.java)
                    method to instance
                }.getOrNull()
                if (resolved == null) {
                    // 服务可能晚于模块加载注册，所以还要重试；但一分钟一次足够，别在锁里刷反射。
                    nextRetryAtMs = SystemClock.elapsedRealtime() + 60_000L
                } else {
                    displayIdMethod = resolved.first
                    service = resolved.second
                }
            }
        }
    }
}

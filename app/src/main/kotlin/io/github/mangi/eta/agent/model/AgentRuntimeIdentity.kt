package io.github.mangi.eta.agent.model

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「我是谁、装的是哪一版」——由 App 在运行时注入系统提示，不靠模型记忆。
 *
 * 为什么必须注入：模型没有可靠的自我状态来源。之前被问「你是什么版本」「装的是不是最新」
 * 「这次改动上了没」时，只能从上下文历史里翻旧版本号，经常把过期信息当现状
 * （用户多次反馈「你经常不知道你是 Eta、不了解 Eta 的实际情况」）。
 *
 * 版本号里 `+` 后面就是**构建这次 APK 的源码提交号**：Gradle 用 `eta.buildMetadata`
 * 拼进 `versionNameSuffix`，CI 传的是 commit 短 sha（例如 `3.4.0+7a71443`）。
 * 所以读出 `versionName` 就等于知道「设备上这份 APK 来自哪个提交」。
 *
 * 刻意不落盘、不跨进程持久化：进程内缓存一次即可，覆盖安装后进程重启自然更新。
 */
internal object AgentRuntimeIdentity {
    @Volatile
    private var cached: String? = null

    /** 在 `Application.onCreate()` 里调一次；`:schedule` 进程也要调（定时任务同样需要身份）。 */
    fun init(context: Context) {
        cached = runCatching { build(context.applicationContext) }.getOrDefault("")
    }

    /** 系统提示用的几行事实；未初始化或读取失败时返回空串（宁可不写，也不编造）。 */
    fun describe(): String = cached.orEmpty()

    private fun build(context: Context): String {
        // minSdk 33，直接用 PackageInfoFlags（旧重载已 deprecated）。
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
        val device = listOf(Build.BRAND, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "未知机型" }
        return format(
            versionName = info.versionName ?: "未知",
            // minSdk 33，longVersionCode 直接可用。
            versionCode = info.longVersionCode,
            updatedAtMillis = info.lastUpdateTime,
            device = device,
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
        )
    }

    /** 纯函数，便于单测。 */
    internal fun format(
        versionName: String,
        versionCode: Long,
        updatedAtMillis: Long,
        device: String,
        sdkInt: Int,
        androidRelease: String,
    ): String {
        val build = versionName.substringAfter('+', "").takeIf { it.isNotBlank() }
        val stamp = if (updatedAtMillis > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(updatedAtMillis))
        } else {
            "未知"
        }
        return buildString {
            append("你是 Eta —— 运行在这台 Android 手机上的 AI Agent 应用（包名 io.github.mangi.eta）。")
            appendLine()
            append("以下是你自己的运行状态，由 App 在运行时注入，不是用户输入，也不是从历史消息推断的：")
            appendLine()
            append("当前安装版本：$versionName（versionCode $versionCode，安装/更新时间 $stamp）；")
            if (build != null) {
                append("「+」后面是构建这次 APK 的源码提交号（$build）。")
            } else {
                append("这个版本没有带提交号。")
            }
            appendLine()
            appendLine("设备：$device，Android $androidRelease（API $sdkInt）。")
            append("用户问「你是什么版本」「装的是不是最新」「这次改动上了没」时，以这一行为准；")
            append("不要凭记忆或历史消息里的旧版本号回答，也不要说自己无法确定。")
        }
    }
}

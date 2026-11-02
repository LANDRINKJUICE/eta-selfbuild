package io.github.mangi.eta.agent.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 通知渠道的统一管理。
 *
 * 关键约束（Android 平台限制，必须遵守）：
 * - **渠道一旦创建，重要性/声音无法用代码修改**。想改只能「删旧渠道再建新渠道」，
 *   代价是用户对该渠道的自定义设置会被重置。所以这里的重置是**显式、受控**的。
 * - 定时任务需要"跑完弹一下"，必须是 IMPORTANCE_HIGH + 声音；否则只会静静躺在通知栏。
 */
internal object EtaNotificationChannels {
    /** 定时任务结果（跑完提示）。 */
    const val SCHEDULED = "eta_scheduled_tasks"

    /** 任务执行中（流体云 / Live Update）。 */
    const val EXECUTION = "eta_execution"

    /** 后台命令结果。 */
    const val BACKGROUND = "eta_background_commands"

    /** 渠道版本标签：渠道行为有变化时递增，触发一次「删旧建新」。 */
    private const val SCHEMA_VERSION_KEY = "eta_channel_schema_version"
    private const val SCHEMA_VERSION = 2

    /**
     * 创建/升级所有渠道。幂等：同一版本重复调用不会反复重建。
     *
     * @param resetScheduled 用户明确同意重置定时任务渠道时为 true（会丢掉该渠道的自定义设置）。
     */
    fun ensure(context: Context, resetScheduled: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val prefs = context.applicationContext.getSharedPreferences("eta_notification_channels", Context.MODE_PRIVATE)
        val applied = prefs.getInt(SCHEMA_VERSION_KEY, 0)

        // 定时任务：需要弹窗提示，所以用 HIGH + 声音 + 震动。
        // 只在首次安装或用户同意重置时重建，避免每次启动都清掉用户设置。
        if (applied < SCHEMA_VERSION || resetScheduled) {
            manager.deleteNotificationChannel(SCHEDULED)
            prefs.edit().putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION).apply()
        }
        manager.createNotificationChannel(
            NotificationChannel(SCHEDULED, "定时任务结果", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "定时任务跑完后提示结果；点开可看详情，可展开阅读完整内容"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                // 保持弹出提示：跑完立刻知道，而不是等你去翻通知栏。
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )

        // 执行中：流体云靠它，用 LOW 避免打扰（进度本身不需要响）。
        manager.createNotificationChannel(
            NotificationChannel(EXECUTION, "任务执行中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "任务运行时显示进度与当前动作，可在通知栏停止"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(BACKGROUND, "后台命令结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "后台命令执行完成后的结果通知"
                setShowBadge(true)
            },
        )
    }

    /** 跳回主界面的 PendingIntent（多个通知复用）。 */
    fun openAppIntent(context: Context, conversationId: String? = null, requestCode: Int = 0): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, "io.github.mangi.eta.ui.MainActivity")
            if (!conversationId.isNullOrBlank()) {
                action = "io.github.mangi.eta.action.OPEN_CONVERSATION_ID"
                putExtra("conversation_id", conversationId)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 是否支持 Live Update（流体云）协议。 */
    fun supportsLiveUpdate(): Boolean = Build.VERSION.SDK_INT >= 36
}

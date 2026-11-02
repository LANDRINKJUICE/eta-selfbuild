package io.github.mangi.eta.agent.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.ui.MainActivity

/**
 * 「执行中」通知（流体云 / Live Update）的构建器。
 *
 * 省电策略（用户明确要求"不耗电"）：
 * - **不做定时轮询**。通知只在状态真正变化时重建（现在换工具、出结果、失败…）。
 * - 已用时间用 `setWhen` + `setUsesChronometer(true)`，**计时交给 SystemUI 自己渲染**，
 *   我们一次都不用刷新就能看到秒数在走 —— 这是最省电的"动起来"。
 * - 结束态只更新一次，然后停止。
 *
 * 视觉分级（"尽量好看"）：
 * - 状态栏胶囊（`shortCriticalText`）：只放当前动作，7 个字左右，长了会截断。
 * - 收起态：一行"正在做什么 + 第几步"。
 * - 展开态：ProgressStyle 进度条 + 已用时间 + 停止按钮。
 */
internal object ExecutionNotificationBuilder {
    /**
     * @param status 当前状态；null 表示只有终端会话之类的长驻任务（不申请流体云）
     * @param taskCount 进行中的任务数
     * @param startedAt 本次执行开始时间（用于已用时间）
     */
    fun build(
        context: Context,
        status: AgentOverlayStatus?,
        taskCount: Int,
        startedAt: Long,
    ): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, AgentExecutionService::class.java)
                .setAction("io.github.mangi.eta.action.STOP_USER_EXECUTION"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val summary = context.resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount)
        val detail = when {
            status == null -> summary
            taskCount <= 1 -> status.localizedText(context.resources)
            else -> "$summary · ${status.localizedText(context.resources)}"
        }

        val builder = Notification.Builder(context, EtaNotificationChannels.EXECUTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.execution_title))
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            // 计时由 SystemUI 渲染：不刷新也能看到秒数在走，这是"不耗电的动感"。
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.execution_stop), stop).build())

        if (status != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // 流体云：进度条 + 状态栏胶囊文字 + 请求提升为 ongoing。
            builder
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgressIndeterminate(true)
                        .setProgressPoints(
                            // 进度点的参数是「触发提示的最小进度」，平台要求 >= 1（写 0 会被 lint 拦下）。
                            // 用 1 与 100 表达"起点 → 完成"，SystemUI 会画成一条推进的轨道。
                            listOf(
                                Notification.ProgressStyle.Point(1),
                                Notification.ProgressStyle.Point(100),
                            ),
                        ),
                )
                .setShortCriticalText(chipText(context, status))
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
        }
        return builder.build()
    }

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
    private fun chipText(context: Context, status: AgentOverlayStatus): String = when (status) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning ->
            context.getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> context.getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> context.getString(R.string.overlay_stopping)
        AgentOverlayStatus.GeneratingAnswer -> context.getString(R.string.execution_chip_answering)
        AgentOverlayStatus.ResultReady -> context.getString(R.string.overlay_result_ready)
        AgentOverlayStatus.RunFailed -> context.getString(R.string.overlay_run_failed)
        else -> context.getString(R.string.overlay_reasoning)
    }
}

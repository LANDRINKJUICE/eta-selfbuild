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
    /** 与 AgentExecutionService 的前台通知位保持一致：回复后要收掉的就是这条。 */
    private const val NOTIFICATION_ID = 1107

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
        /** 当前运行所属的会话；非空时通知带「回复」按钮，回复直接回到该会话。 */
        conversationId: String? = null,
        /**
         * 会话标题，用作通知标题（用户要求：别只写"Eta 在运行"，要能看出是哪个会话）。
         * 为空时退回原来的通用标题。
         */
        conversationTitle: String? = null,
    ): Notification {
        // 点正文直接进对应会话。
        // 为什么不用内联回复按钮：这条通知是流体云（ProgressStyle），实测系统在 ProgressStyle 下
        // 不渲染 action 按钮（dumpsys 里 actions 齐全，界面上就是不画），这是平台行为。
        // 所以走「点正文 → 到会话」：流体云保留，少一次自己翻会话列表的操作。
        val openIntent = Intent(context, MainActivity::class.java).apply {
            val target = conversationId?.takeIf { it.isNotBlank() }
            if (target != null) {
                action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                putExtra(MainActivity.EXTRA_CONVERSATION_ID, target)
            }
        }
        val open = PendingIntent.getActivity(
            context,
            conversationId?.hashCode() ?: 0,
            openIntent,
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
        // 详情优先说「正在做什么」，会话信息已经放到标题里了，这里不重复。
        val detail = when {
            status == null -> summary
            taskCount <= 1 -> status.localizedText(context.resources)
            else -> "$summary · ${status.localizedText(context.resources)}"
        }

        // 标题要「会变」：ColorOS 的流体云卡片只渲染标题（实测副标题不显示，
        // 截图里只有一行"Eta 正在运行"），所以把「会话名 · 当前动作」都放进标题，
        // 用户一眼能看出是哪个会话、在做什么，而不是干巴巴一行"正在运行"。
        val stateText = status?.localizedText(context.resources)
            ?: context.getString(R.string.execution_title)
        val titleText = conversationTitle?.takeIf { it.isNotBlank() }
            ?.let { "$it · $stateText" }
            ?: stateText

        val builder = Notification.Builder(context, EtaNotificationChannels.EXECUTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleText)
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

        // 注：这里原本加了「回复」内联按钮，但流体云的 ProgressStyle 不渲染 action，
        // 加上去只是多构造一个永远不会被点到的 PendingIntent，已移除（改由点正文进会话）。

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

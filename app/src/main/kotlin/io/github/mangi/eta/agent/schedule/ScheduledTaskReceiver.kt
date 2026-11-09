package io.github.mangi.eta.agent.schedule

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.notify.EtaNotificationChannels
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 定时任务的执行入口（运行在独立进程 `:schedule`）。
 *
 * 为什么用 Service 而不是 BroadcastReceiver：
 * - 事件型任务（通知、充电、电量、Wi-Fi、解锁）要在**独立进程**里处理，避免系统广播把主进程唤醒，
 *   用户正在用手机时不会因为一次定时任务而卡顿；主进程只有真正需要 Agent 能力时才被拉起。
 * - `onStartCommand` 天然有约 10 秒的启动窗口，足够完成"取任务 → 执行 → 续排下一次"，
 *   不需要 goAsync 那套广播技巧。
 *
 * 为什么要自己 `startForeground`（2026-11-09 修 A3）：
 * 本地动作（自检、系统快照、大脑备份、清 tmp、漂移检测）原本跑在裸线程上，整个包只有
 * `ScheduledAgentRunner` 借过主进程的前台服务 —— 那对 `:schedule` 自己一点保护都没有。
 * ColorOS 回收这个进程就是静默没备份，用户还以为做了。现在整个执行阶段自己就是前台服务。
 *
 * 自身不持有任何 UI 依赖；执行与通知都在 [ScheduledActionRunner]。
 */
internal class ScheduledTaskReceiver : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(ScheduledTaskScheduler.EXTRA_TASK_ID)
        if (taskId == null) {
            // 2026-11-04 加：`am startservice` 或系统重建 Service 时 intent 可能为 null，
            // 以前这里静默 stopSelf，排查时完全看不出"任务根本没开始"。
            AndroidAgentLogger.warn("Scheduled receiver got null task id, skip")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val appContext = applicationContext
        // 手动「立即执行一次」带 force：用户主动点的就该跑，不再被 2 分钟去重挡掉。
        val force = intent?.getBooleanExtra(ScheduledTaskScheduler.EXTRA_FORCE, false) ?: false
        Thread {
            // 只有真的要执行才提升为前台：任务已被删（孤儿闹钟）、已停用、刚跑过时
            // 提前台只会白闪一条「定时任务执行中」通知（2026-11-09 修）。
            var foreground = false
            try {
                val task = ScheduledTaskStore.load(appContext).firstOrNull { it.id == taskId }
                if (task == null) {
                    AndroidAgentLogger.warn("Scheduled task missing, skip")
                } else if (!task.enabled) {
                    // 已停用，什么都不做
                } else if (!force &&
                    !ScheduledRunGate.shouldRun(task.lastRunAtMillis, System.currentTimeMillis())
                ) {
                    // 同一任务刚跑过：这是重排闹钟/系统补发导致的重复触发。
                    // 只跳过执行，下面 finally 里仍会续排下一次，不影响后续日程。
                    AndroidAgentLogger.warn("Scheduled task duplicated, skip: ${task.id}")
                } else if (!inFlight.add(taskId)) {
                    // 同一任务上一次还没跑完：跳过，别再并发跑一遍（2026-11-04 代码体检 #4）
                    AndroidAgentLogger.warn("Scheduled task already running, skip: ${task.id}")
                } else {
                    foreground = enterForeground()
                    try {
                        // 先把「已开始」写进磁盘再执行：任务跑的那几分钟里磁盘上必须是新时间，
                        // 否则第二个触发（闹钟补发 / 事件同刻 / 手动执行）会通过 2 分钟闸门并发跑两遍。
                        ScheduledTaskStore.markRun(appContext, task.id, System.currentTimeMillis())
                        // 把触发这次任务的事件一起带上。
                        //
                        // 2026-11-04 修：通知匹配类任务以前只传 task，Agent 拿不到通知的
                        // 标题/正文/包名，只能**自己去打开通知栏看** —— 而通知栏属于 systemui，
                        // CanvasRoutingPolicy 把它列为必须主屏的包，于是用户正在用的界面被顶掉。
                        // 用户原话：「sub 识别到了，自己识屏然后打开通知栏…这就是直接抢我焦点了」。
                        val event = ScheduledEvent.decode(
                            intent?.getStringExtra(ScheduledTaskScheduler.EXTRA_EVENT)
                        )
                        ScheduledActionRunner.run(appContext, task, event)
                        ScheduledTaskStore.markRun(appContext, task.id, System.currentTimeMillis())
                    } finally {
                        inFlight.remove(taskId)
                    }
                }
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Scheduled task failed: type=${failure.javaClass.simpleName}")
            } finally {
                // 只有按时间触发的任务需要续排下一天；事件型任务不排。
                runCatching {
                    ScheduledTaskStore.load(appContext)
                        .firstOrNull { it.id == taskId }
                        ?.takeIf { it.enabled && it.trigger.usesAlarm }
                        ?.let { ScheduledTaskScheduler.schedule(appContext, it) }
                }
                // 还有别的任务在跑就别撤通知：撤了服务会退回后台态，下一个任务立刻失去保护。
                if (foreground && inFlight.isEmpty()) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                }
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    /**
     * 把 `:schedule` 提升为前台服务，成功返回 true。
     *
     * 失败只**降级**、不中止：通知权限被关、厂商拦截等情况不该让任务完全跑不了，
     * 但必须留痕（否则又回到"静默失败"的老问题）。
     */
    private fun enterForeground(): Boolean = runCatching {
        EtaNotificationChannels.ensure(this)
        val notification = Notification.Builder(this, EtaNotificationChannels.EXECUTION)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.scheduled_task_running_title))
            .setContentText(getString(R.string.scheduled_task_running_summary))
            .setContentIntent(
                EtaNotificationChannels.openAppIntent(this, requestCode = FOREGROUND_NOTIFICATION_ID)
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
        true
    }.onFailure { failure ->
        AndroidAgentLogger.warn("Scheduled foreground failed: type=${failure.javaClass.simpleName}")
    }.getOrDefault(false)

    private companion object {
        /** 与 [EtaNotificationChannels.EXECUTION] 的「任务执行中」共用一条通知位，不额外堆通知。 */
        const val FOREGROUND_NOTIFICATION_ID = 1190

        /**
         * 同进程内正在执行的任务 id。
         *
         * 磁盘上的 lastRunAtMillis 闸门只能挡「跑完之后」的重复触发；
         * 正在执行的这段时间需要内存标记兜住同进程并发（2026-11-04 代码体检 #4）。
         */
        val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}

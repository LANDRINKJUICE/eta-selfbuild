package io.github.mangi.eta.agent.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 重启、改时间、换时区、覆盖安装后重建闹钟。
 *
 * AlarmManager 的闹钟不跨重启存活，所以必须在这里补回来，否则"每天 9 点"会在重启后静默失效。
 */
internal class ScheduledTaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> ScheduledTaskScheduler.rescheduleAll(context.applicationContext)
        }
    }
}

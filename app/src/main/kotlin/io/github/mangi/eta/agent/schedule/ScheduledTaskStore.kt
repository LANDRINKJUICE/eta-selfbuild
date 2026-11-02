package io.github.mangi.eta.agent.schedule

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.UUID

/**
 * 定时任务持久化。
 *
 * 任务数量天然很小（个位数），结构也简单，因此用 SharedPreferences 存一个 JSON 数组，
 * 不引入 Room 迁移成本，也不与对话数据耦合。
 *
 * ⚠️ 注意两点（都踩过）：
 * 1. 这里的文件**运行在 `:schedule` 进程**，主进程若同时改同一文件，两边内存缓存会不一致。
 *    当前所有写入都发生在 UI 进程（通过 [ScheduledTaskStore]），执行进程只读，
 *    因此用 `MODE_PRIVATE` 打开即可，读取时用 `reload` 语义（每次新建实例，避免长缓存）。
 * 2. 直接用 root 写这个 xml **不会**让运行中的进程立即看到，需要冷启动或重新打开页面。
 */
internal object ScheduledTaskStore {
    private const val PREFS_NAME = "eta_scheduled_tasks"
    private const val KEY_TASKS = "tasks"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<ScheduledTask> {
        val raw = prefs(context).getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(ScheduledTask::fromJson)
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, tasks: List<ScheduledTask>) {
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_TASKS, array.toString()).apply()
    }

    fun upsert(context: Context, task: ScheduledTask) {
        val tasks = load(context).toMutableList()
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index >= 0) tasks[index] = task else tasks += task
        save(context, tasks)
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        save(context, load(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** 记录最近一次执行时间，供界面显示。 */
    fun markRun(context: Context, id: String, atMillis: Long) {
        save(context, load(context).map { if (it.id == id) it.copy(lastRunAtMillis = atMillis) else it })
    }

    fun newId(): String = UUID.randomUUID().toString()
}

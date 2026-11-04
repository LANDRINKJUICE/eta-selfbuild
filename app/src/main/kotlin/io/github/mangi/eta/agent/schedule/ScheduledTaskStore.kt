package io.github.mangi.eta.agent.schedule

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * 定时任务持久化。
 *
 * **为什么从 SharedPreferences 换成文件（2026-11-04 重写）**
 *
 * 任务表被两个进程读写：主进程（定时任务页）和 `:schedule`（执行完写 `last_run_at`）。
 * `SharedPreferences` 是**按进程缓存**的，两边各自拿着旧快照再「读整表 → 改 → 写整表」，于是：
 * 1. 页面每 5 秒轮询也看不到「上次执行」——缓存不刷新，轮询白轮询；
 * 2. 用户在页面保存时会用旧数组盖掉 `markRun`，`ScheduledRunGate` 的去重因此失效，
 *    同一次触发可能跑两遍；
 * 3. 极端情况（首次 seed 与保存交错）会丢任务。
 *
 * 现在：单一 JSON 文件 + 跨进程文件锁 + 临时文件原子替换。读永远读磁盘，不做进程内缓存。
 *
 * ⚠️ 仍要注意：直接用 root 改这个文件，运行中的进程下次读就会看到（不再有缓存），
 * 但别在两个进程写的中途手改。
 */
internal object ScheduledTaskStore {
    private const val FILE_NAME = "scheduled_tasks.json"
    private const val LOCK_NAME = "scheduled_tasks.lock"

    /** 旧版存储位置：只用于一次性迁移，迁移后不再写它。 */
    private const val LEGACY_PREFS = "eta_scheduled_tasks"
    private const val KEY_TASKS = "tasks"
    private const val KEY_SEEDED = "seeded_v1"

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun lockFile(context: Context): File =
        File(context.applicationContext.filesDir, LOCK_NAME)

    fun load(context: Context): List<ScheduledTask> = readState(context).tasks

    internal fun hasSeeded(context: Context): Boolean = readState(context).seeded

    internal fun markSeeded(context: Context) {
        update(context) { state -> state.copy(seeded = true) }
    }

    fun save(context: Context, tasks: List<ScheduledTask>) {
        update(context) { state -> state.copy(tasks = tasks) }
    }

    fun upsert(context: Context, task: ScheduledTask) {
        update(context) { state ->
            val next = state.tasks.toMutableList()
            val index = next.indexOfFirst { it.id == task.id }
            if (index >= 0) next[index] = task else next += task
            state.copy(tasks = next)
        }
    }

    fun delete(context: Context, id: String) {
        update(context) { state -> state.copy(tasks = state.tasks.filterNot { it.id == id }) }
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        update(context) { state ->
            state.copy(tasks = state.tasks.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    /** 记录最近一次执行时间，供界面显示。 */
    fun markRun(context: Context, id: String, atMillis: Long) {
        update(context) { state ->
            state.copy(
                tasks = state.tasks.map {
                    if (it.id == id) it.copy(lastRunAtMillis = atMillis) else it
                }
            )
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    private data class State(val seeded: Boolean, val tasks: List<ScheduledTask>)

    /**
     * 读-改-写必须整段持有跨进程锁，否则两个进程同时改会丢其中一次。
     *
     * `@Synchronized` 管同进程内并发（避免同一 JVM 里两次 `channel.lock()` 撞成
     * OverlappingFileLockException），文件锁管跨进程。
     */
    @Synchronized
    private fun update(context: Context, transform: (State) -> State) {
        val appContext = context.applicationContext
        withLock(appContext) {
            writeState(appContext, transform(readStateUnlocked(appContext)))
        }
    }

    @Synchronized
    private fun readState(context: Context): State =
        withLock(context) { readStateUnlocked(context.applicationContext) }

    private fun <T> withLock(context: Context, block: () -> T): T =
        runCatching {
            RandomAccessFile(lockFile(context), "rw").use { handle ->
                handle.channel.lock().use { block() }
            }
        }.getOrElse { failure ->
            // 锁文件异常（极少数文件系统问题）也不能让任务彻底不可用：退化成无锁执行，
            // 行为与改造前一致，但至少不会整条链路挂掉。
            AndroidAgentLogger.warn(
                "Scheduled store lock failed: type=${failure.javaClass.simpleName}"
            )
            block()
        }

    private fun readStateUnlocked(context: Context): State {
        val target = file(context)
        if (!target.exists()) return migrateLegacy(context)
        return runCatching {
            val json = JSONObject(target.readText())
            State(
                seeded = json.optBoolean("seeded", false),
                tasks = json.optJSONArray("tasks")?.let(::parseTasks).orEmpty(),
            )
        }.getOrElse { failure ->
            AndroidAgentLogger.warn(
                "Scheduled store read failed: type=${failure.javaClass.simpleName}"
            )
            // 读坏了也不能返回 seeded=false：否则下次 seed 会把用户的默认任务重灌一遍。
            State(seeded = true, tasks = emptyList())
        }
    }

    /** 从旧版 SharedPreferences 迁移一次；没有旧数据就返回空状态，不写文件。 */
    private fun migrateLegacy(context: Context): State {
        val legacy = runCatching {
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
        val seeded = legacy?.getBoolean(KEY_SEEDED, false) ?: false
        val tasks = legacy?.getString(KEY_TASKS, null)?.let { raw ->
            runCatching { parseTasks(JSONArray(raw)) }.getOrDefault(emptyList())
        }.orEmpty()
        val state = State(seeded = seeded, tasks = tasks)
        if (seeded || tasks.isNotEmpty()) writeState(context, state)
        return state
    }

    private fun writeState(context: Context, state: State) {
        val target = file(context)
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        runCatching {
            temp.writeText(
                JSONObject()
                    .put("seeded", state.seeded)
                    .put(
                        "tasks",
                        JSONArray().also { array -> state.tasks.forEach { array.put(it.toJson()) } },
                    )
                    .toString()
            )
            // 原子替换：读的一方要么看到旧文件、要么看到新文件，不会读到半个 JSON。
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { failure ->
            AndroidAgentLogger.warn(
                "Scheduled store write failed: type=${failure.javaClass.simpleName}"
            )
        }
    }

    private fun parseTasks(array: JSONArray): List<ScheduledTask> =
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(ScheduledTask::fromJson)
        }
}

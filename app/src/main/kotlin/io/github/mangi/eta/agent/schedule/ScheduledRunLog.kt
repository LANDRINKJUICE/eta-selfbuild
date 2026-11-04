package io.github.mangi.eta.agent.schedule

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * 定时任务的执行历史（最近 N 次）。
 *
 * 为什么必须有它：定时任务最大的风险是"静默失败"——你以为它每天在跑，其实早就断了。
 * 有了历史，界面能显示"上次执行时间 / 成功还是失败 / 失败原因 / 连续失败次数"。
 *
 * **存储为什么从 SharedPreferences 换成文件（2026-11-04）**
 * 历史由 `:schedule` 进程写、主进程读。`SharedPreferences` 按进程缓存，主进程读不到
 * 另一个进程刚写的记录 —— 表现为「任务刚跑完，页面上还是空的 / 上次执行时间不动」，
 * 用户实测过（页面为此加了 5 秒轮询，但轮询读的还是缓存，白轮）。
 * 现在改成单一 JSON 文件 + 跨进程文件锁，读永远读磁盘。
 */
internal object ScheduledRunLog {
    private const val FILE_NAME = "scheduled_runs.json"
    private const val LOCK_NAME = "scheduled_runs.lock"

    /** 旧版存储位置：只用于一次性迁移。 */
    private const val LEGACY_PREFS = "eta_scheduled_runs"
    private const val KEY_RECORDS = "records"

    private const val MAX_RECORDS = 100

    /** 一次执行的结果。 */
    internal data class Record(
        val taskId: String,
        val atMillis: Long,
        val ok: Boolean,
        /** 成功时的结果摘要 / 失败时的原因；不含敏感内容。 */
        val message: String,
        /** 耗时毫秒，便于发现"越来越慢"。 */
        val durationMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("task_id", taskId)
            .put("at", atMillis)
            .put("ok", ok)
            .put("message", message)
            .put("duration_ms", durationMs)

        companion object {
            fun fromJson(json: JSONObject): Record? {
                val taskId = json.optString("task_id").takeIf { it.isNotBlank() } ?: return null
                return Record(
                    taskId = taskId,
                    atMillis = json.optLong("at", 0L),
                    ok = json.optBoolean("ok", false),
                    message = json.optString("message", ""),
                    durationMs = json.optLong("duration_ms", 0L),
                )
            }
        }
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun lockFile(context: Context): File =
        File(context.applicationContext.filesDir, LOCK_NAME)

    fun record(context: Context, record: Record) {
        update(context) { records ->
            (listOf(record) + records).take(MAX_RECORDS)
        }
    }

    /**
     * 读不加锁（写是原子替换，读到的总是完整文件）；只有首次迁移才进锁。
     * 详见 [ScheduledTaskStore.readState] 的注释。
     */
    @Synchronized
    fun load(context: Context): List<Record> {
        val appContext = context.applicationContext
        if (file(appContext).exists()) return readUnlocked(appContext)
        return withLock(appContext) {
            if (file(appContext).exists()) {
                readUnlocked(appContext)
            } else {
                migrateLegacy(appContext)
            }
        }
    }

    /** 某条任务最近一次执行记录（没有则 null）。 */
    fun latestFor(context: Context, taskId: String): Record? =
        load(context).firstOrNull { it.taskId == taskId }

    /** 某条任务连续失败次数（从最新往前数，遇到成功就停）。 */
    fun consecutiveFailures(context: Context, taskId: String): Int =
        consecutiveFailuresOf(load(context), taskId)

    /**
     * 纯函数版本：直接用已经读出来的历史算，UI 每帧渲染时不必再读一次文件。
     *
     * 这个数字是「定时任务静默失败」最早能被发现的信号 —— 一条任务连着失败三次，
     * 界面应该直接告诉用户，而不是等人自己去翻执行历史（原作者写了计数却没接上，
     * 2026-11-04 补上）。
     */
    fun consecutiveFailuresOf(records: List<Record>, taskId: String): Int {
        var count = 0
        for (record in records) {
            if (record.taskId != taskId) continue
            if (record.ok) break
            count++
        }
        return count
    }

    fun clear(context: Context) {
        update(context) { emptyList() }
    }

    /**
     * 只清掉某一条任务的历史，其他任务的不动。
     *
     * 用户 2026-11-04 提的：执行历史只能看不能删，跑多了会一直挂在卡片下面。
     */
    fun removeForTask(context: Context, taskId: String) {
        update(context) { records -> records.filterNot { it.taskId == taskId } }
    }

    @Synchronized
    private fun update(context: Context, transform: (List<Record>) -> List<Record>) {
        val appContext = context.applicationContext
        withLock(appContext) {
            writeUnlocked(appContext, transform(readUnlocked(appContext)))
        }
    }

    private fun <T> withLock(context: Context, block: () -> T): T {
        // 同 ScheduledTaskStore：只容错「打开 + 加锁」，
        // 别把 block 自己的异常也当锁失败再执行一遍（2026-11-04 代码体检）。
        val handle = runCatching { RandomAccessFile(lockFile(context), "rw") }.getOrElse { failure ->
            AndroidAgentLogger.warn(
                "Scheduled run log lock open failed: type=${failure.javaClass.simpleName}",
            )
            return block()
        }
        return handle.use { file ->
            val lock = runCatching { file.channel.lock() }.getOrElse { failure ->
                AndroidAgentLogger.warn(
                    "Scheduled run log lock failed: type=${failure.javaClass.simpleName}",
                )
                return block()
            }
            lock.use { block() }
        }
    }

    private fun readUnlocked(context: Context): List<Record> {
        val target = file(context)
        // 文件不存在时由调用方在锁内决定要不要迁移（见 load）；这里只负责读。
        if (!target.exists()) return emptyList()
        return runCatching {
            parse(JSONArray(target.readText()))
        }.getOrElse { failure ->
            AndroidAgentLogger.warn(
                "Scheduled run log read failed: type=${failure.javaClass.simpleName}"
            )
            emptyList()
        }
    }

    /** 从旧版 SharedPreferences 迁移一次；没有旧数据就不写文件。 */
    private fun migrateLegacy(context: Context): List<Record> {
        val legacy = runCatching {
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
        val raw = legacy?.getString(KEY_RECORDS, null) ?: return emptyList()
        val records = runCatching { parse(JSONArray(raw)) }.getOrDefault(emptyList())
        if (records.isNotEmpty()) writeUnlocked(context, records)
        return records
    }

    private fun writeUnlocked(context: Context, records: List<Record>) {
        val target = file(context)
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        runCatching {
            val array = JSONArray()
            records.forEach { array.put(it.toJson()) }
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { failure ->
            AndroidAgentLogger.warn(
                "Scheduled run log write failed: type=${failure.javaClass.simpleName}"
            )
        }
    }

    private fun parse(array: JSONArray): List<Record> =
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)
                ?.let(Record::fromJson)
                ?.let(::normalizeLegacyFailure)
        }

    /**
     * 纠正旧版本写错的成败标记（只改读取结果，不改写磁盘上的原始记录）。
     *
     * 旧实现靠文案前缀猜成败，真机历史里留下了 8 条「已停止」「没有可用的模型配置」
     * 「执行超时」却 `ok=true` 的记录，界面上显示成绿勾。这里用同一套判据（[ScheduledOutcome.of]）
     * 把明显是失败的记录纠正过来，让旧数据也能正确显示。
     */
    private fun normalizeLegacyFailure(record: Record): Record =
        if (record.ok && !ScheduledOutcome.of(record.message).ok) {
            record.copy(ok = false)
        } else {
            record
        }
}

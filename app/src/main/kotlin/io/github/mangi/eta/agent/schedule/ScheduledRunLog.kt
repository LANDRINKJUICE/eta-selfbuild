package io.github.mangi.eta.agent.schedule

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时任务的执行历史（最近 N 次）。
 *
 * 为什么必须有它：定时任务最大的风险是"静默失败"——你以为它每天在跑，其实早就断了。
 * 有了历史，界面能显示"上次执行时间 / 成功还是失败 / 失败原因 / 连续失败次数"。
 *
 * 存储：SharedPreferences 里存一个 JSON 数组，只保留最近 [MAX_RECORDS] 条，避免无限增长。
 * 由 `:schedule` 进程写入、主进程读取；两边都只做 append/read，不需要强一致。
 */
internal object ScheduledRunLog {
    private const val PREFS_NAME = "eta_scheduled_runs"
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

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(context: Context, record: Record) {
        val existing = load(context).toMutableList()
        existing.add(0, record) // 最新的放最前
        val trimmed = existing.take(MAX_RECORDS)
        val array = JSONArray()
        trimmed.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    fun load(context: Context): List<Record> {
        val raw = prefs(context).getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(Record::fromJson)
            }
        }.getOrDefault(emptyList())
    }

    /** 某条任务最近一次执行记录（没有则 null）。 */
    fun latestFor(context: Context, taskId: String): Record? =
        load(context).firstOrNull { it.taskId == taskId }

    /** 某条任务连续失败次数（从最新往前数，遇到成功就停）。 */
    fun consecutiveFailures(context: Context, taskId: String): Int {
        var count = 0
        for (record in load(context)) {
            if (record.taskId != taskId) continue
            if (record.ok) break
            count++
        }
        return count
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RECORDS).apply()
    }
}

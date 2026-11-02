package io.github.mangi.eta.agent.schedule

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootAccessStatus
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自我体检：一次性回答「这台手机上 Eta 赖以工作的东西还齐不齐」。
 *
 * 与 [ScheduledActionRunner] 里 `permissions_check` 的定位区别：
 * - `permissions_check` 只报系统权限开关，一行流水账，答「我是不是误关了某个开关」；
 * - 这里会跑工作区脚本（技能体检、负载温度）、看定时任务闹钟还在不在、量 tmp 体积，
 *   每项都带证据，答「到底哪一环坏了」。
 *
 * 输出固定为「项目: 正常 / 异常 / 未验证（证据）」，一行一项，用户扫一眼就能定位。
 * 某一项读不到只降级成「未验证」，不让整份体检失败 —— 否则一个被禁用的系统服务
 * 就能让体检报告变成一句"执行失败"，那这份体检就没意义了。
 *
 * 运行位置：`:schedule` 进程里的 [ScheduledActionRunner]。所以这里不持有任何 Activity / Compose 依赖。
 */
internal object ScheduledHealthCheck {
    private const val STATUS_OK = "正常"
    private const val STATUS_BAD = "异常"
    private const val STATUS_UNKNOWN = "未验证"

    private const val MAX_NOTIFICATION_CHARS = 360
    private const val SCRIPT_TIMEOUT_SECONDS = 30L
    private const val PROBE_TIMEOUT_SECONDS = 15L

    /** 超过这个体积就提醒用户收 tmp。 */
    private const val TMP_WARN_BYTES = 512L * 1024L * 1024L

    /**
     * 完整报告，直接进通知与历史。
     *
     * @param pendingAlarms 期望已注册的闹钟数（= 启用的「每天定时」任务数），由调用方给，
     *                      体检里不再重读一遍任务表。
     */
    fun report(context: Context, pendingAlarms: Int): String {
        val findings = collect(context, pendingAlarms)
        val bad = findings.count { it.status == STATUS_BAD }
        val unknown = findings.count { it.status == STATUS_UNKNOWN }
        val verdict = when {
            bad > 0 -> "异常 $bad 项"
            unknown > 0 -> "全部可用，$unknown 项未验证"
            else -> "全部正常"
        }
        val body = buildString {
            append("结论：").append(verdict).append('\n')
            append("时间：").append(timestamp()).append('\n')
            findings.forEach { append(it.line()).append('\n') }
            if (bad > 0 || unknown > 0) append("（异常与未验证项的具体原因见上式括号内）")
        }.trimEnd()
        return if (body.length <= MAX_NOTIFICATION_CHARS) body else body.take(MAX_NOTIFICATION_CHARS - 1) + "…"
    }

    /** 单项结果。[detail] 必须是证据本身，不能是结论的同义反复。 */
    private data class Finding(val name: String, val status: String, val detail: String) {
        fun line(): String = "$name: $status（$detail）"
    }

    private fun collect(context: Context, pendingAlarms: Int): List<Finding> = listOf(
        directories(),
        skillAudit(),
        notificationPermission(context),
        accessibility(context),
        rootAccess(),
        exactAlarm(context),
        alarmRegistration(pendingAlarms),
        tmpSize(),
        loadAndTemperature(),
        memoryStatus(context),
    )

    // ---------------------------------------------------------------- 关键路径

    private fun directories(): Finding {
        val paths = ScheduledShell.Paths
        val checks = listOf(
            paths.workspace to "工作区",
            paths.tools to "工具目录",
            paths.analysis to "系统分析",
            paths.tmp to "tmp",
        )
        val missing = checks.filterNot { (file, _) -> file.isDirectory }
        return if (missing.isEmpty()) {
            Finding("关键路径", STATUS_OK, "4/4 个目录存在")
        } else {
            Finding("关键路径", STATUS_BAD, "缺少 " + missing.joinToString("、") { "${it.second}(${it.first.absolutePath})" })
        }
    }

    // ---------------------------------------------------------------- 技能体检

    private fun skillAudit(): Finding {
        val script = ScheduledShell.Paths.tool("skill-audit.sh")
        if (!script.isFile) return Finding("技能体检", STATUS_UNKNOWN, "缺 ${script.absolutePath}，跳过")
        val outcome = ScheduledShell.run(
            command = "/system/bin/sh '${script.absolutePath}' 2>&1",
            timeoutSeconds = SCRIPT_TIMEOUT_SECONDS,
            asRoot = true,
        )
        if (!outcome.ok) return Finding("技能体检", STATUS_UNKNOWN, "脚本失败：${outcome.reason}")
        val text = outcome.stdout
        // 汇总行形如「- 技能目录总数：74」，脚本改格式时这里会取到 null，降级成未验证而不是报 0。
        val total = text.valueAfter("技能目录总数：")
            ?: return Finding("技能体检", STATUS_UNKNOWN, "报告里没有汇总段，脚本可能已改版")
        val broken = text.valueAfter("有问题的技能：")?.toIntOrNull() ?: 0
        val items = text.valueAfter("问题条目：")
        val detail = buildString {
            append("$total 个技能")
            if (items != null) append("，$items 条问题")
            if (broken > 0) append("，涉及 $broken 个技能")
        }
        return if (broken == 0) {
            Finding("技能体检", STATUS_OK, detail)
        } else {
            Finding("技能体检", STATUS_BAD, "$detail，详见 skill-audit.sh 报告")
        }
    }

    /** 取汇总行的值；找不到标签时返回 null。 */
    private fun String.valueAfter(label: String): String? = lineSequence()
        .firstOrNull { it.contains(label) }
        ?.substringAfter(label)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- 权限

    private fun notificationPermission(context: Context): Finding {
        val enabled = context.getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() == true
        return Finding(
            "通知权限",
            if (enabled) STATUS_OK else STATUS_BAD,
            if (enabled) "已开启" else "已关闭，定时任务结果将看不到",
        )
    }

    private fun accessibility(context: Context): Finding {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?: return Finding("无障碍", STATUS_UNKNOWN, "取不到 AccessibilityManager")
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service -> service.resolveInfo.serviceInfo.packageName == context.packageName }
        return Finding(
            "无障碍",
            if (enabled) STATUS_OK else STATUS_BAD,
            if (enabled) "Eta 的辅助服务在启用列表里" else "未启用，GUI 操作会直接失败",
        )
    }

    private fun rootAccess(): Finding {
        val state = RootAccess.state.value
        return when {
            state.isGranted -> Finding("Root", STATUS_OK, "已授权")
            // 没探测过 ≠ 没授权；否则重启后第一次体检会报假异常。
            state.status == RootAccessStatus.UNKNOWN -> Finding("Root", STATUS_UNKNOWN, "尚未探测")
            else -> Finding("Root", STATUS_BAD, "状态 ${state.status}")
        }
    }

    private fun exactAlarm(context: Context): Finding {
        val manager = context.getSystemService(AlarmManager::class.java)
            ?: return Finding("精确闹钟", STATUS_UNKNOWN, "取不到 AlarmManager")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Finding("精确闹钟", STATUS_OK, "系统版本无需授权")
        val allowed = manager.canScheduleExactAlarms()
        return Finding(
            "精确闹钟",
            if (allowed) STATUS_OK else STATUS_BAD,
            if (allowed) "可排精确闹钟" else "已降级为不精确闹钟，触发可能推迟几分钟",
        )
    }

    // ---------------------------------------------------------------- 闹钟注册

    /**
     * 定时任务的闹钟是否真的挂在系统里。
     *
     * 只数 `tag=*walarm*:<ACTION_RUN>` 这一种条目。不能直接 grep 包名：包名会顺带匹配到
     * uid 行、procName、PendingIntent 记录，一次 dump 里同一个闹钟要出现好几次，数出来偏大
     * （本机实测：裸 grep 包名 28 行，真实闹钟 6 个）。这里用脚本自己的常量拼 tag，不写死字符串。
     */
    private fun alarmRegistration(pendingAlarms: Int): Finding {
        val outcome = ScheduledShell.run(
            command = "dumpsys alarm 2>/dev/null | grep -c 'tag=\\*walarm\\*:${ScheduledTaskScheduler.ACTION_RUN}'",
            timeoutSeconds = PROBE_TIMEOUT_SECONDS,
            asRoot = true,
        )
        if (!outcome.ok) return Finding("定时任务闹钟", STATUS_UNKNOWN, "读不到 alarm 服务：${outcome.reason}")
        val count = outcome.stdout.trim().toIntOrNull()
            ?: return Finding("定时任务闹钟", STATUS_UNKNOWN, "dumpsys 输出无法解析")
        return when {
            pendingAlarms == 0 && count == 0 -> Finding("定时任务闹钟", STATUS_OK, "没有需要注册的定时任务")
            count == pendingAlarms -> Finding("定时任务闹钟", STATUS_OK, "已注册 $count 个，与启用任务数一致")
            count == 0 -> Finding(
                "定时任务闹钟",
                STATUS_BAD,
                "应有 $pendingAlarms 个，实际 0 个（进一次定时任务页会自动重建）",
            )
            else -> Finding("定时任务闹钟", STATUS_BAD, "应有 $pendingAlarms 个，实际 $count 个")
        }
    }

    // ---------------------------------------------------------------- tmp 体积

    private fun tmpSize(): Finding {
        val tmp = ScheduledShell.Paths.tmp
        if (!tmp.isDirectory) return Finding("tmp 体积", STATUS_UNKNOWN, "目录不存在")
        val outcome = ScheduledShell.run(
            command = "du -sk '${tmp.absolutePath}' 2>/dev/null | awk '{ print \$1 }'",
            timeoutSeconds = SCRIPT_TIMEOUT_SECONDS,
            asRoot = true,
        )
        val kb = outcome.stdout.trim().toIntOrNull()
            ?: return Finding("tmp 体积", STATUS_UNKNOWN, "du 读不到（${outcome.reason}）")
        val bytes = kb * 1024L
        val text = formatBytes(bytes)
        return if (bytes > TMP_WARN_BYTES) {
            Finding("tmp 体积", STATUS_BAD, "$text，超过 512 MB（可跑一次「清理 tmp」）")
        } else {
            Finding("tmp 体积", STATUS_OK, text)
        }
    }

    // ---------------------------------------------------------------- 负载与温度

    private fun loadAndTemperature(): Finding {
        val script = ScheduledShell.Paths.tool("check-load.sh")
        if (!script.isFile) return Finding("负载与温度", STATUS_UNKNOWN, "缺 ${script.absolutePath}，跳过")
        val outcome = ScheduledShell.run(
            command = "/system/bin/sh '${script.absolutePath}' 2>&1",
            timeoutSeconds = SCRIPT_TIMEOUT_SECONDS,
            asRoot = true,
        )
        if (!outcome.ok) return Finding("负载与温度", STATUS_UNKNOWN, "脚本失败：${outcome.reason}")
        // 脚本输出里有两行固定格式：「结论：【谨慎】只做轻活」与「  依据：1分钟负载 6.01…」。
        val verdict = outcome.stdout.lineSequence()
            .firstOrNull { it.contains("结论：") }
            ?.substringAfter("结论：")
            ?.trim()
        val basis = outcome.stdout.lineSequence()
            .firstOrNull { it.contains("依据：") }
            ?.substringAfter("依据：")
            ?.trim()
            ?.take(60)
        if (verdict == null) return Finding("负载与温度", STATUS_UNKNOWN, "脚本输出里没有结论行")
        // check-load.sh 的三档里「谨慎」既不算异常也不算正常，如实标未验证而不是硬塞进某一档。
        val status = when {
            verdict.contains("别开工") -> STATUS_BAD
            verdict.contains("正常") -> STATUS_OK
            else -> STATUS_UNKNOWN
        }
        return Finding("负载与温度", status, listOfNotNull(verdict, basis).joinToString("；"))
    }

    // ---------------------------------------------------------------- 记忆

    private fun memoryStatus(context: Context): Finding = runCatching {
        // 与 ScheduledActionRunner.memoryStatus 同样的理由：:schedule 进程里仓库是懒初始化的。
        AgentMemoryRepository.init(context)
        val snapshot = AgentMemoryRepository.snapshot()
        val memoryContext = AgentMemoryContextBuilder.build(snapshot = snapshot, contextWindow = null)
        val chars = memoryContext.coreContent.length
        when {
            !memoryContext.enabled -> Finding("记忆", STATUS_BAD, "未初始化")
            chars == 0 -> Finding("记忆", STATUS_BAD, "缺少「# 核心记忆」段，每轮不会注入")
            else -> Finding("记忆", STATUS_OK, "核心 $chars 字符，文件 ${formatBytes(snapshot.byteSize.toLong())}")
        }
    }.getOrElse { failure -> Finding("记忆", STATUS_UNKNOWN, "读取失败（${failure.javaClass.simpleName}）") }

    // ---------------------------------------------------------------- 工具

    private fun timestamp(): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(System.currentTimeMillis()))

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

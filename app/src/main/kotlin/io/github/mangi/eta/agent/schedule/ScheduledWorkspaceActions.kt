package io.github.mangi.eta.agent.schedule

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 依赖工作区脚本的动作：系统快照、大脑备份、清理 tmp、漂移检测。
 *
 * 全部同步执行、全部返回「能直接塞进通知」的一句话（或几行）摘要。
 * **失败必须如实返回「执行失败：原因」**——[ScheduledActionRunner.run] 靠这个前缀把这次执行记成失败，
 * 返回成功文案会让历史记录显示一切正常，而实际什么都没做。
 *
 * 与 [ScheduledActionRunner] 里纯读系统属性的动作分开成文件，是因为这几个都要起 shell：
 * 出错方式、超时、失败文案完全是另一套，混在一堆 `when` 分支里会越写越乱。
 */
internal object ScheduledWorkspaceActions {
    /** 快照脚本可能跑一两分钟（几十上百条 dumpsys），给足时间但也必须设上限。 */
    private const val SNAPSHOT_TIMEOUT_SECONDS = 180L

    /** 备份只做收集打包，不推送，30 秒足够；超过就是脚本卡住了。 */
    private const val BACKUP_TIMEOUT_SECONDS = 60L

    /** 清理 tmp 要先统计再删，两趟 find，20 秒够。 */
    private const val CLEAN_TIMEOUT_SECONDS = 20L

    /** 漂移检测是一次 find + 一次 awk 解析，10 秒内必出结果。 */
    private const val DRIFT_TIMEOUT_SECONDS = 10L

    /** tmp 保留天数：超过就删。 */
    private const val TMP_KEEP_DAYS = 7

    private const val MAX_NOTIFICATION_CHARS = 400

    // ================================================================ 系统快照

    /**
     * 跑 `04-工具-tools/sys-snapshot.sh`，输出落到 `05-系统分析-sysanalyze/SYSTEM-SNAPSHOT-<日期>.md`。
     *
     * 脚本自己只打印到 stdout（「输出的重定向由调用方决定」），所以重定向由这里拼。
     * 同名文件已存在时改名成 `<日期>-<HHmm>.md`，免得同一天跑两次把上一份覆盖掉 ——
     * 快照的价值就在于「改动前后各留一张底片」，被覆盖掉就等于白拍。
     */
    fun snapshot(context: Context): String {
        val paths = ScheduledShell.Paths
        val script = paths.tool("sys-snapshot.sh")
        if (!ScheduledShell.ensureScript(context, script)) return ScheduledShell.missingScript(script)
        if (!paths.analysis.isDirectory) {
            return ScheduledShell.failure("系统分析目录不存在 ${paths.analysis.absolutePath}")
        }

        val target = snapshotTarget()
        val outcome = ScheduledShell.run(
            command = "/system/bin/sh '${script.absolutePath}' > '${target.absolutePath}' 2>/dev/null",
            timeoutSeconds = SNAPSHOT_TIMEOUT_SECONDS,
            asRoot = true,
        )
        if (!outcome.ok) {
            // 写了一半的文件会误导后来的人（看起来"有一份快照"，其实缺节），直接清掉。
            if (target.length() < MIN_SNAPSHOT_BYTES) runCatching { target.delete() }
            return ScheduledShell.failure("${outcome.reason}${suffixFor(outcome)}")
        }
        if (!target.isFile || target.length() < MIN_SNAPSHOT_BYTES) {
            runCatching { target.delete() }
            return ScheduledShell.failure("脚本跑完但没有产出有效内容（${target.name}）")
        }
        return "快照已保存：${target.name}（${ScheduledHealthCheck.formatBytes(target.length())}）"
    }

    /** 小于这个体积基本可以认定是空跑（正常快照有十几 KB）。 */
    private const val MIN_SNAPSHOT_BYTES = 1024L

    private fun snapshotTarget(): File {
        val analysis = ScheduledShell.Paths.analysis
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val plain = File(analysis, "SYSTEM-SNAPSHOT-$day.md")
        if (!plain.exists()) return plain
        val clock = SimpleDateFormat("HHmm", Locale.US).format(Date())
        return File(analysis, "SYSTEM-SNAPSHOT-$day-$clock.md")
    }

    // ================================================================ 大脑备份

    /**
     * 跑 `04-工具-tools/eta-brain-backup.sh --dry-run`：只收集 + 打包 + 生成清单，不推送。
     *
     * 为什么一定是 `--dry-run`：脚本第 5 步要 `git` / `gh`，Android 宿主里没有这两个，
     * 不传这个参数会在一半失败，还会把 `tmp/brain-backup/payload` 留成「看起来准备好了但其实没推」的状态。
     * `--dry-run` 是脚本作者给这个场景留的正式入口（用法注释第一行就写了「只打包，不推送」），
     * 所以这里能只跑收集部分，不需要假装成功也不需要接受一个假的 git 报错。
     *
     * 顺带说明：payload 里含记忆与技能，属于敏感内容，只留在本机 tmp/ 下，本动作不联网。
     */
    fun brainBackup(context: Context): String {
        val script = ScheduledShell.Paths.tool("eta-brain-backup.sh")
        if (!ScheduledShell.ensureScript(context, script)) return ScheduledShell.missingScript(script)

        val outcome = ScheduledShell.run(
            command = "/system/bin/sh '${script.absolutePath}' --dry-run 2>/dev/null",
            timeoutSeconds = BACKUP_TIMEOUT_SECONDS,
            asRoot = true,
        )
        if (!outcome.ok) return ScheduledShell.failure("${outcome.reason}${suffixFor(outcome)}")

        val output = outcome.stdout
        // 脚本在「文件数: N  体积: X」这一行给出打包结果；取不到就说明没跑到那一步。
        val summary = output.lineSequence()
            .firstOrNull { it.contains("文件数:") }
            ?.trim()
            ?: return ScheduledShell.failure("脚本没有输出打包结果，可能中途退出")
        val location = output.lineSequence()
            .firstOrNull { it.contains("打包位置:") }
            ?.substringAfter("打包位置:")
            ?.trim()
        return if (location != null) {
            "已打包（未推送）：$summary · 位置 $location"
        } else {
            "已打包（未推送）：$summary"
        }
    }

    // ================================================================ 清理 tmp

    /**
     * 删掉工作区 `tmp/` 里超过 [TMP_KEEP_DAYS] 天的文件。
     *
     * 安全边界（写死在这里，不接收参数）：
     * 1. 起点固定是 `<工作区>/tmp`；tmp 本身不存在直接返回，不创建。
     * 2. **只删文件，不 `-delete` 目录**：tmp 下有些目录（brain-backup、cloud-build）本身就是工作用的挂点，
     *    删掉会让别的脚本下次跑的时候踩空。文件删完后顺手收掉空的子目录（`-type d -empty -delete`），
     *    但这个 find 的起点仍是 tmp，绝不会碰到 01-… 那些编号目录。
     * 3. 判龄用 `-mtime +7` 而不是 `! -newermt @时间戳`：本机 `/system/bin/find`（toybox）支持 `-newermt`，
     *    但经 root 的 `su -c` 执行时 `find` 解析到 busybox 1.36，它的 find 不认 `-newermt`，会直接报
     *    "unrecognized: -newermt" 并且**什么都不删**——那就是一个永远"成功"却毫无作用的定时任务。
     *    `-mtime` 两个实现都支持，实测一致。
     *
     * 删除前先把数量与体积算出来写进摘要：用户要能从通知里看出「这次删了多少」，
     * 而不是只看到一句"清理完成"。
     */
    fun cleanTmp(): String {
        val tmp = ScheduledShell.Paths.tmp
        if (!tmp.isDirectory) return "跳过：工作区没有 tmp 目录（${tmp.absolutePath}）"

        val quoted = "'${tmp.absolutePath}'"
        // 数量、体积、删除分三趟：本机实测 `find -exec ls/stat { } +` 会把同一个文件报两行（toybox 的
        // 批处理 bug，不管是 ls 还是 stat 都会被调用两次），用 `find | xargs stat` 才只出现一次。
        // 体积只用于摘要，所以宁可分两趟也不要一个算错的数字。
        val countOutcome = ScheduledShell.run(
            command = "cd $quoted && find . -type f -mtime +$TMP_KEEP_DAYS 2>/dev/null | awk 'END { print NR + 0 }'",
            timeoutSeconds = CLEAN_TIMEOUT_SECONDS,
            asRoot = true,
        )
        if (!countOutcome.ok) return ScheduledShell.failure("统计待清理文件失败：${countOutcome.reason}")
        val count = countOutcome.stdout.trim().toIntOrNull()
            ?: return ScheduledShell.failure("统计待清理文件失败：输出无法解析")
        if (count == 0) return "无需清理：tmp 里没有超过 $TMP_KEEP_DAYS 天的文件"

        val sizeOutcome = ScheduledShell.run(
            command = "cd $quoted && find . -type f -mtime +$TMP_KEEP_DAYS 2>/dev/null | " +
                "xargs -r stat -c '%s' 2>/dev/null | awk '{ s += \$1 } END { print s + 0 }'",
            timeoutSeconds = CLEAN_TIMEOUT_SECONDS,
            asRoot = true,
        )
        // 体积只用于摘要，取不到不算失败（数量才是关键信息），标成"体积未知"就好。
        val sizeText = if (sizeOutcome.ok) {
            sizeOutcome.stdout.trim().toLongOrNull()?.let(ScheduledHealthCheck::formatBytes) ?: "体积未知"
        } else {
            "体积未知"
        }

        val deleteOutcome = ScheduledShell.run(
            command = "cd $quoted && find . -type f -mtime +$TMP_KEEP_DAYS -delete 2>/dev/null; " +
                "find . -depth -type d -empty -delete 2>/dev/null; echo deleted",
            timeoutSeconds = CLEAN_TIMEOUT_SECONDS,
            asRoot = true,
        )
        if (!deleteOutcome.ok) return ScheduledShell.failure("删除阶段失败：${deleteOutcome.reason}")

        return "已清理 $count 个文件，释放 $sizeText（保留 $TMP_KEEP_DAYS 天内与目录骨架）"
    }

    // ================================================================ 漂移检测

    /**
     * 拿当前状态和 `05-系统分析-sysanalyze/` 里最新一份快照的若干关键项逐条比对。
     *
     * 比较什么、为什么不比别的：
     * - `ro.build.display.id` / `ro.build.version.incremental` / `ro.build.id`：系统更新最直接的证据；
     * - `/data/adb/modules` 下的模块目录：比对时只数「快照里列过的那些模块还在不在」，
     *   新装的模块不算漂移（新增不是坏事，缺了才是）；
     * - `zygisksu 模块目录` / `tricky_store` / `playintegrityfix`：root 隐藏栈最常被更新破坏的三处。
     *
     * 快照里的内存、监听端口、开机时长是实时量，天然会变，明确不比，否则每次都报一堆差异等于没报。
     * dpi 等 `settings` 值没纳入：读取要走 `settings get`（su 下的 path 不一定有 `settings` 二进制），
     * 掉档时 dpi 的漂移交给「系统快照 + 人工 diff」这条现有流程，不在这里冒险。
     *
     * 实现方式：awk 脚本压缩成一行字符串直接拼进命令，不落临时文件 ——
     * 定时任务跑在 `:schedule` 进程里，不该为了比对一次就往磁盘写脚本，也不好清理。
     */
    fun driftCheck(): String {
        val snapshot = latestSnapshot()
            ?: return "未执行：${ScheduledShell.Paths.analysis.absolutePath} 下没有 SYSTEM-SNAPSHOT-*.md"
        val outcome = ScheduledShell.run(
            command = "/system/bin/awk '${driftAwkScript()}' '${snapshot.absolutePath}' 2>/dev/null",
            timeoutSeconds = DRIFT_TIMEOUT_SECONDS,
            asRoot = true,
        )
        if (!outcome.ok) return ScheduledShell.failure("比对失败：${outcome.reason}")
        return summarize(outcome.stdout, snapshot.name)
    }

    private fun latestSnapshot(): File? = runCatching {
        val analysis = ScheduledShell.Paths.analysis
        if (!analysis.isDirectory) return null
        analysis.listFiles { file -> file.isFile && file.name.startsWith("SYSTEM-SNAPSHOT-") && file.name.endsWith(".md") }
            ?.maxByOrNull { it.name }
    }.getOrNull()

    /**
     * 一次式的 awk 脚本：解析快照表格，把每项期望值和实时值比一遍，输出
     * `OK<TAB>标签<TAB>值` / `DRIFT<TAB>标签<TAB>期望<TAB>实际` / `MODTOTAL<TAB>总数<TAB>缺失数`。
     *
     * 写法约束（都是踩出来的）：
     * 1. **控制语句里不能有换行**：本机 awk 会把 `{` 后换行再写语句判成 "illegal statement"，
     *    所以三元/分支都写成单行的 `if(){...}else{...}`。
     * 2. **不用 `$` 做字符串拼接**：`$` 在 Kotlin 里要写 `\$`，穿过 sh 与 awk 后还剩不剩得看运气；
     *    输出一律用 `printf` + awk 变量，不引入额外转义。
     * 3. **正则全部用字面量 / 纯 ASCII 字符类 / index()**：不写 `\$`、`\\\\` 这类二次转义。
     * 4. **中文表头用「非 ASCII 就跳过」判断**，而不是写 CJK 正则：toybox awk 对 CJK 的 `|` 交替不稳。
     */
    private fun driftAwkScript(): String = listOf(
        "function trim(s){gsub(/^[ \\t\\r]+/,\"\",s);gsub(/[ \\t\\r]+$/,\"\",s);return s}",
        "function clean(s){gsub(/`/,\"\",s);return trim(s)}",
        "function gp(k,   r,cmd){cmd=\"getprop \" k \" 2>/dev/null\";cmd|getline r;close(cmd);r=trim(r);return (r==\"\"?\"?\":r)}",
        "function ex(p){return (system(\"test -d \\\"\" p \"\\\"\")==0?\"yes\":\"no\")}",
        "function rec(label,want,live){",
        "if(live==\"\"){return}",
        "if(live==want){printf \"OK\\t%s\\t%s\\n\",label,want}",
        "else{printf \"DRIFT\\t%s\\t%s\\t%s\\n\",label,want,live}}",
        // 标题行切换当前节；表头分隔行（|---|---|）直接跳过，不做 in_table 状态机 —— 状态机太长，容易写错。
        "/^## /{section=$0;next}",
        "/^### /{section=section \"|\" $0;next}",
        "/^[ \\t]*\\|/ {",
        "if(index($0,\"---\")>0){next}",
        "line=$0;sub(/^[ \\t]*\\|/,\"\",line);",
        "n=split(line,c,\"|\");if(n<2){next}",
        "label=clean(c[1]);want=clean(c[2]);",
        "if(label==\"\"){next}",
        // 表头行：第一列是「属性 / 命名空间 / 文件 / 检查项 / 目录」这类中文表头，直接跳。
        "if(label!~/^[A-Za-z0-9_.:\\/-]+$/ && label!~/^\\//){next}",
        // 2.1 节：只收集模块目录名（纯 ASCII，可安全用于 grep 判断）。
        "if(section ~ /2\\.1/){if(label~/^[A-Za-z0-9_.:-]+$/){mods[++nm]=label}next}",
        "live=\"\";",
        "if(section ~ /^## 1\\. /){",
        "if(label==\"ro.build.display.id\"){live=gp(label)}",
        "else if(label==\"ro.build.version.incremental\"){live=gp(label)}",
        "else if(label==\"ro.build.id\"){live=gp(label)}}",
        "else if(section ~ /2\\.2/){",
        "if(label==\"zygisksu 模块目录\"){live=ex(\"/data/adb/modules/zygisksu\")}",
        "else if(label==\"/data/adb/zygisksu\"){live=ex(\"/data/adb/zygisksu\")}",
        "else if(label==\"tricky_store\"){live=ex(\"/data/adb/modules/tricky_store\")}",
        "else if(label==\"playintegrityfix\"){live=ex(\"/data/adb/modules/playintegrityfix\")}}",
        "if(live==\"\"){next}",
        "rec(label,want,live)}",
        "END{miss=0;for(i=1;i<=nm;i++){if(system(\"test -d \\\"/data/adb/modules/\" mods[i] \"\\\"\")!=0){miss++}}printf \"MODTOTAL\\t%d\\t%d\\n\",nm,miss}",
    ).joinToString("")

    /** 把 awk 的原始行翻译成人话；没有差异时也明确说「一致」，不能空手返回。 */
    private fun summarize(raw: String, snapshotName: String): String {
        val okItems = mutableListOf<String>()
        val drifts = mutableListOf<String>()
        var moduleTotal = -1
        var moduleMissing = 0

        raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val cells = line.split('\t')
            when (cells.firstOrNull()) {
                "OK" -> if (cells.size >= 3) okItems += "${cells[1]}=${cells[2]}"
                "DRIFT" -> if (cells.size >= 4) drifts += "${cells[1]}（快照 ${cells[2]} → 现在 ${cells[3]}）"
                "MODTOTAL" -> {
                    moduleTotal = cells.getOrNull(1)?.toIntOrNull() ?: -1
                    moduleMissing = cells.getOrNull(2)?.toIntOrNull() ?: 0
                }
            }
        }

        if (moduleTotal < 0 && okItems.isEmpty() && drifts.isEmpty()) {
            return ScheduledShell.failure("快照 $snapshotName 里没找到可比对的关键项（表格格式可能变了）")
        }
        if (moduleMissing > 0) drifts += "模块清单缺 $moduleMissing 个（快照里 ${moduleTotal} 个）"

        val head = "对比 $snapshotName："
        if (drifts.isEmpty()) {
            val modules = if (moduleTotal > 0) "，$moduleTotal 个模块都在" else ""
            return head + "无漂移（${okItems.size} 项关键值一致$modules）"
        }
        val text = buildString {
            append(head).append("发现 ").append(drifts.size).append(" 处变化 → ")
            append(drifts.joinToString("；"))
        }
        return if (text.length <= MAX_NOTIFICATION_CHARS) text else text.take(MAX_NOTIFICATION_CHARS - 1) + "…"
    }

    // ================================================================ 公共

    /** 失败详细说明里补一句退出码/耗时，便于用户判断是脚本坏了还是超时。 */
    private fun suffixFor(outcome: ScheduledShell.Outcome): String = when {
        outcome.timedOut -> ""
        outcome.exitCode == 0 -> ""
        else -> "（退出码 ${outcome.exitCode}，耗时 ${outcome.durationMs / 1000} 秒）"
    }
}

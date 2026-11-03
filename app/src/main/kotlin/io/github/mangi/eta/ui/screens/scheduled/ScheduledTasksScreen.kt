package io.github.mangi.eta.ui.screens.scheduled

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.basic.Switch
import androidx.compose.foundation.clickable
import io.github.mangi.eta.agent.schedule.ScheduledTaskReceiver
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.schedule.ScheduledActionKind
import io.github.mangi.eta.agent.schedule.ScheduledRunLog
import io.github.mangi.eta.agent.schedule.ScheduledRepeat
import io.github.mangi.eta.agent.schedule.ScheduledTask
import io.github.mangi.eta.agent.schedule.ScheduledTaskSchedule
import io.github.mangi.eta.agent.schedule.ScheduledTaskScheduler
import io.github.mangi.eta.agent.schedule.ScheduledTaskStore
import io.github.mangi.eta.agent.schedule.ScheduledTrigger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaPreferenceRow
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaRadioButtonPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.model.RecentUsageMessageWindow
import io.github.mangi.eta.ui.model.TokenUsageSnapshot
import io.github.mangi.eta.ui.model.formatUsageTokenCount
import io.github.mangi.eta.ui.model.loadTokenUsageSnapshot

import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 新建任务时的默认时间：09:00，够早也够日常。 */
private const val DefaultHour = 9
private const val DefaultMinute = 0

/** 按「几点几分」排序，列表顺序与用户的一天一致。 */
private val TaskOrder: Comparator<ScheduledTask> = compareBy({ it.hour }, { it.minute })

/**
 * 编辑弹窗内容区的最高高度。
 *
 * 动作选项目前有 9 条，加上时间滚轮、小节标题与「保存/取消」，整屏放不下。Miuix 的 WindowDialog
 * 只对自身做边距限制，**不会**给 content 加滚动，所以这里显式给一个上限并配 verticalScroll。
 * 取 460dp：常见的 6.1–6.8 寸屏在对话框上下边距之后仍剩 500dp 以上，460 既够放下时间滚轮 + 3~4 条动作，
 * 也保证「保存/取消」始终留在可见区域内（用户不需要先滚到底才能保存）。
 */
private val EditorMaxContentHeight = 460.dp

/**
 * 定时任务：每天到点让 Agent 自动做一件固定的事。
 *
 * 页面只负责编辑「时间 + 动作 + 开关」三件事；真正的落盘与闹钟注册在
 * [ScheduledTaskStore] / [ScheduledTaskScheduler]，这里每次改动都写穿到 Store 后
 * 重新读一次，保证界面显示的就是磁盘上的状态。
 */
@Composable
internal fun ScheduledTasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf(emptyList<ScheduledTask>()) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    var pendingDelete by remember { mutableStateOf<ScheduledTask?>(null) }
    var runs by remember { mutableStateOf(emptyList<ScheduledRunLog.Record>()) }
    /** 已展开执行历史的任务 id；默认全部折叠。 */
    var expandedHistory by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 长按弹菜单的任务；极简版把编辑/历史/删除都收进这里。 */
    var longPressTask by remember { mutableStateOf<ScheduledTask?>(null) }
    // 用量卡片：打开页面时读一次本地库；不联网、不调模型，所以没有轮询的必要。
    var usage by remember { mutableStateOf(TokenUsageSnapshot()) }
    // 与输入框上方的上下文按钮保持一致：token 数字跟随当前界面语言做数字分组。
    val locale = LocalConfiguration.current.locales[0]

    val reloadTasks = {
        tasks = ScheduledTaskStore.load(context).sortedWith(TaskOrder)
        runs = ScheduledRunLog.load(context)
    }

    // 增删改统一走这里：先落盘，再按启用状态注册/取消闹钟，最后重新读盘刷新界面。
    val persist: (ScheduledTask) -> Unit = { task ->
        ScheduledTaskStore.upsert(context, task)
        if (task.enabled) {
            ScheduledTaskScheduler.schedule(context, task)
        } else {
            ScheduledTaskScheduler.cancel(context, task.id)
        }
        reloadTasks()
    }

    val removeTask: (ScheduledTask) -> Unit = { task ->
        ScheduledTaskStore.delete(context, task.id)
        ScheduledTaskScheduler.cancel(context, task.id)
        reloadTasks()
    }

    LaunchedEffect(context) {
        reloadTasks()
        // 打开页面就按当前配置重建一次闹钟：改过系统时间、或任务数据来自外部写入时，
        // 一进页面就是已生效状态，不必再等一次冷启动。
        ScheduledTaskScheduler.rescheduleAll(context)
        usage = withContext(Dispatchers.IO) {
            runCatching { loadTokenUsageSnapshot(EtaDatabase.get(context.applicationContext)) }
                .getOrDefault(TokenUsageSnapshot())
        }
    }

    // 页面停留期间定时重读：任务在 :schedule 进程里跑，写的是 SharedPreferences，
    // 本页不主动轮询就永远看不到「刚刚执行过」和最新 last_run_at（用户实测：执行完了列表不动）。
    // 只读两个小文件，开销可忽略；用 repeatOnLifecycle 绑到 RESUMED，
    // 切后台或离开页面即停止，不会常驻轮询。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(5_000L)
                reloadTasks()
            }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_scheduled_tasks),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = {
                    editing = null
                    showEditor = true
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.scheduled_tasks_add),
                )
            }
        },
    ) {
        if (tasks.isEmpty()) {
            item(key = "scheduled_tasks_empty") {
                ListEmptyState(
                    title = stringResource(R.string.scheduled_tasks_empty),
                    icon = Icons.Rounded.Schedule,
                    action = {
                        EtaTextButton(
                            text = stringResource(R.string.scheduled_tasks_add),
                            onClick = {
                                editing = null
                                showEditor = true
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    },
                )
            }
        } else {
            // 分组标题与页面大标题重复，这里不再渲染，直接从任务列表开始。
            // 最近一次执行记录：状态行与图标颜色都要用，抽出来避免同一查询写两遍。
            fun latestRunOf(task: ScheduledTask) = runs.firstOrNull { it.taskId == task.id }

            tasks.forEach { task ->
                item(key = "scheduled_task_${task.id}") {
                    val latest = latestRunOf(task)
                    EtaPreferenceGroup {
                        // 为什么不用 EtaSwitchPreference：它内部用 Modifier.toggleable 接管了整行点击，
                        // 我在外面加的 combinedClickable 会被覆盖 —— 长按菜单因此完全失效（用户实测）。
                        // 改用 EtaPreferenceRow + endActions 放开关：开关只占右侧开关本身，
                        // 左边整行归我控制，点=编辑、长按=菜单，两个都不丢。
                        EtaPreferenceRow(
                            title = taskSummaryText(task),
                            summary = ScheduledTaskSchedule.describe(task),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Schedule,
                                    tint = if (task.enabled) EtaPreferenceColors.Blue
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            },
                            interaction = Modifier.combinedClickable(
                                onClick = {
                                    editing = task
                                    showEditor = true
                                },
                                onLongClick = { longPressTask = task },
                            ),
                            endActions = {
                                EtaSwitch(
                                    checked = task.enabled,
                                    onCheckedChange = { checked -> persist(task.copy(enabled = checked)) },
                                    enabled = true,
                                )
                            },
                        )

                        // 历史默认折叠：只显示最近 1 条（就是"最近执行"），
                        // 点「展开」才看更多。任务一多时不会一屏全是历史。
                        if (expandedHistory.contains(task.id)) {
                            runs.filter { it.taskId == task.id }.take(5).forEach { record ->
                                EtaPreferenceDivider()
                                EtaPreference(
                                    title = runTimeText(record),
                                    summary = record.message.ifBlank { if (record.ok) "成功" else "失败" },
                                    startAction = {
                                        EtaPreferenceIcon(
                                            icon = if (record.ok) Icons.Rounded.CheckCircle
                                            else Icons.Rounded.ErrorOutline,
                                            tint = if (record.ok) EtaPreferenceColors.Green
                                            else MiuixTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }

                        // 展开/收起：只有真有历史时才显示
                        val total = runs.count { it.taskId == task.id }
                        if (total > 0) {
                            EtaPreferenceDivider()
                            EtaArrowPreference(
                                title = if (expandedHistory.contains(task.id)) "收起执行历史" else "展开执行历史（$total 条）",
                                startAction = {
                                    EtaPreferenceIcon(
                                        icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                },
                                onClick = {
                                    expandedHistory = if (expandedHistory.contains(task.id)) {
                                        expandedHistory - task.id
                                    } else {
                                        expandedHistory + task.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 页面底部的用量卡片：任务跑起来之后用户最关心的就是「今天/最近花掉了多少」。
        item(key = "scheduled_tasks_usage") {
            EtaPreferenceGroupTitle(stringResource(R.string.token_usage_section_title))
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.token_usage_scheduled_title),
                    summary = if (usage.recentCalls > 0) {
                        stringResource(
                            R.string.token_usage_scheduled_summary,
                            RecentUsageMessageWindow,
                            formatUsageTokenCount(usage.recentInputTokens, locale),
                            formatUsageTokenCount(usage.recentOutputTokens, locale),
                            usage.recentCalls,
                        )
                    } else {
                        stringResource(R.string.token_usage_scheduled_empty)
                    },
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.Rounded.DataUsage,
                            tint = EtaPreferenceColors.Blue,
                        )
                    },
                )
            }
        }
    }

    // ---------- 长按菜单：极简版把编辑/历史/删除都收在这里 ----------
    longPressTask?.let { task ->
        val latest = runs.firstOrNull { it.taskId == task.id }
        EtaWindowDialog(
            show = true,
            title = taskSummaryText(task),
            summary = "${ScheduledTaskSchedule.describe(task)} · ${runBriefText(latest)}",
            onDismissRequest = { longPressTask = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TaskMenuRow("编辑时间与动作") {
                    longPressTask = null
                    editing = task
                    showEditor = true
                }
                TaskMenuRow("立即执行一次", enabled = task.enabled) {
                    longPressTask = null
                    // 与定时触发走同一个入口（:schedule 进程里的 ScheduledTaskReceiver），
                    // 不另写一套执行逻辑，避免"手动能跑、定时不能跑"这种分叉。
                    runCatching {
                        context.startService(
                            android.content.Intent(context, ScheduledTaskReceiver::class.java)
                                .putExtra(ScheduledTaskScheduler.EXTRA_TASK_ID, task.id),
                        )
                    }
                }
                TaskMenuRow("删除", danger = true) {
                    longPressTask = null
                    pendingDelete = task
                }
                Spacer(Modifier.height(8.dp))
                MiuixDialogActions(
                    confirmText = "关闭",
                    onCancel = { longPressTask = null },
                    onConfirm = { longPressTask = null },
                )
            }
        }
    }


    if (showEditor) {
        ScheduleEditorDialog(
            task = editing,
            onDismiss = {
                showEditor = false
                editing = null
            },
            onSave = { saved ->
                persist(saved)
                showEditor = false
                editing = null
            },
        )
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.scheduled_tasks_delete),
            summary = stringResource(R.string.scheduled_tasks_delete_confirm, taskTimeText(deleteTarget)),
            onDismissRequest = { pendingDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.scheduled_tasks_delete),
                destructive = true,
                onCancel = { pendingDelete = null },
                onConfirm = {
                    removeTask(deleteTarget)
                    pendingDelete = null
                },
            )
        }
    }
}

/**
 * 新建 / 编辑任务的弹窗：时间用 Miuix 自带的 [NumberPicker]（两列滚轮，时 0–23、分 0–59），
 * 动作从 [ScheduledActionKind.entries] 里单选。项目里没有 Material3，也没有现成的 TimePicker，
 * 所以不引新依赖，直接用 Miuix 组件。
 */
@Composable
private fun ScheduleEditorDialog(
    task: ScheduledTask?,
    onDismiss: () -> Unit,
    onSave: (ScheduledTask) -> Unit,
) {
    var hour by remember(task?.id) { mutableStateOf(task?.hour ?: DefaultHour) }
    var minute by remember(task?.id) { mutableStateOf(task?.minute ?: DefaultMinute) }
    var action by remember(task?.id) { mutableStateOf(task?.action ?: ScheduledActionKind.DEVICE_STATUS) }
    var prompt by remember(task?.id) { mutableStateOf(task?.prompt.orEmpty()) }
    var repeat by remember(task?.id) { mutableStateOf(task?.repeat ?: ScheduledRepeat.DAILY) }
    var dayOfWeek by remember(task?.id) { mutableStateOf(task?.dayOfWeek ?: java.util.Calendar.MONDAY) }
    var dayOfMonth by remember(task?.id) { mutableStateOf(task?.dayOfMonth ?: 1) }
    var interval by remember(task?.id) { mutableStateOf(task?.interval ?: 1) }

    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.settings_scheduled_tasks_title),
        summary = stringResource(R.string.settings_scheduled_tasks_summary),
        onDismissRequest = onDismiss,
    ) {
        // 动作现在有 9 种，单选列表比弹窗还高：Miuix 的 WindowDialog 内容区不会自己滚，
        // 这里必须给一个高度上限 + verticalScroll，否则小屏上「保存/取消」会被挤出屏幕外。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = EditorMaxContentHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            EditorSectionLabel(stringResource(R.string.scheduled_tasks_repeat_title))
            ScheduledRepeat.entries.forEach { option ->
                EtaRadioButtonPreference(
                    title = repeatLabel(option),
                    selected = repeat == option,
                    onClick = { repeat = option },
                )
            }
            // 周期相关的附加参数：周几 / 几号 / N 值。
            when (repeat) {
                ScheduledRepeat.WEEKLY -> {
                    EditorSectionLabel(stringResource(R.string.scheduled_tasks_weekday_title))
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = EtaPreferenceDefaults.SidePadding)) {
                        listOf(
                            java.util.Calendar.MONDAY to "一",
                            java.util.Calendar.TUESDAY to "二",
                            java.util.Calendar.WEDNESDAY to "三",
                            java.util.Calendar.THURSDAY to "四",
                            java.util.Calendar.FRIDAY to "五",
                            java.util.Calendar.SATURDAY to "六",
                            java.util.Calendar.SUNDAY to "日",
                        ).forEach { (value, label) ->
                            EtaTextButton(
                                text = label,
                                onClick = { dayOfWeek = value },
                                colors = if (dayOfWeek == value) {
                                    ButtonDefaults.textButtonColorsPrimary()
                                } else {
                                    ButtonDefaults.textButtonColors()
                                },
                            )
                        }
                    }
                }
                ScheduledRepeat.MONTHLY -> {
                    EditorSectionLabel(stringResource(R.string.scheduled_tasks_day_of_month_title))
                    NumberPicker(
                        value = dayOfMonth,
                        onValueChange = { dayOfMonth = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = EtaPreferenceDefaults.SidePadding),
                        range = 1..31,
                        label = { "$it 日" },
                        visibleItemCount = 3,
                        wrapAround = true,
                    )
                }
                ScheduledRepeat.EVERY_N_DAYS, ScheduledRepeat.EVERY_N_HOURS -> {
                    EditorSectionLabel(stringResource(R.string.scheduled_tasks_interval_title))
                    NumberPicker(
                        value = interval,
                        onValueChange = { interval = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = EtaPreferenceDefaults.SidePadding),
                        range = 1..30,
                        label = {
                            if (repeat == ScheduledRepeat.EVERY_N_DAYS) "每 $it 天" else "每 $it 小时"
                        },
                        visibleItemCount = 3,
                        wrapAround = false,
                    )
                }
                else -> Unit
            }

            EditorSectionLabel(stringResource(R.string.scheduled_tasks_time_title))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NumberPicker(
                    value = hour,
                    onValueChange = { hour = it },
                    modifier = Modifier.weight(1f),
                    range = 0..23,
                    label = { it.toString().padStart(2, '0') },
                    visibleItemCount = 3,
                    wrapAround = true,
                )
                NumberPicker(
                    value = minute,
                    onValueChange = { minute = it },
                    modifier = Modifier.weight(1f),
                    range = 0..59,
                    label = { it.toString().padStart(2, '0') },
                    visibleItemCount = 3,
                    wrapAround = true,
                )
            }

            EditorSectionLabel(stringResource(R.string.scheduled_tasks_action_title))
            ScheduledActionKind.entries.forEach { kind ->
                EtaRadioButtonPreference(
                    title = stringResource(actionLabelRes(kind)),
                    selected = action == kind,
                    onClick = { action = kind },
                )
            }

            // 选「自定义指令」时展开输入框：这里写什么，到点就让 Agent 做什么。
            if (action == ScheduledActionKind.CUSTOM_PROMPT) {
                EditorSectionLabel(stringResource(R.string.scheduled_tasks_prompt_label))
                TextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EtaPreferenceDefaults.SidePadding),
                )
                Text(
                    text = stringResource(R.string.scheduled_tasks_prompt_placeholder),
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(
                        start = EtaPreferenceDefaults.SidePadding,
                        end = EtaPreferenceDefaults.SidePadding,
                        top = 4.dp,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            MiuixDialogActions(
                confirmText = stringResource(R.string.scheduled_tasks_save),
                cancelText = stringResource(R.string.scheduled_tasks_cancel),
                onCancel = onDismiss,
                onConfirm = {
                    onSave(
                        ScheduledTask(
                            id = task?.id ?: ScheduledTaskStore.newId(),
                            // 当前界面只做「每天定时」；事件型任务的编辑入口下一版补 UI。
                            trigger = task?.trigger ?: ScheduledTrigger.DAILY_TIME,
                            hour = hour,
                            minute = minute,
                            action = action,
                            enabled = task?.enabled ?: true,
                            lastRunAtMillis = task?.lastRunAtMillis,
                            prompt = if (action == ScheduledActionKind.CUSTOM_PROMPT) prompt.trim() else "",
                            repeat = repeat,
                            dayOfWeek = dayOfWeek,
                            dayOfMonth = dayOfMonth,
                            interval = interval,
                        ),
                    )
                },
            )
        }
    }
}

/** 弹窗里的小节标题；页面级的分组标题内边距是按卡片对齐的，在弹窗里太靠里，这里另写一个。 */
@Composable
private fun EditorSectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(
            start = EtaPreferenceDefaults.SidePadding,
            end = EtaPreferenceDefaults.SidePadding,
            top = 12.dp,
            bottom = 4.dp,
        ),
    )
}

/** 最近一次执行的一句话摘要：从没跑过 / 成功 / 失败。 */
@Composable
private fun runSummaryText(record: ScheduledRunLog.Record?): String = when {
    record == null -> stringResource(R.string.scheduled_tasks_history_never)
    record.ok -> stringResource(R.string.scheduled_tasks_history_ok, runTimeText(record))
    else -> stringResource(R.string.scheduled_tasks_history_failed, runTimeText(record))
}

/**
 * 卡片副标题用的**精简**执行状态。
 *
 * 与 [runSummaryText] 的区别：那个是展开历史区里的完整句式（"上次执行于 …"），
 * 拼进「每天 08:30 · …」这种副标题会太长、被截断，反而看不到关键信息。
 * 这里只给最短结论：从未 / ✓ 07:50 / ✗ 07:50。
 */
@Composable
private fun runBriefText(record: ScheduledRunLog.Record?): String = when {
    record == null -> stringResource(R.string.scheduled_tasks_brief_never)
    record.ok -> "✓ ${runTimeShort(record)}"
    else -> "✗ ${runTimeShort(record)}"
}

/** HH:mm，用于卡片副标题（同一天不需要年月日）。 */
private fun runTimeShort(record: ScheduledRunLog.Record): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = record.atMillis }
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val minute = calendar.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
    return "$hour:$minute"
}

/** 执行时间：MM-dd HH:mm。 */
private fun runTimeText(record: ScheduledRunLog.Record): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = record.atMillis }
    val month = (calendar.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val minute = calendar.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
    return "$month-$day $hour:$minute"
}

/** HH:mm；不用 String.format，避免跟随系统 Locale 出现阿拉伯数字变体。 */
private fun taskTimeText(task: ScheduledTask): String = ScheduledTaskSchedule.describe(task)

/** 列表摘要：内置动作显示动作名；自定义指令显示指令内容（截断，太长会把这一行撑爆）。 */
@Composable
private fun taskSummaryText(task: ScheduledTask): String = when (task.action) {
    ScheduledActionKind.CUSTOM_PROMPT -> {
        val text = task.prompt.trim().ifEmpty { stringResource(R.string.scheduled_tasks_kind_custom) }
        stringResource(R.string.scheduled_tasks_prompt_summary_prefix, text.take(60))
    }
    else -> stringResource(actionLabelRes(task.action))
}

@Composable
private fun repeatLabel(repeat: ScheduledRepeat): String = when (repeat) {
    ScheduledRepeat.DAILY -> stringResource(R.string.scheduled_repeat_daily)
    ScheduledRepeat.WEEKLY -> stringResource(R.string.scheduled_repeat_weekly)
    ScheduledRepeat.MONTHLY -> stringResource(R.string.scheduled_repeat_monthly)
    ScheduledRepeat.EVERY_N_DAYS -> stringResource(R.string.scheduled_repeat_every_n_days)
    ScheduledRepeat.EVERY_N_HOURS -> stringResource(R.string.scheduled_repeat_every_n_hours)
}

@StringRes
private fun actionLabelRes(kind: ScheduledActionKind): Int = when (kind) {
    ScheduledActionKind.DEVICE_STATUS -> R.string.scheduled_tasks_action_device_status
    ScheduledActionKind.MEMORY_STATUS -> R.string.scheduled_tasks_action_memory_status
    ScheduledActionKind.PERMISSIONS_CHECK -> R.string.scheduled_tasks_action_permissions_check
    ScheduledActionKind.SELF_CHECK -> R.string.scheduled_tasks_action_self_check
    ScheduledActionKind.SNAPSHOT -> R.string.scheduled_tasks_action_snapshot
    ScheduledActionKind.BRAIN_BACKUP -> R.string.scheduled_tasks_action_brain_backup
    ScheduledActionKind.CLEAN_TMP -> R.string.scheduled_tasks_action_clean_tmp
    ScheduledActionKind.DRIFT_CHECK -> R.string.scheduled_tasks_action_drift_check
    ScheduledActionKind.CUSTOM_PROMPT -> R.string.scheduled_tasks_kind_custom
}

/** 长按菜单里的一行（极简：只有文字，危险操作用红色）。 */
@Composable
private fun TaskMenuRow(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = when {
                !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                danger -> MiuixTheme.colorScheme.error
                else -> MiuixTheme.colorScheme.onSurface
            },
        )
    }
}

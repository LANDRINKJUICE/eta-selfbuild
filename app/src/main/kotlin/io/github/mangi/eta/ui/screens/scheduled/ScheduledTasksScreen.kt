package io.github.mangi.eta.ui.screens.scheduled

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import io.github.mangi.eta.agent.schedule.ScheduledTask
import io.github.mangi.eta.agent.schedule.ScheduledTaskScheduler
import io.github.mangi.eta.agent.schedule.ScheduledTaskStore
import io.github.mangi.eta.agent.schedule.ScheduledTrigger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaRadioButtonPreference
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.model.RecentUsageMessageWindow
import io.github.mangi.eta.ui.model.TokenUsageSnapshot
import io.github.mangi.eta.ui.model.formatUsageTokenCount
import io.github.mangi.eta.ui.model.loadTokenUsageSnapshot

import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
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
    var expandedTaskId by remember { mutableStateOf<String?>(null) }
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
            tasks.forEach { task ->
                item(key = "scheduled_task_${task.id}") {
                    EtaPreferenceGroup {
                        // 一行读全：几点几分 + 做什么；点进去改时间或换动作。
                        EtaArrowPreference(
                            title = taskTimeText(task),
                            summary = taskSummaryText(task),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Schedule,
                                    tint = EtaPreferenceColors.Blue,
                                    enabled = task.enabled,
                                )
                            },
                            onClick = {
                                editing = task
                                showEditor = true
                            },
                        )

                        EtaPreferenceDivider()
                        EtaSwitchPreference(
                            title = stringResource(R.string.scheduled_tasks_enabled),
                            checked = task.enabled,
                            onCheckedChange = { checked -> persist(task.copy(enabled = checked)) },
                        )

                        EtaPreferenceDivider()
                        // 最近一次执行结果：定时任务最怕"静默失败"，这里必须看得见。
                        val latestRun = runs.firstOrNull { it.taskId == task.id }
                        EtaArrowPreference(
                            title = stringResource(R.string.scheduled_tasks_history_title),
                            summary = runSummaryText(latestRun),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.History,
                                    tint = if (latestRun?.ok == false) MiuixTheme.colorScheme.error else EtaPreferenceColors.Green,
                                )
                            },
                            onClick = {
                                expandedTaskId = if (expandedTaskId == task.id) null else task.id
                            },
                        )

                        if (expandedTaskId == task.id) {
                            val history = runs.filter { it.taskId == task.id }.take(5)
                            if (history.isEmpty()) {
                                EtaPreferenceDivider()
                                EtaPreference(
                                    title = stringResource(R.string.scheduled_tasks_history_empty),
                                    summary = "",
                                )
                            }
                            history.forEach { record ->
                                EtaPreferenceDivider()
                                EtaPreference(
                                    title = runTimeText(record),
                                    summary = record.message.ifBlank { if (record.ok) "成功" else "失败" },
                                )
                            }
                        }

                        EtaPreferenceDivider()
                        EtaPreference(
                            title = stringResource(R.string.scheduled_tasks_delete),
                            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                            startAction = {
                                EtaPreferenceIcon(
                                    icon = Icons.Rounded.Delete,
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            },
                            onClick = { pendingDelete = task },
                        )
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
private fun taskTimeText(task: ScheduledTask): String =
    task.hour.toString().padStart(2, '0') + ":" + task.minute.toString().padStart(2, '0')

/** 列表摘要：内置动作显示动作名；自定义指令显示指令内容（截断，太长会把这一行撑爆）。 */
@Composable
private fun taskSummaryText(task: ScheduledTask): String = when (task.action) {
    ScheduledActionKind.CUSTOM_PROMPT -> {
        val text = task.prompt.trim().ifEmpty { stringResource(R.string.scheduled_tasks_kind_custom) }
        stringResource(R.string.scheduled_tasks_prompt_summary_prefix, text.take(60))
    }
    else -> stringResource(actionLabelRes(task.action))
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

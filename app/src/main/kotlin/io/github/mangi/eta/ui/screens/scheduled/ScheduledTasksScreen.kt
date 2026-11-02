package io.github.mangi.eta.ui.screens.scheduled

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.schedule.ScheduledActionKind
import io.github.mangi.eta.agent.schedule.ScheduledTask
import io.github.mangi.eta.agent.schedule.ScheduledTaskScheduler
import io.github.mangi.eta.agent.schedule.ScheduledTaskStore
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

    val reloadTasks = {
        tasks = ScheduledTaskStore.load(context).sortedWith(TaskOrder)
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
            item(key = "section_scheduled_tasks") {
                EtaPreferenceGroupTitle(stringResource(R.string.settings_scheduled_tasks_title))
            }
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
 * 动作三选一。项目里没有 Material3，也没有现成的 TimePicker，所以不引新依赖，直接用 Miuix 组件。
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
        Column(modifier = Modifier.fillMaxWidth()) {
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
    ScheduledActionKind.CUSTOM_PROMPT -> R.string.scheduled_tasks_kind_custom
}

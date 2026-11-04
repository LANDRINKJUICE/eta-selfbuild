package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import kotlin.math.abs
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceRow
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillGroupUi
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CardHorizontalPadding = 16.dp
private val CardBottomPadding = 16.dp

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    /** 正在改名的分组；null 表示没开改名弹窗。 */
    var renameGroup by remember { mutableStateOf<SkillGroupUi?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val operationPending = state.isImporting || state.busySkillId != null
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(AgentSkillsAction.ImportZip(uri.toString()))
    }
    val openZipPicker = {
        zipPicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }

    /** 正在拖动的分组 id（用于高亮）；拖动状态放在 Composable 作用域，LazyListScope 里不能用 remember。 */
    var draggingSkillGroupId by remember { mutableStateOf<String?>(null) }
    // 行高用实测值而不是写死 72dp：换位阈值必须和真实行高一致，否则「拖一点就跳两格」
    // 或者「拖了半屏还不换位」（2026-11-04 用户反馈拖动不跟手）。
    // 72dp 只作为第一帧测量出来之前的兜底值。
    val rowHeightFallbackPx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    var skillGroupRowHeightPx by remember { mutableFloatStateOf(rowHeightFallbackPx) }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_skills),
        onBack = { onAction(AgentSkillsAction.NavigateBack) },
        modifier = modifier,
    ) {
        val installed = state.skills.filter { it.installed }
        val byId = installed.associateBy { it.id }
        val removed = state.skills.filter { !it.installed }

        item(key = "zip-import-title") { EtaPreferenceGroupTitle(stringResource(R.string.ui_install_087db6)) }
        item(key = "zip-import-card") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                EtaPreference(
                    title = if (state.isImporting) stringResource(R.string.skills_checking_package) else stringResource(R.string.skills_import_zip),
                    summary = if (state.isImporting) {
                        stringResource(R.string.skills_installing_package)
                    } else {
                        stringResource(R.string.skills_choose_package)
                    },
                    startAction = {
                        if (state.isImporting) {
                            // 加载态与图标同宽，切换时标题不横向跳动。
                            Box(
                                modifier = Modifier.size(EtaPreferenceDefaults.IconSize),
                                contentAlignment = Alignment.Center,
                            ) {
                                InfiniteProgressIndicator(size = 20.dp)
                            }
                        } else {
                            EtaPreferenceIcon(Icons.Rounded.FolderZip, enabled = !operationPending, tint = EtaPreferenceColors.Orange)
                        }
                    },
                    enabled = !operationPending,
                    onClick = openZipPicker,
                    onClickLabel = stringResource(R.string.skills_choose_zip),
                )
            }
        }

        // ── 技能分组 ──
        // 视觉沿用设置页那套大分组卡片：彩色图标 + 大标题 + 右侧拖动把手与箭头。
        // 交互：点整行折叠/展开，长按整行改名，长按右侧拖动把手排序。
        if (state.groups.isNotEmpty()) {
            item(key = "skill-groups-toolbar") {
                EtaPreferenceGroupTitle(
                    stringResource(R.string.skills_groups_title) + " · " +
                        stringResource(R.string.skills_groups_hint),
                )
            }
            item(key = "skill-groups") {
                EtaPreferenceGroup(
                    modifier = Modifier.padding(horizontal = CardHorizontalPadding),
                ) {
                    state.groups.forEachIndexed { groupIndex, group ->
                        if (groupIndex > 0) EtaPreferenceDivider()
                        // key 必须给：重排后 Compose 才能把「同一个分组」的组合状态搬过去。
                        // 没有 key 时按位置复用，行内的拖动位移会留在原来的槽上，视觉错位。
                        key(group.id) {
                            SkillGroupRow(
                                group = group,
                                count = group.skillIds.count { byId.containsKey(it) },
                                dragging = draggingSkillGroupId == group.id,
                                rowHeightPx = skillGroupRowHeightPx,
                                onMeasured = { height ->
                                    // 只在真的变了才写 state，避免布局阶段反复触发重组。
                                    if (abs(height - skillGroupRowHeightPx) > 0.5f) {
                                        skillGroupRowHeightPx = height
                                    }
                                },
                                onToggle = { onAction(AgentSkillsAction.ToggleSkillGroup(group.id)) },
                                onRename = {
                                    renameDraft = group.name
                                    renameGroup = group
                                },
                                onDragStart = { draggingSkillGroupId = group.id },
                                onDragEnd = { draggingSkillGroupId = null },
                                onDragStep = step@{ groupId, delta ->
                                    // 按 id 现查索引：拖动过程中分组顺序已经变了，
                                    // 闭包里捕获的 groupIndex 是拖动开始那一刻的旧值。
                                    val current = state.groups.indexOfFirst { it.id == groupId }
                                    if (current < 0) return@step false
                                    val target = (current + delta).coerceIn(0, state.groups.lastIndex)
                                    if (target == current) return@step false
                                    val ids = state.groups.map { it.id }.toMutableList()
                                    ids.removeAt(current)
                                    ids.add(target, groupId)
                                    onAction(AgentSkillsAction.ReorderSkillGroups(ids))
                                    true
                                },
                            )
                        }
                        if (!group.collapsed) {
                            group.skillIds.mapNotNull(byId::get).forEach { skill ->
                                EtaPreferenceDivider()
                                SkillSwitchRow(
                                    skill = skill,
                                    enabled = !operationPending,
                                    onToggle = { enabled ->
                                        onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                    },
                                    onDelete = if (skill.canDeleteUserSkill) {
                                        { deleteTarget = skill }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "skill-groups-reset") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(top = CardBottomPadding),
                ) {
                    EtaPreference(
                        title = stringResource(R.string.skills_group_reset),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Autorenew,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        },
                        onClick = { onAction(AgentSkillsAction.ResetSkillGroups) },
                    )
                }
            }
        }

        if (removed.isNotEmpty()) {
            item(key = "removed-title") { EtaPreferenceGroupTitle(stringResource(R.string.ui_removed_4e5c49)) }
            item(key = "removed-card") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    removed.forEachIndexed { index, skill ->
                        if (index > 0) EtaPreferenceDivider()
                        EtaPreference(
                            title = skill.name,
                            summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                            startAction = { EtaPreferenceIcon(iconForSkill(skill.id), enabled = !operationPending, tint = EtaPreferenceColors.Green) },
                            enabled = !operationPending,
                            onClick = {
                                onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                            },
                        )
                    }
                }
            }
        }

        if (state.skills.isEmpty() && state.isLoading) {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
            }
        }
        if (state.skills.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                    summary = stringResource(R.string.skills_choose_package),
                    icon = Icons.Rounded.FolderZip,
                    action = {
                        EtaTextButton(
                            text = stringResource(R.string.skills_import_zip),
                            enabled = !operationPending,
                            onClick = openZipPicker,
                        )
                    },
                )
            }
        }
    }

    state.replacement?.let { replacement ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_replace_user_skills_250e98),
            summary = stringResource(R.string.skills_replace_summary, replacement.name, replacement.id),
            onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.skills_replace),
                confirmEnabled = !operationPending,
                onCancel = { onAction(AgentSkillsAction.CancelZipReplacement) },
                onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
            )
        }
    }

    renameGroup?.let { group ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.skills_group_rename),
            summary = stringResource(R.string.skills_group_rename_hint),
            onDismissRequest = { renameGroup = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                MiuixDialogActions(
                    confirmText = stringResource(R.string.skills_group_save),
                    onCancel = { renameGroup = null },
                    onConfirm = {
                        onAction(AgentSkillsAction.RenameSkillGroup(group.id, renameDraft))
                        renameGroup = null
                    },
                )
            }
        }
    }

    deleteTarget?.let { skill ->        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_delete_user_skills_f319a9),
            summary = stringResource(R.string.skills_delete_summary, skill.name),
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.ui_delete_3755f5),
                destructive = true,
                confirmEnabled = !operationPending,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    onAction(AgentSkillsAction.DeleteSkill(skill.id))
                },
            )
        }
    }

    state.notice?.let { notice ->
        EtaWindowDialog(
            show = true,
            title = notice.title,
            summary = notice.message,
            onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
        ) {
            EtaTextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { onAction(AgentSkillsAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (notice.isError) {
                    ButtonDefaults.textButtonColors()
                } else {
                    ButtonDefaults.textButtonColorsPrimary()
                },
            )
        }
    }
}


/**
 * 分组行：沿用设置页那种大分组样式 —— 彩色图标 + 大标题 + 右侧拖动把手与展开箭头。
 *
 * 手势分工（三个互不打架）：
 * - 点整行 → 折叠 / 展开
 * - 长按整行 → 重命名
 * - 只有长按右侧把手 → 进入拖动排序
 */
@Composable
private fun SkillGroupRow(
    group: SkillGroupUi,
    count: Int,
    dragging: Boolean,
    rowHeightPx: Float,
    onMeasured: (Float) -> Unit,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragStep: (String, Int) -> Boolean,
) {
    var offsetY by remember(group.id) { mutableFloatStateOf(0f) }
    // 手势协程一旦启动就是长生命周期：直接读参数会一直用「拖动开始那一刻」的闭包
    // （旧的顺序、旧的行高）。rememberUpdatedState 让协程每次拿到最新回调。
    val currentStep by rememberUpdatedState(onDragStep)
    val currentRowHeight by rememberUpdatedState(rowHeightPx)
    val currentStart by rememberUpdatedState(onDragStart)
    val currentEnd by rememberUpdatedState(onDragEnd)
    EtaPreferenceRow(
        title = group.name,
        modifier = Modifier
            .onGloballyPositioned { coordinates -> onMeasured(coordinates.size.height.toFloat()) }
            .graphicsLayer {
                translationY = offsetY
                alpha = if (dragging) 0.9f else 1f
            },
        startAction = {
            EtaPreferenceIcon(
                icon = skillGroupIcon(group.icon),
                tint = skillGroupTint(group.icon),
            )
        },
        interaction = Modifier.combinedClickable(
            onClick = onToggle,
            onLongClick = onRename,
        ),
        endActions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (count > 0) {
                    Text(
                        text = count.toString(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = stringResource(R.string.skills_group_drag),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(30.dp)
                        // 自己接管手势，而不是 detectDragGesturesAfterLongPress：
                        // 后者检测到的「长按」会和整行 combinedClickable 的长按（重命名）同时成立，
                        // 结果一拖就把重命名弹窗顶出来（用户实测）。
                        // 这里第一件事就是 consume 掉 down 事件 —— 父级收不到，根本不会判定长按。
                        .pointerInput(group.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var accumulated = 0f
                                var started = false
                                drag(down.id) { change ->
                                    change.consume()
                                    // 行高还没测出来（0）就别判换位，否则第一个像素就触发。
                                    val rowHeight = currentRowHeight.takeIf { it > 1f } ?: return@drag
                                    accumulated += change.position.y - change.previousPosition.y
                                    if (!started && abs(accumulated) > rowHeight / 3f) {
                                        started = true
                                        currentStart()
                                    }
                                    if (!started) return@drag
                                    // 真的移动成功了才扣掉一行的量；到顶/到底时夹住位移，
                                    // 否则手指继续拖会让 offsetY 越跑越远、行飞出去。
                                    if (accumulated > rowHeight) {
                                        accumulated = if (currentStep(group.id, 1)) {
                                            accumulated - rowHeight
                                        } else {
                                            rowHeight
                                        }
                                    } else if (accumulated < -rowHeight) {
                                        accumulated = if (currentStep(group.id, -1)) {
                                            accumulated + rowHeight
                                        } else {
                                            -rowHeight
                                        }
                                    }
                                    offsetY = accumulated
                                }
                                offsetY = 0f
                                if (started) currentEnd()
                            }
                        },
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = MiuixIcons.Basic.ArrowRight,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(8.dp, 14.dp)
                        .rotate(if (group.collapsed) 0f else 90f),
                )
            }
        },
    )
}

/**
 * 分组图标：按**图标类别**取，而不是按组名。
 *
 * 这样模型自动建的分组、用户改过名字的分组都有图标，不会因为组名不匹配就掉成默认文件夹。
 */
private fun skillGroupIcon(icon: String): ImageVector = when (icon) {
    "dev" -> Icons.Rounded.Code
    "system" -> Icons.Rounded.Settings
    "network" -> Icons.Rounded.Hub
    "media" -> Icons.Rounded.PlayCircle
    "doc" -> Icons.Rounded.Description
    "self" -> Icons.Rounded.Autorenew
    else -> Icons.Rounded.Folder
}

/** 分组颜色：沿用设置页那套蓝/绿/橙/黄，一眼能区分大类。 */
@Composable
private fun skillGroupTint(icon: String): Color = when (icon) {
    "dev", "network" -> EtaPreferenceColors.Blue
    "system", "doc" -> EtaPreferenceColors.Green
    "media" -> EtaPreferenceColors.Orange
    "self" -> EtaPreferenceColors.Yellow
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

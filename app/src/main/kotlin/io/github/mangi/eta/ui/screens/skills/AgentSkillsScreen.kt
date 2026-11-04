package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
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
    val skillGroupRowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }

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
                        SkillGroupRow(
                            group = group,
                            count = group.skillIds.count { byId.containsKey(it) },
                            dragging = draggingSkillGroupId == group.id,
                            rowHeightPx = skillGroupRowHeightPx,
                            onToggle = { onAction(AgentSkillsAction.ToggleSkillGroup(group.id)) },
                            onRename = {
                                renameDraft = group.name
                                renameGroup = group
                            },
                            onDragStart = { draggingSkillGroupId = group.id },
                            onDragEnd = { draggingSkillGroupId = null },
                            onDragStep = { delta ->
                                val target = (groupIndex + delta).coerceIn(0, state.groups.lastIndex)
                                if (target != groupIndex) {
                                    val ids = state.groups.map { it.id }.toMutableList()
                                    ids.removeAt(groupIndex)
                                    ids.add(target, group.id)
                                    onAction(AgentSkillsAction.ReorderSkillGroups(ids))
                                }
                            },
                        )
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
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragStep: (Int) -> Unit,
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    EtaPreferenceRow(
        title = group.name,
        modifier = Modifier.graphicsLayer {
            translationY = offsetY
            alpha = if (dragging) 0.9f else 1f
        },
        startAction = {
            EtaPreferenceIcon(
                icon = skillGroupIcon(group.id),
                tint = skillGroupTint(group.id),
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
                        .size(24.dp)
                        // 长按把手才进拖动：整行拖动会和「点击折叠」「列表滚动」打架。
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offsetY += amount.y
                                    if (offsetY > rowHeightPx) {
                                        onDragStep(1)
                                        offsetY -= rowHeightPx
                                    } else if (offsetY < -rowHeightPx) {
                                        onDragStep(-1)
                                        offsetY += rowHeightPx
                                    }
                                },
                                onDragEnd = {
                                    offsetY = 0f
                                    onDragEnd()
                                },
                                onDragCancel = {
                                    offsetY = 0f
                                    onDragEnd()
                                },
                            )
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

/** 分组图标：按组 id 固定，用户改名不影响视觉。 */
private fun skillGroupIcon(id: String): ImageVector = when (id) {
    "Eta 自身" -> Icons.Rounded.SupportAgent
    "开发与构建" -> Icons.Rounded.Code
    "逆向与包分析" -> Icons.Rounded.BugReport
    "系统与设备" -> Icons.Rounded.Settings
    "网络与代理" -> Icons.Rounded.Hub
    "前端与 Web" -> Icons.Rounded.Language
    "写作与文档" -> Icons.Rounded.Description
    "自我维护" -> Icons.Rounded.Autorenew
    else -> Icons.Rounded.Folder
}

/** 分组颜色：沿用设置页那套蓝/绿/橙/黄，一眼能区分大类。 */
@Composable
private fun skillGroupTint(id: String): Color = when (id) {
    "Eta 自身", "开发与构建", "网络与代理" -> EtaPreferenceColors.Blue
    "系统与设备", "写作与文档" -> EtaPreferenceColors.Green
    "逆向与包分析", "前端与 Web" -> EtaPreferenceColors.Orange
    "自我维护" -> EtaPreferenceColors.Yellow
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

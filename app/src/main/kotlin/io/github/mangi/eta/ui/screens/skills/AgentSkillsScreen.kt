package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    /** 打开「分组菜单」的组 id（改名 / 上移 / 下移 / 恢复自动分类）。 */
    var groupMenuId by remember { mutableStateOf<String?>(null) }
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
        // 分组内容由 AgentSkillsStore 算好（自动分类 + 用户改名/排序），这里只管渲染与手势：
        // 点标题折叠/展开，长按标题上下拖动排序，右侧「⋮」改名或恢复自动分类。
        if (state.groups.isNotEmpty()) {
            item(key = "skill-groups-toolbar") {
                EtaPreferenceGroupTitle(
                    stringResource(R.string.skills_groups_title) + " · " +
                        stringResource(R.string.skills_groups_hint),
                )
            }
            state.groups.forEachIndexed { groupIndex, group ->
                item(key = "skill-group-${group.id}") {
                    SkillGroupHeader(
                        name = group.name,
                        count = group.skillIds.count { byId.containsKey(it) },
                        collapsed = group.collapsed,
                        dragging = draggingSkillGroupId == group.id,
                        onToggle = { onAction(AgentSkillsAction.ToggleSkillGroup(group.id)) },
                        onMenu = { groupMenuId = group.id },
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
                        rowHeightPx = skillGroupRowHeightPx,
                    )
                }
                if (!group.collapsed) {
                    val members = group.skillIds.mapNotNull(byId::get)
                    if (members.isNotEmpty()) {
                        item(key = "skill-group-card-${group.id}") {
                            EtaPreferenceGroup(
                                modifier = Modifier
                                    .padding(horizontal = CardHorizontalPadding)
                                    .padding(bottom = CardBottomPadding),
                            ) {
                                members.forEachIndexed { index, skill ->
                                    if (index > 0) EtaPreferenceDivider()
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

    // 分组菜单：改名 / 上移 / 下移 / 恢复自动分类。
    // 不做「长按弹出菜单」是因为长按已经留给拖动排序了。
    groupMenuId?.let { id ->
        val group = state.groups.firstOrNull { it.id == id }
        if (group != null) {
            val index = state.groups.indexOfFirst { it.id == group.id }
            EtaWindowDialog(
                show = true,
                title = group.name,
                summary = stringResource(R.string.skills_group_menu_summary),
                onDismissRequest = { groupMenuId = null },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    EtaTextButton(
                        text = stringResource(R.string.skills_group_rename),
                        onClick = {
                            groupMenuId = null
                            renameDraft = group.name
                            renameGroup = group
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EtaTextButton(
                        text = stringResource(R.string.skills_group_move_up),
                        enabled = index > 0,
                        onClick = {
                            groupMenuId = null
                            val ids = state.groups.map { it.id }.toMutableList()
                            ids.removeAt(index)
                            ids.add((index - 1).coerceAtLeast(0), group.id)
                            onAction(AgentSkillsAction.ReorderSkillGroups(ids))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EtaTextButton(
                        text = stringResource(R.string.skills_group_move_down),
                        enabled = index in 0 until state.groups.lastIndex,
                        onClick = {
                            groupMenuId = null
                            val ids = state.groups.map { it.id }.toMutableList()
                            ids.removeAt(index)
                            ids.add((index + 1).coerceAtMost(ids.size), group.id)
                            onAction(AgentSkillsAction.ReorderSkillGroups(ids))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EtaTextButton(
                        text = stringResource(R.string.skills_group_reset),
                        onClick = {
                            groupMenuId = null
                            onAction(AgentSkillsAction.ResetSkillGroups)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
 * 分组标题行：点击折叠/展开，长按上下拖动排序，右侧「⋮」打开菜单。
 *
 * 为什么不用 [EtaArrowPreference]：它的箭头方向固定向右，而这里要随折叠状态变（▸/▾）。
 * 字号与左右边距沿用 `EtaPreferenceGroupTitle`（subtitle + 32dp），保持整页视觉一致。
 */
@Composable
private fun SkillGroupHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    dragging: Boolean,
    rowHeightPx: Float,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragStep: (Int) -> Unit,
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .graphicsLayer {
                translationY = offsetY
                alpha = if (dragging) 0.85f else 1f
            }
            .pointerInput(Unit) {
                // 长按拖动：跨过一行高度就换位。用固定行高阈值而不是精确测量——
                // 分组标题行高度一致，差几个像素的手感比测量开销更值。
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        offsetY = 0f
                        onDragStart()
                    },
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
            }
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Rounded.KeyboardArrowRight else Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = name,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = count.toString(),
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
        )
        IconButton(onClick = onMenu) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.skills_group_menu),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

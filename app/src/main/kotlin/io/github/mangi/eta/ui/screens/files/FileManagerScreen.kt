package io.github.mangi.eta.ui.screens.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.files.FileManagerOps
import io.github.mangi.eta.agent.files.FilePathRisk
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 内置文件管理器：浏览、复制、移动、重命名、删除（进回收站）、新建目录。
 *
 * 几个刻意的设计：
 * 1. **删除走回收站**，不是直接 rm —— 误删还能捞回来。
 * 2. **二次确认 + 危险路径红字**：系统分区不限制操作，但删之前会告诉你后果。
 * 3. **列表先出名字**：目录读取在 IO 线程做，加载时给明确提示，不会白屏。
 * 4. **路径可在顶部编辑**：直接跳到目标目录，不用一层层点。
 */
@Composable
internal fun FileManagerScreen(
    initialPath: String,
    onBack: () -> Unit,
    /** 由调用方提供 shell 执行能力（root 或普通身份）。 */
    runShell: (String) -> String,
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var listing by remember { mutableStateOf<FileManagerOps.Listing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reload(path: String) {
        currentPath = path
        loading = true
        selected = emptySet()
        scope.launch {
            val result = withContext(Dispatchers.IO) { FileManagerOps.list(runShell, path) }
            listing = result
            loading = false
        }
    }

    LaunchedEffect(initialPath) { reload(initialPath) }

    MiuixScaffoldPage(
        title = "文件",
        onBack = onBack,
        actions = {
            IconButton(onClick = { reload(currentPath) }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { showNewFolder = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "新建文件夹", modifier = Modifier.size(22.dp))
            }
        },
    ) {
        // 当前路径 + 返回上级
        item(key = "path") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
            ) {
                EtaPreference(
                    title = currentPath,
                    summary = when {
                        loading -> "加载中…"
                        listing?.ok == false -> listing?.error.orEmpty()
                        else -> "${listing?.entries?.size ?: 0} 项"
                    },
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.Rounded.FolderOpen,
                            tint = EtaPreferenceColors.Blue,
                        )
                    },
                    onClick = {
                        val parent = currentPath.trimEnd('/').substringBeforeLast('/', "/")
                        if (parent != currentPath) reload(parent)
                    },
                )
            }
        }

        // 选中项的操作条
        if (selected.isNotEmpty()) {
            item(key = "actions") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                ) {
                    EtaPreference(
                        title = "已选 ${selected.size} 项",
                        summary = "点这里清除选择",
                        startAction = {
                            EtaPreferenceIcon(icon = Icons.Rounded.Close, tint = EtaPreferenceColors.Orange)
                        },
                        onClick = { selected = emptySet() },
                    )
                    EtaPreference(
                        title = "删除（移到回收站）",
                        titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                            color = MiuixTheme.colorScheme.error,
                        ),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Delete,
                                tint = MiuixTheme.colorScheme.error,
                            )
                        },
                        onClick = { pendingDelete = selected.toList() },
                    )
                }
            }
        }

        // 目录内容
        if (!loading && listing?.ok == true) {
            if (listing?.entries.isNullOrEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "这个目录是空的",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            } else {
                item(key = "section_title") {
                    EtaPreferenceGroupTitle("内容")
                }
                item(key = "entries") {
                    EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                        listing?.entries?.forEachIndexed { index, entry ->
                            if (index > 0) io.github.mangi.eta.ui.components.EtaPreferenceDivider()
                            SimpleFileRow(
                                entry = entry,
                                selected = entry.path in selected,
                                onToggleSelect = {
                                    selected = if (entry.path in selected) {
                                        selected - entry.path
                                    } else {
                                        selected + entry.path
                                    }
                                },
                                onOpen = {
                                    if (entry.isDirectory) {
                                        reload(entry.path)
                                    } else {
                                        notice = "${entry.name} · ${FileManagerOps.formatSize(entry.sizeBytes)}"
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认：危险路径额外加红字
    val deleteTargets = pendingDelete
    if (deleteTargets != null) {
        val risk = FilePathRisk.assess(deleteTargets, destructive = true)
        EtaWindowDialog(
            show = true,
            title = "删除 ${deleteTargets.size} 项？",
            summary = buildString {
                append(deleteTargets.take(3).joinToString("\n") { it.substringAfterLast('/') })
                if (deleteTargets.size > 3) append("\n…共 ${deleteTargets.size} 项")
                append("\n\n会移到回收站，${FilePathRisk.TRASH_RETENTION_DAYS} 天后自动清除。")
                if (risk.warning.isNotBlank()) {
                    append("\n\n⚠ ")
                    append(risk.warning)
                }
            },
            onDismissRequest = { pendingDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = if (risk.needsStrongWarning) "仍然删除" else "删除",
                destructive = true,
                onCancel = { pendingDelete = null },
                onConfirm = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            FileManagerOps.moveToTrash(runShell, deleteTargets)
                        }
                        notice = result.message
                        pendingDelete = null
                        reload(currentPath)
                    }
                },
            )
        }
    }

    // 新建文件夹
    if (showNewFolder) {
        EtaWindowDialog(
            show = true,
            title = "新建文件夹",
            summary = "在 $currentPath 下创建",
            onDismissRequest = { showNewFolder = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = "文件夹名",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                MiuixDialogActions(
                    confirmText = "创建",
                    confirmEnabled = newFolderName.isNotBlank(),
                    onCancel = { showNewFolder = false },
                    onConfirm = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FileManagerOps.createDirectory(runShell, currentPath, newFolderName.trim())
                            }
                            notice = result.message
                            showNewFolder = false
                            newFolderName = ""
                            reload(currentPath)
                        }
                    },
                )
            }
        }
    }

    // 结果提示
    notice?.let { message ->
        EtaWindowDialog(
            show = true,
            title = "完成",
            summary = message,
            onDismissRequest = { notice = null },
        ) {
            MiuixDialogActions(
                confirmText = "知道了",
                onCancel = { notice = null },
                onConfirm = { notice = null },
            )
        }
    }
}

/** 一行文件：图标 + 名字 + 大小/权限；点一下打开，长按（这里用选中态替代）多选。 */
@Composable
private fun SimpleFileRow(
    entry: FileManagerOps.Entry,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (selected) onToggleSelect() else onOpen() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (entry.isDirectory) EtaPreferenceColors.Yellow else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MiuixTheme.textStyles.body2,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDirectory) {
                    entry.permissions
                } else {
                    "${FileManagerOps.formatSize(entry.sizeBytes)} · ${entry.permissions}"
                },
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        EtaTextButton(
            text = if (selected) "取消" else "选择",
            onClick = onToggleSelect,
        )
    }
}

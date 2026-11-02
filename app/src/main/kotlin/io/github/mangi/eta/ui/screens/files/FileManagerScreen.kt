package io.github.mangi.eta.ui.screens.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.UploadFile
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
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 双栏文件管理器（对齐 MT 的用法）。
 *
 * 布局与交互：
 * ```
 * ┌──────────────┬──────────────┐
 * │ 左栏路径行    │ 右栏路径行    │  ← 点路径行可返回上级
 * │ 文件列表      │ 文件列表      │  ← 各自独立滚动、独立选择
 * ├──────────────┴──────────────┤
 * │ [复制→] [←复制] [移动→] [删除] │  ← 跨栏操作，这是双栏的核心价值
 * └──────────────────────────────┘
 * ```
 *
 * 为什么值得做成双栏：单栏下「复制到某个目录」要来回跳转、记住路径；
 * 双栏把源与目标同时摆在眼前，这是 MT 最实用的地方。
 *
 * 性能：每个条目是独立的 LazyColumn item（可懒加载），不是塞进一个大 item。
 */
@Composable
internal fun FileManagerScreen(
    initialPath: String,
    onBack: () -> Unit,
    runShell: (String) -> String,
) {
    // 左栏 = 进入时的目录；右栏 = 内部存储根（最常用的复制目标）
    var leftPath by remember { mutableStateOf(initialPath) }
    var rightPath by remember { mutableStateOf("/storage/emulated/0") }

    var leftListing by remember { mutableStateOf<FileManagerOps.Listing?>(null) }
    var rightListing by remember { mutableStateOf<FileManagerOps.Listing?>(null) }
    var leftLoading by remember { mutableStateOf(true) }
    var rightLoading by remember { mutableStateOf(true) }
    var leftSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var rightSelected by remember { mutableStateOf<Set<String>>(emptySet()) }

    var notice by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<Set<String>, Boolean>?>(null) } // paths to isLeftPane
    var showNewFolder by remember { mutableStateOf<Boolean?>(null) }                    // true=左栏
    var newFolderName by remember { mutableStateOf("") }
    var singlePane by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reloadLeft(path: String = leftPath) {
        leftPath = path
        leftLoading = true
        leftSelected = emptySet()
        scope.launch {
            leftListing = withContext(Dispatchers.IO) { FileManagerOps.list(runShell, path) }
            leftLoading = false
        }
    }

    fun reloadRight(path: String = rightPath) {
        rightPath = path
        rightLoading = true
        rightSelected = emptySet()
        scope.launch {
            rightListing = withContext(Dispatchers.IO) { FileManagerOps.list(runShell, path) }
            rightLoading = false
        }
    }

    LaunchedEffect(initialPath) {
        reloadLeft(initialPath)
        reloadRight("/storage/emulated/0")
    }

    /** 跨栏搬运：fromLeft 表示把左栏选中项搬到右栏目录，反之亦然。 */
    fun transfer(fromLeft: Boolean, move: Boolean) {
        val sources = if (fromLeft) leftSelected else rightSelected
        val target = if (fromLeft) rightPath else leftPath
        if (sources.isEmpty()) {
            notice = "先在${if (fromLeft) "左" else "右"}栏选中文件"
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (move) {
                    FileManagerOps.move(runShell, sources.toList(), target)
                } else {
                    FileManagerOps.copy(runShell, sources.toList(), target)
                }
            }
            notice = result.message
            if (fromLeft) reloadLeft() else reloadRight()
            if (move) {
                // 移动会改变两侧目录内容，两边都刷新
                reloadLeft()
                reloadRight()
            } else {
                reloadLeft()
                reloadRight()
            }
        }
    }

    MiuixScaffold(
        title = "文件管理",
        onBack = onBack,
        actions = {
            IconButton(onClick = { singlePane = !singlePane }) {
                Icon(Icons.Rounded.SwapHoriz, contentDescription = "切换单/双栏", modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { reloadLeft(); reloadRight() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(22.dp))
            }
        },
    ) { paddingValues, _, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            // ---------- 面板区 ----------
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PaneColumn(
                    title = leftPath,
                    listing = leftListing,
                    loading = leftLoading,
                    selected = leftSelected,
                    onToggleSelect = { path ->
                        leftSelected = if (path in leftSelected) leftSelected - path else leftSelected + path
                    },
                    onOpenDirectory = { reloadLeft(it) },
                    onGoParent = {
                        val parent = leftPath.trimEnd('/').substringBeforeLast('/', "/")
                        if (parent != leftPath) reloadLeft(parent)
                    },
                    onNewFolder = { showNewFolder = true },
                    onFileTap = { notice = "${it.name} · ${FileManagerOps.formatSize(it.sizeBytes)}" },
                    modifier = Modifier.weight(1f),
                )
                if (!singlePane) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.dividerLine),
                    )
                    PaneColumn(
                        title = rightPath,
                        listing = rightListing,
                        loading = rightLoading,
                        selected = rightSelected,
                        onToggleSelect = { path ->
                            rightSelected = if (path in rightSelected) rightSelected - path else rightSelected + path
                        },
                        onOpenDirectory = { reloadRight(it) },
                        onGoParent = {
                            val parent = rightPath.trimEnd('/').substringBeforeLast('/', "/")
                            if (parent != rightPath) reloadRight(parent)
                        },
                        onNewFolder = { showNewFolder = false },
                        onFileTap = { notice = "${it.name} · ${FileManagerOps.formatSize(it.sizeBytes)}" },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---------- 操作条 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionChip("复制→", Icons.Rounded.ContentCopy, enabled = leftSelected.isNotEmpty()) {
                    transfer(fromLeft = true, move = false)
                }
                ActionChip("←复制", Icons.Rounded.ContentCopy, enabled = rightSelected.isNotEmpty()) {
                    transfer(fromLeft = false, move = false)
                }
                ActionChip("移动→", Icons.Rounded.DriveFileMove, enabled = leftSelected.isNotEmpty()) {
                    transfer(fromLeft = true, move = true)
                }
                ActionChip("←移动", Icons.Rounded.DriveFileMove, enabled = rightSelected.isNotEmpty()) {
                    transfer(fromLeft = false, move = true)
                }
                ActionChip(
                    text = "删除",
                    icon = Icons.Rounded.Delete,
                    enabled = leftSelected.isNotEmpty() || rightSelected.isNotEmpty(),
                    danger = true,
                ) {
                    if (leftSelected.isNotEmpty()) {
                        pendingDelete = leftSelected to true
                    } else {
                        pendingDelete = rightSelected to false
                    }
                }
            }
        }
    }

    // ---------- 删除确认（危险路径加红字） ----------
    pendingDelete?.let { (paths, fromLeft) ->
        val risk = FilePathRisk.assess(paths.toList(), destructive = true)
        EtaWindowDialog(
            show = true,
            title = "删除 ${paths.size} 项？",
            summary = buildString {
                append(paths.take(3).joinToString("\n") { it.substringAfterLast('/') })
                if (paths.size > 3) append("\n…共 ${paths.size} 项")
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
                            FileManagerOps.moveToTrash(runShell, paths.toList())
                        }
                        notice = result.message
                        pendingDelete = null
                        if (fromLeft) reloadLeft() else reloadRight()
                    }
                },
            )
        }
    }

    // ---------- 新建文件夹 ----------
    showNewFolder?.let { isLeft ->
        val parent = if (isLeft) leftPath else rightPath
        EtaWindowDialog(
            show = true,
            title = "新建文件夹",
            summary = "在 $parent 下创建",
            onDismissRequest = { showNewFolder = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
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
                    onCancel = { showNewFolder = null },
                    onConfirm = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                FileManagerOps.createDirectory(runShell, parent, newFolderName.trim())
                            }
                            newFolderName = ""
                            showNewFolder = null
                            if (isLeft) reloadLeft() else reloadRight()
                        }
                    },
                )
            }
        }
    }

    notice?.let { message ->
        EtaWindowDialog(
            show = true,
            title = "提示",
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

/** 单个面板：路径行 + 可懒加载的列表。 */
@Composable
private fun PaneColumn(
    title: String,
    listing: FileManagerOps.Listing?,
    loading: Boolean,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onGoParent: () -> Unit,
    onNewFolder: () -> Unit,
    onFileTap: (FileManagerOps.Entry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Column(modifier = modifier.fillMaxSize()) {
        // 路径行：点一下返回上级
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onGoParent() }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = EtaPreferenceColors.Blue,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        loading -> "加载中…"
                        listing?.ok == false -> listing.error.orEmpty()
                        else -> "${listing?.entries?.size ?: 0} 项 · 点击返回上级"
                    },
                    style = MiuixTheme.textStyles.subtitle,
                    color = if (listing?.ok == false) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onNewFolder) {
                Icon(Icons.Rounded.Add, contentDescription = "新建", modifier = Modifier.size(18.dp))
            }
        }

        if (!loading && listing?.ok == true && listing.entries.isEmpty()) {
            Text(
                text = "空目录",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // 每个条目一个 item：可懒加载，大目录不卡。
                items(listing?.entries.orEmpty(), key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        selected = entry.path in selected,
                        onOpen = {
                            if (entry.isDirectory) onOpenDirectory(entry.path) else onFileTap(entry)
                        },
                        onToggleSelect = { onToggleSelect(entry.path) },
                    )
                }
            }
        }
    }
}

/** 一行文件：点名字进入/预览，点右侧圆点选中。 */
@Composable
private fun FileRow(
    entry: FileManagerOps.Entry,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (entry.isDirectory) EtaPreferenceColors.Yellow
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MiuixTheme.textStyles.body2,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDirectory) entry.permissions
                else FileManagerOps.formatSize(entry.sizeBytes),
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 选中按钮（用文字而不是图标，手指点得准）
        EtaTextButton(
            text = if (selected) "✓" else "○",
            onClick = onToggleSelect,
        )
    }
}

/** 底部操作按钮。 */
@Composable
private fun ActionChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(18.dp),
            tint = when {
                !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                danger -> MiuixTheme.colorScheme.error
                else -> MiuixTheme.colorScheme.primary
            },
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.subtitle,
            color = when {
                !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                danger -> MiuixTheme.colorScheme.error
                else -> MiuixTheme.colorScheme.primary
            },
        )
    }
}

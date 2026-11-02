package io.github.mangi.eta.ui.screens.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.files.FileManagerOps
import io.github.mangi.eta.agent.files.FilePathRisk
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文件管理器（对齐 MT 的手感）。
 *
 * 交互设计（这是用户明确要的，别再改回去）：
 * - **单栏**：名字能显示全。双栏虽然能并排看两个目录，但把每个名字都挤成
 *   `33094010…`，连后缀都看不见，反而不能用。
 * - **单击 = 打开**：目录进入；文件按类型预览（文本看内容、图片交给系统看图、
 *   APK 看包信息、其他给属性），不再是弹一个空提示。
 * - **长按 = 操作菜单**：复制 / 剪切 / 删除 / 重命名 / 属性 / 新建文件夹。
 *   底部不放常驻按钮 —— 那排按钮既占地方又容易误触。
 * - **复制/剪切后底部浮出「粘贴到此处」**：这是 MT 最核心的手感，
 *   用户可以一路点进目标目录，再粘。带 × 可以取消剪贴板。
 * - **[选择] 模式**：顶栏切换，多选后批量复制/剪切/删除，用于跨目录搬运多个文件。
 */
@Composable
internal fun FileManagerScreen(
    initialPath: String,
    onBack: () -> Unit,
    runShell: (String) -> String,
    /**
     * 传了就是「选文件」模式（附件按钮用）：底部出现「确认选择」，
     * 确认时把勾选的路径交回去。不传就是普通管理模式。
     */
    onPickPaths: ((List<String>) -> Unit)? = null,
) {
    var path by remember { mutableStateOf(initialPath) }
    var listing by remember { mutableStateOf<FileManagerOps.Listing?>(null) }
    var loading by remember { mutableStateOf(true) }
    /** 选择模式下勾选的文件/目录。 */
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** 长按弹出的操作菜单目标。 */
    var actionTarget by remember { mutableStateOf<FileManagerOps.Entry?>(null) }
    /** 剪贴板：复制/剪切待粘贴的项 + 是否为剪切。 */
    var clipboard by remember { mutableStateOf<List<String>?>(null) }
    var clipboardIsCut by remember { mutableStateOf(false) }
    /** 多选模式。 */
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    var notice by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<FileManagerOps.Entry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var infoTarget by remember { mutableStateOf<FileManagerOps.Entry?>(null) }
    /** 文本预览：标题 + 正文。 */
    var previewText by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** 跳转路径输入框。 */
    var showGoTo by remember { mutableStateOf(false) }
    var goToInput by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current

    fun reload(target: String = path) {
        path = target
        loading = true
        selected = emptySet()
        scope.launch {
            listing = withContext(Dispatchers.IO) { FileManagerOps.list(runShell, target) }
            loading = false
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(initialPath) { reload(initialPath) }

    /**
     * 单击文件的处理：按类型分流。
     * - 文本：读内容，弹出可滚动的正文预览
     * - 图片：交给系统图片查看器（root 下能打开 /data 里的私有文件）
     * - 其它：属性面板（大小/权限/时间/路径），至少能看到"它是什么"
     */
    fun openFile(entry: FileManagerOps.Entry) {
        when {
            FileManagerOps.looksLikeText(entry.name) -> scope.launch {
                val text = withContext(Dispatchers.IO) {
                    FileManagerOps.readTextPreview(runShell, entry.path)
                }
                previewText = entry.name to text.message
            }

            FileManagerOps.looksLikeImage(entry.name) -> {
                val opened = runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            android.net.Uri.fromFile(java.io.File(entry.path)),
                            "image/*",
                        )
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                if (opened.isFailure) infoTarget = entry
            }

            else -> infoTarget = entry
        }
    }

    fun parentOf(current: String): String? {
        val trimmed = current.trimEnd('/')
        if (trimmed.isEmpty() || trimmed == "/") return null
        val parent = trimmed.substringBeforeLast('/', "/")
        return if (parent == current) null else parent
    }

    /** 粘贴：把剪贴板内容复制/移动到当前目录。 */
    fun paste() {
        val sources = clipboard ?: return
        val cut = clipboardIsCut
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (cut) FileManagerOps.move(runShell, sources, path)
                else FileManagerOps.copy(runShell, sources, path)
            }
            notice = result.message
            clipboard = null
            clipboardIsCut = false
            reload()
        }
    }

    MiuixScaffold(
        title = if (selectMode) "已选 ${selected.size} 项" else "文件管理",
        onBack = {
            if (selectMode) {
                selectMode = false
                selected = emptySet()
            } else {
                onBack()
            }
        },
        actions = {
            IconButton(onClick = {
                selectMode = !selectMode
                selected = emptySet()
            }) {
                Icon(
                    Icons.Rounded.UploadFile,
                    contentDescription = if (selectMode) "退出多选" else "多选",
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = { reload() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(22.dp))
            }
        },
    ) { paddingValues, _, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            // ---------- 路径栏：点路径打开「跳转」输入框，点「上级」回父目录 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = EtaPreferenceColors.Yellow,
                )
                Spacer(Modifier.width(6.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            goToInput = path
                            showGoTo = true
                        },
                ) {
                    Text(
                        text = path,
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            loading -> "加载中…"
                            listing?.ok == false -> listing?.error.orEmpty()
                            else -> "${listing?.entries?.size ?: 0} 项 · 点路径可跳转"
                        },
                        style = MiuixTheme.textStyles.subtitle,
                        color = if (listing?.ok == false) MiuixTheme.colorScheme.error
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    newFolderName = ""
                    showNewFolder = true
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建文件夹", modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = { parentOf(path)?.let { reload(it) } },
                    enabled = parentOf(path) != null,
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = "上级目录",
                        modifier = Modifier.size(20.dp),
                        tint = if (parentOf(path) != null) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // ---------- 文件列表 ----------
            Box(modifier = Modifier.weight(1f)) {
                val entries = listing?.entries.orEmpty()
                if (!loading && listing?.ok == true && entries.isEmpty()) {
                    Text(
                        text = "空目录",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(entries, key = { it.path }) { entry ->
                            FileRow(
                                entry = entry,
                                selected = if (onPickPaths != null) entry.path in picked
                                else entry.path in selected,
                                selectMode = selectMode || onPickPaths != null,
                                onTap = {
                                    if (selectMode || onPickPaths != null) {
                                        // 选择模式：单击勾选，目录也照样进入（用「上级」回来）
                                        val already = entry.path in picked
                                        if (entry.isDirectory && !already) {
                                            reload(entry.path)
                                        } else {
                                            picked = if (already) picked - entry.path else picked + entry.path
                                        }
                                    } else if (entry.isDirectory) {
                                        reload(entry.path)
                                    } else {
                                        // 文件：文本→读内容预览；其它→属性/交给系统打开
                                        openFile(entry)
                                    }
                                },
                                onLongPress = {
                                    if (!selectMode) actionTarget = entry
                                },
                            )
                        }
                    }
                }
            }

            // ---------- 底部：剪贴板 / 多选操作条 ----------
            if (onPickPaths != null) {
                // 选文件模式：确认后把勾选路径交回去
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (picked.isEmpty()) "点文件勾选，点目录进入"
                        else "已选 ${picked.size} 项",
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.weight(1f),
                    )
                    EtaTextButton(
                        text = if (picked.isEmpty()) "取消" else "清空",
                        onClick = { if (picked.isEmpty()) onBack() else picked = emptySet() },
                    )
                    EtaTextButton(
                        text = "确认选择",
                        enabled = picked.isNotEmpty(),
                        onClick = { onPickPaths.invoke(picked.toList()) },
                    )
                }
            } else if (selectMode) {
                SelectionBar(
                    count = selected.size,
                    onCopy = {
                        clipboard = selected.toList()
                        clipboardIsCut = false
                        notice = "已复制 ${selected.size} 项，进入目标目录后粘贴"
                        selectMode = false
                        selected = emptySet()
                    },
                    onCut = {
                        clipboard = selected.toList()
                        clipboardIsCut = true
                        notice = "已剪切 ${selected.size} 项，进入目标目录后粘贴"
                        selectMode = false
                        selected = emptySet()
                    },
                    onDelete = { pendingDelete = selected.toList() },
                )
            } else if (clipboard != null) {
                ClipboardBar(
                    count = clipboard!!.size,
                    isCut = clipboardIsCut,
                    onPaste = { paste() },
                    onClear = {
                        clipboard = null
                        clipboardIsCut = false
                    },
                )
            }
        }
    }

    // ---------- 长按操作菜单 ----------
    actionTarget?.let { entry ->
        EtaWindowDialog(
            show = true,
            title = entry.name,
            summary = if (entry.isDirectory) "目录 · ${entry.permissions}"
            else "${FileManagerOps.formatSize(entry.sizeBytes)} · ${entry.permissions}",
            onDismissRequest = { actionTarget = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ActionMenuItem("复制", Icons.Rounded.ContentCopy) {
                    clipboard = listOf(entry.path)
                    clipboardIsCut = false
                    actionTarget = null
                    notice = "已复制「${entry.name}」，进入目标目录后粘贴"
                }
                ActionMenuItem("剪切", Icons.Rounded.ContentCut) {
                    clipboard = listOf(entry.path)
                    clipboardIsCut = true
                    actionTarget = null
                    notice = "已剪切「${entry.name}」，进入目标目录后粘贴"
                }
                if (entry.isDirectory) {
                    ActionMenuItem("粘贴到此处", Icons.Rounded.DriveFileMove, enabled = clipboard != null) {
                        actionTarget = null
                        scope.launch {
                            val sources = clipboard ?: return@launch
                            val cut = clipboardIsCut
                            val result = withContext(Dispatchers.IO) {
                                if (cut) FileManagerOps.move(runShell, sources, entry.path)
                                else FileManagerOps.copy(runShell, sources, entry.path)
                            }
                            notice = result.message
                            clipboard = null
                            clipboardIsCut = false
                            reload()
                        }
                    }
                }
                ActionMenuItem("重命名", Icons.Rounded.Edit) {
                    renameTarget = entry
                    renameText = entry.name
                    actionTarget = null
                }
                ActionMenuItem("属性", Icons.Rounded.Info) {
                    infoTarget = entry
                    actionTarget = null
                }
                ActionMenuItem("删除", Icons.Rounded.Delete, danger = true) {
                    pendingDelete = listOf(entry.path)
                    actionTarget = null
                }
                Spacer(Modifier.height(8.dp))
                MiuixDialogActions(
                    confirmText = "关闭",
                    onCancel = { actionTarget = null },
                    onConfirm = { actionTarget = null },
                )
            }
        }
    }

    // ---------- 删除确认（危险路径红字） ----------
    pendingDelete?.let { paths ->
        val risk = FilePathRisk.assess(paths, destructive = true)
        EtaWindowDialog(
            show = true,
            title = "删除 ${paths.size} 项？",
            summary = buildString {
                append(paths.take(3).joinToString("\n") { it.trimEnd('/').substringAfterLast('/') })
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
                            FileManagerOps.moveToTrash(runShell, paths)
                        }
                        notice = result.message
                        pendingDelete = null
                        reload()
                    }
                },
            )
        }
    }

    // ---------- 新建文件夹 ----------
    if (showNewFolder) {
        EtaWindowDialog(
            show = true,
            title = "新建文件夹",
            summary = "在 $path 下创建",
            onDismissRequest = { showNewFolder = false },
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
                    onCancel = { showNewFolder = false },
                    onConfirm = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FileManagerOps.createDirectory(runShell, path, newFolderName.trim())
                            }
                            notice = result.message
                            newFolderName = ""
                            showNewFolder = false
                            reload()
                        }
                    },
                )
            }
        }
    }

    // ---------- 重命名 ----------
    renameTarget?.let { entry ->
        EtaWindowDialog(
            show = true,
            title = "重命名",
            summary = entry.path,
            onDismissRequest = { renameTarget = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = "新名称",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                MiuixDialogActions(
                    confirmText = "保存",
                    confirmEnabled = renameText.isNotBlank() && renameText != entry.name,
                    onCancel = { renameTarget = null },
                    onConfirm = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FileManagerOps.rename(runShell, entry.path, renameText.trim())
                            }
                            notice = result.message
                            renameTarget = null
                            reload()
                        }
                    },
                )
            }
        }
    }

    // ---------- 属性 ----------
    infoTarget?.let { entry ->
        EtaWindowDialog(
            show = true,
            title = "属性",
            summary = FileManagerOps.describeEntry(entry),
            onDismissRequest = { infoTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = "关闭",
                onCancel = { infoTarget = null },
                onConfirm = { infoTarget = null },
            )
        }
    }

    // ---------- 文本预览 ----------
    previewText?.let { (title, body) ->
        EtaWindowDialog(
            show = true,
            title = title,
            summary = "${body.length} 字符",
            onDismissRequest = { previewText = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = body,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                MiuixDialogActions(
                    confirmText = "关闭",
                    onCancel = { previewText = null },
                    onConfirm = { previewText = null },
                )
            }
        }
    }

    // ---------- 跳转路径 ----------
    if (showGoTo) {
        EtaWindowDialog(
            show = true,
            title = "跳转到目录",
            summary = "输入绝对路径，例如 /data/adb/modules",
            onDismissRequest = { showGoTo = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = goToInput,
                    onValueChange = { goToInput = it },
                    label = "路径",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                MiuixDialogActions(
                    confirmText = "前往",
                    confirmEnabled = goToInput.trim().startsWith("/"),
                    onCancel = { showGoTo = false },
                    onConfirm = {
                        showGoTo = false
                        reload(goToInput.trim())
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

/** 一行文件：单击打开，长按操作；多选模式下单击勾选。 */
@Composable
private fun FileRow(
    entry: FileManagerOps.Entry,
    selected: Boolean,
    selectMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MiuixTheme.colorScheme.surface
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Text(
                text = if (selected) "✓" else "○",
                style = MiuixTheme.textStyles.body2,
                color = if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (entry.isDirectory) EtaPreferenceColors.Yellow
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            // 名字用 maxLines = 2：单栏宽度足够时一行显示全，特别长的会折行而不是被截成
            // "33094010…" 这种认不出来的样子。
            Text(
                text = entry.name,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDirectory) "目录 · ${entry.permissions}"
                else "${FileManagerOps.formatSize(entry.sizeBytes)} · ${entry.permissions}",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 长按菜单里的一项。 */
@Composable
private fun ActionMenuItem(
    text: String,
    icon: ImageVector,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                danger -> MiuixTheme.colorScheme.error
                else -> MiuixTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.width(12.dp))
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

/** 底部剪贴板条：粘贴 / 取消。 */
@Composable
private fun ClipboardBar(
    count: Int,
    isCut: Boolean,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${if (isCut) "剪切" else "复制"}了 $count 项",
            style = MiuixTheme.textStyles.subtitle,
            modifier = Modifier.weight(1f),
        )
        EtaTextButton(text = "取消", onClick = onClear)
        EtaTextButton(text = "粘贴到此处", onClick = onPaste)
    }
}

/** 底部多选操作条。 */
@Composable
private fun SelectionBar(
    count: Int,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
) {
    val enabled = count > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "已选 $count 项",
            style = MiuixTheme.textStyles.subtitle,
            modifier = Modifier.weight(1f),
        )
        EtaTextButton(text = "复制", enabled = enabled, onClick = onCopy)
        EtaTextButton(text = "剪切", enabled = enabled, onClick = onCut)
        EtaTextButton(text = "删除", enabled = enabled, onClick = onDelete)
    }
}

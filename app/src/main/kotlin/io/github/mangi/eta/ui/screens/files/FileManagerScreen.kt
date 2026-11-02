package io.github.mangi.eta.ui.screens.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** 排序字段。 */
private enum class SortMode(val label: String) {
    Name("名称"),
    Size("大小"),
    Time("时间"),
}

/**
 * 一栏的可变状态。
 *
 * 单独抽出来是因为双栏要求**两栏各自**记住路径、列表、勾选和历史；
 * 用一堆并列的 `leftXxx` / `rightXxx` 变量会让代码翻倍且容易串。
 */
private class PaneState(initialPath: String) {
    var path by mutableStateOf(initialPath)
    var listing by mutableStateOf<FileManagerOps.Listing?>(null)
    var loading by mutableStateOf(true)
    var selected by mutableStateOf<Set<String>>(emptySet())
}

/**
 * 文件管理器（对齐 MT 的交互，用户明确要求"集全"）。
 *
 * 交互地图：
 * ```
 * ┌─────────────────────────────────────┐
 * │ ← 返回       文件管理    搜索 排序 刷新 │  ← 顶栏
 * ├──────────────────┬──────────────────┤
 * │ ↑ /sdcard  ＋ ⋯  │ ↑ /data/local/tmp ＋ ⋯│  ← 每栏路径行（↑ = 返回上一层）
 * │ 📄 a.txt         │ 📁 04-工具-tools    │
 * │ 📁 123云盘        │ 📄 README.md       │  ← 单击打开；长按=多选
 * ├──────────────────┴──────────────────┤
 * │ 复制 剪切 删除 重命名 属性 全选 退出   │  ← 多选时出现
 * │ 复制了 2 项        [粘贴到此处] [取消] │  ← 复制/剪切后出现
 * └─────────────────────────────────────┘
 * ```
 *
 * 关键设计决定（都是被真实反馈逼出来的）：
 * - **每栏都有自己的「↑ 返回上一层」**：以前只能点顶栏返回，一点就退出文件管理器回到首页，
 *   进深目录后想回上一级必须重新进 —— 这是用户明确抱怨的点。
 * - **点文本文件直接进编辑器**：以前只弹属性框，用户说"只能看不能编辑"。
 *   无扩展名（如 `.uuid`）用 `file` 命令判定是不是文本，不再靠猜扩展名。
 * - **长按 = 进入多选**（MT 习惯）：不再弹单个文件的菜单，避免"多选"和"操作菜单"两套逻辑打架。
 * - **底部条只在需要时出现**：常驻按钮既占地方又容易误触。
 */
@Composable
internal fun FileManagerScreen(
    initialPath: String,
    onBack: () -> Unit,
    runShell: (String) -> String,
    /** 传了就是「选文件」模式（附件按钮用）：底部变成「确认选择」。 */
    onPickPaths: ((List<String>) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val pickMode = onPickPaths != null

    val left = remember { PaneState(initialPath) }
    val right = remember { PaneState("/data/local/tmp/eta") }

    var dualPane by remember { mutableStateOf(true) }
    var activeIsLeft by remember { mutableStateOf(true) }
    var selectMode by remember { mutableStateOf(false) }
    var clipboard by remember { mutableStateOf<List<String>?>(null) }
    var clipboardIsCut by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.Name) }
    var sortAscending by remember { mutableStateOf(true) }
    var showHidden by remember { mutableStateOf(true) }

    // 搜索
    var searchActive by remember { mutableStateOf(false) }
    var searchKeyword by remember { mutableStateOf("") }
    var searchEntries by remember { mutableStateOf<List<FileManagerOps.Entry>>(emptyList()) }
    var searchTruncated by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }

    // 弹窗
    var showPaneMenu by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var goToInput by remember { mutableStateOf("") }
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }
    var newNameInput by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<FileManagerOps.Entry?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var infoText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<List<String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 编辑器
    var editorPath by remember { mutableStateOf<String?>(null) }
    var editorName by remember { mutableStateOf("") }
    var editorText by remember { mutableStateOf("") }
    var editorDirty by remember { mutableStateOf(false) }

    // 内置图片查看器：文件名 + 完整路径 + 已解码的位图（路径留着给"用其他应用打开"用）
    var imageView by remember { mutableStateOf<Triple<String, String, android.graphics.Bitmap>?>(null) }

    // 多选时"当前操作栏"指向那一栏：哪栏有选中就作用于哪栏
    val selectedPaths = if (activeIsLeft) left.selected else right.selected
    val activePath = if (activeIsLeft) left.path else right.path

    fun activePane(): PaneState = if (activeIsLeft) left else right

    fun load(pane: PaneState, target: String = pane.path) {
        pane.path = target
        pane.loading = true
        // 故意不清空 selected：多选要能跨目录累积（MT 习惯），清空交给"退出/清空"按钮
        scope.launch {
            pane.listing = withContext(Dispatchers.IO) { FileManagerOps.list(runShell, target) }
            pane.loading = false
        }
    }

    fun refreshBoth() {
        load(left)
        if (dualPane) load(right)
    }

    LaunchedEffect(initialPath) {
        load(left, initialPath)
        load(right, "/data/local/tmp/eta")
    }

    /** 当前栏要显示的条目（搜索优先，其次目录内容，带排序与隐藏过滤）。 */
    fun displayEntries(pane: PaneState): List<FileManagerOps.Entry> {
        if (searchActive && pane === activePane()) {
            return searchEntries
        }
        val raw = pane.listing?.entries.orEmpty()
        val filtered = if (showHidden) raw else raw.filterNot { it.name.startsWith(".") }
        val cmp = when (sortMode) {
            SortMode.Name -> compareBy<FileManagerOps.Entry> { it.name.lowercase() }
            SortMode.Size -> compareBy { it.sizeBytes }
            SortMode.Time -> compareBy { it.modifiedAtMillis }
        }
        val dirs = filtered.filter { it.isDirectory }.sortedWith(cmp)
        val files = filtered.filterNot { it.isDirectory }.sortedWith(cmp)
        return (if (sortAscending) dirs else dirs.reversed()) + (if (sortAscending) files else files.reversed())
    }

    fun toggleSelection(pane: PaneState, path: String) {
        pane.selected = if (path in pane.selected) pane.selected - path else pane.selected + path
    }

    /** 找条目：先查当前栏列表，再查搜索结果（搜索结果里的项不在目录列表里）。 */
    fun findEntry(path: String): FileManagerOps.Entry? =
        (if (activeIsLeft) left.listing else right.listing)?.entries?.firstOrNull { it.path == path }
            ?: searchEntries.firstOrNull { it.path == path }

    fun parentOf(current: String): String? {
        val trimmed = current.trimEnd('/')
        if (trimmed.isEmpty() || trimmed == "/") return null
        val parent = trimmed.substringBeforeLast('/', "/")
        return if (parent == current) null else parent
    }

    /** 粘贴：把剪贴板内容复制/移动到当前栏目录。 */
    fun paste() {
        val sources = clipboard ?: return
        val cut = clipboardIsCut
        val target = activePane().path
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (cut) FileManagerOps.move(runShell, sources, target) else FileManagerOps.copy(runShell, sources, target)
            }
            notice = result.message
            clipboard = null
            clipboardIsCut = false
            refreshBoth()
        }
    }


    fun openEditor(entry: FileManagerOps.Entry) {
        scope.launch {
            val (result, truncated) = withContext(Dispatchers.IO) {
                FileManagerOps.readForEdit(runShell, entry.path)
            }
            if (!result.ok) {
                notice = result.message
                return@launch
            }
            editorPath = entry.path
            editorName = entry.name
            editorText = result.message
            editorDirty = false
            if (truncated) notice = "文件过大，只载入了前 512 KB（保存会覆盖原文件，请谨慎）"
        }
    }

    fun showInfo(entry: FileManagerOps.Entry) {
        scope.launch {
            val md5 = withContext(Dispatchers.IO) {
                if (entry.isDirectory) "" else FileManagerOps.md5(runShell, entry.path)
            }
            infoText = "属性" to buildString {
                append(FileManagerOps.describeEntry(entry))
                if (md5.isNotBlank()) append("\nMD5：").append(md5)
            }
        }
    }

    /** 打开内置图片查看器：把字节读进来，按屏幕尺寸采样解码，避免大图 OOM。 */
    fun openImage(entry: FileManagerOps.Entry) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val bytes = FileManagerOps.readBytesDirect(entry.path) ?: return@withContext null
                runCatching {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    // 按最长边 2048 采样：够看清，又不会因为一张 8000px 的照片吃光内存
                    var sample = 1
                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }.getOrNull()
            }
            if (bitmap != null) {
                imageView = Triple(entry.name, entry.path, bitmap)
            } else {
                notice = "读不到这张图（可能不是有效图片、超过 48 MB，或它在应用无权访问的位置）"
                showInfo(entry)
            }
        }
    }

    /** 按类型打开文件：文本进编辑器，图片进内置查看器，音视频交系统，压缩包看内容，其它给属性。 */
    fun openFile(entry: FileManagerOps.Entry) {
        when {
            FileManagerOps.looksLikeText(entry.name) -> openEditor(entry)

            // 图片走内置查看器：外部打开需要 FileProvider，而 targetSdk 37 下 file:// 被系统禁止
            // （这正是"点图片打不开"的根因），内置查看器不受这条限制，也不依赖外部应用。
            FileManagerOps.looksLikeImage(entry.name) -> openImage(entry)

            FileManagerOps.looksLikeMedia(entry.name) -> scope.launch {
                val result = withContext(Dispatchers.IO) {
                    FileManagerOps.openExternal(runShell, entry.path, FileManagerOps.mimeOf(entry.name))
                }
                if (!result.ok) {
                    notice = result.message
                    showInfo(entry)
                }
            }

            FileManagerOps.looksLikeArchive(entry.name) -> scope.launch {
                val result = withContext(Dispatchers.IO) { FileManagerOps.listArchive(runShell, entry.path) }
                infoText = "「${entry.name}」内容" to result.message
            }

            else -> {
                // 无扩展名或未知类型：用 file 命令判定，能当文本读的就进编辑器（.uuid 属于这种）
                scope.launch {
                    val isText = withContext(Dispatchers.IO) { FileManagerOps.isTextFile(runShell, entry.path) }
                    if (isText) openEditor(entry) else showInfo(entry)
                }
            }
        }
    }


    fun runSearch() {
        val dir = activePane().path
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                FileManagerOps.search(runShell, dir, searchKeyword)
            }
            searchEntries = result.entries
            searchTruncated = result.truncated
            searchActive = true
            if (result.error != null) notice = result.error
            else if (result.entries.isEmpty()) notice = "没有找到匹配「$searchKeyword」的文件"
        }
    }

    // ============================================================ 编辑器 / 看图整页
    val editing = editorPath != null
    val viewing = imageView != null
    MiuixScaffold(
        title = when {
            editing -> editorName
            viewing -> imageView?.first.orEmpty()
            selectMode -> "已选 ${selectedPaths.size} 项"
            else -> "文件管理"
        },
        onBack = {
            when {
                viewing -> imageView = null
                editing -> {
                    if (editorDirty) {
                        notice = "有未保存的修改，请先点保存或再按一次返回放弃"
                        editorDirty = false
                    } else {
                        editorPath = null
                    }
                }
                selectMode -> {
                    selectMode = false
                    left.selected = emptySet()
                    right.selected = emptySet()
                }
                else -> onBack()
            }
        },
        actions = {
            if (viewing) {
                EtaTextButton(
                    text = "用其他应用打开",
                    onClick = {
                        val current = imageView ?: return@EtaTextButton
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FileManagerOps.openExternal(
                                    runShell,
                                    current.second,
                                    FileManagerOps.mimeOf(current.first),
                                )
                            }
                            notice = result.message
                        }
                    },
                )
            } else if (editing) {
                EtaTextButton(
                    text = "保存",
                    enabled = editorDirty,
                    onClick = {
                        val path = editorPath ?: return@EtaTextButton
                        val text = editorText
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FileManagerOps.writeText(runShell, path, text)
                            }
                            notice = result.message
                            if (result.ok) {
                                editorDirty = false
                                refreshBoth()
                            }
                        }
                    },
                )
            } else {
                IconButton(onClick = {
                    goToInput = activePane().path
                    showGoTo = true
                }) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索或跳转", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = { refreshBoth() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = {
                    dualPane = !dualPane
                    if (dualPane) load(right)
                }) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = "单/双栏", modifier = Modifier.size(22.dp))
                }
                if (!pickMode) {
                    IconButton(onClick = {
                        selectMode = !selectMode
                        if (!selectMode) {
                            left.selected = emptySet()
                            right.selected = emptySet()
                        }
                    }) {
                        Icon(Icons.Rounded.CheckBox, contentDescription = "多选", modifier = Modifier.size(22.dp))
                    }
                }
            }
        },
    ) { paddingValues, _, _ ->
        if (viewing) {
            // ---------- 内置图片查看器 ----------
            val (name, path, bitmap) = imageView!!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                    .background(androidx.compose.ui.graphics.Color.Black),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(
                        text = "${bitmap.width} × ${bitmap.height} · ${FileManagerOps.formatSize(java.io.File(path).length())}",
                        style = MiuixTheme.textStyles.subtitle,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                    Text(
                        text = path,
                        style = MiuixTheme.textStyles.subtitle,
                        color = androidx.compose.ui.graphics.Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EtaTextButton(text = "关闭", onClick = { imageView = null })
                        EtaTextButton(
                            text = "用其他应用打开",
                            onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        FileManagerOps.openExternal(runShell, path, FileManagerOps.mimeOf(name))
                                    }
                                    notice = result.message
                                }
                            },
                        )
                    }
                }
            }
        } else if (editing) {
            // ---------- 编辑器 ----------
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = editorPath.orEmpty(),
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                TextField(
                    value = editorText,
                    onValueChange = {
                        editorText = it
                        editorDirty = true
                    },
                    label = "文件内容",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EtaTextButton(text = "关闭", onClick = { editorPath = null })
                    EtaTextButton(
                        text = if (editorDirty) "保存*" else "保存",
                        enabled = editorDirty,
                        onClick = {
                            val path = editorPath ?: return@EtaTextButton
                            val text = editorText
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    FileManagerOps.writeText(runShell, path, text)
                                }
                                notice = result.message
                                if (result.ok) {
                                    editorDirty = false
                                    refreshBoth()
                                }
                            }
                        },
                    )
                }
            }
        } else {
            // ---------- 浏览 ----------
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
            ) {
                // 搜索结果提示条
                if (searchActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "搜索「$searchKeyword」：${searchEntries.size} 项${if (searchTruncated) "（已截断）" else ""}",
                            style = MiuixTheme.textStyles.subtitle,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        EtaTextButton(text = "关闭", onClick = {
                            searchActive = false
                            searchEntries = emptyList()
                        })
                    }
                }

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    PaneView(
                        pane = left,
                        isActive = activeIsLeft,
                        entries = displayEntries(left),
                        searchMode = searchActive && activeIsLeft,
                        selectMode = selectMode || pickMode,
                        showHidden = showHidden,
                        onActivate = { activeIsLeft = true },
                        onTap = { entry ->
                            activeIsLeft = true
                            when {
                                pickMode && entry.isDirectory -> load(left, entry.path)
                                pickMode -> toggleSelection(left, entry.path)
                                selectMode && entry.isDirectory && entry.path !in left.selected -> load(left, entry.path)
                                selectMode -> toggleSelection(left, entry.path)
                                entry.isDirectory -> load(left, entry.path)
                                else -> openFile(entry)
                            }
                        },
                        onLongPress = { entry ->
                            activeIsLeft = true
                            if (pickMode) {
                                toggleSelection(left, entry.path)
                            } else {
                                selectMode = true
                                left.selected = left.selected + entry.path
                            }
                        },
                        onUp = { parentOf(left.path)?.let { load(left, it) } },
                        onGoPath = {
                            activeIsLeft = true
                            goToInput = left.path
                            showGoTo = true
                        },
                        onPaneMenu = {
                            activeIsLeft = true
                            showPaneMenu = true
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (dualPane) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MiuixTheme.colorScheme.dividerLine),
                        )
                        PaneView(
                            pane = right,
                            isActive = !activeIsLeft,
                            entries = displayEntries(right),
                            searchMode = searchActive && !activeIsLeft,
                            selectMode = selectMode || pickMode,
                            showHidden = showHidden,
                            onActivate = { activeIsLeft = false },
                            onTap = { entry ->
                                activeIsLeft = false
                                when {
                                    pickMode && entry.isDirectory -> load(right, entry.path)
                                    pickMode -> toggleSelection(right, entry.path)
                                    selectMode && entry.isDirectory && entry.path !in right.selected -> load(right, entry.path)
                                    selectMode -> toggleSelection(right, entry.path)
                                    entry.isDirectory -> load(right, entry.path)
                                    else -> openFile(entry)
                                }
                            },
                            onLongPress = { entry ->
                                activeIsLeft = false
                                if (pickMode) {
                                    toggleSelection(right, entry.path)
                                } else {
                                    selectMode = true
                                    right.selected = right.selected + entry.path
                                }
                            },
                            onUp = { parentOf(right.path)?.let { load(right, it) } },
                            onGoPath = {
                                activeIsLeft = false
                                goToInput = right.path
                                showGoTo = true
                            },
                            onPaneMenu = {
                                activeIsLeft = false
                                showPaneMenu = true
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // ---------- 底部：选文件 / 多选 / 剪贴板 ----------
                when {
                    pickMode -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MiuixTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val picked = left.selected + right.selected
                        Text(
                            text = if (picked.isEmpty()) "点文件勾选 · 长按可勾选文件夹 · 点目录进入" else "已选 ${picked.size} 项",
                            style = MiuixTheme.textStyles.subtitle,
                            modifier = Modifier.weight(1f),
                        )
                        EtaTextButton(
                            text = if (picked.isEmpty()) "取消" else "清空",
                            onClick = {
                                if (picked.isEmpty()) {
                                    onBack()
                                } else {
                                    left.selected = emptySet()
                                    right.selected = emptySet()
                                }
                            },
                        )
                        EtaTextButton(
                            text = "确认选择",
                            enabled = picked.isNotEmpty(),
                            onClick = { onPickPaths?.invoke(picked.toList()) },
                        )
                    }

                    selectMode -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MiuixTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val count = selectedPaths.size
                        BarAction("复制", Icons.Rounded.ContentCopy, enabled = count > 0) {
                            clipboard = selectedPaths.toList()
                            clipboardIsCut = false
                            selectMode = false
                            left.selected = emptySet()
                            right.selected = emptySet()
                            notice = "已复制 $count 项，进入目标目录后点「粘贴到此处」"
                        }
                        BarAction("剪切", Icons.Rounded.ContentCut, enabled = count > 0) {
                            clipboard = selectedPaths.toList()
                            clipboardIsCut = true
                            selectMode = false
                            left.selected = emptySet()
                            right.selected = emptySet()
                            notice = "已剪切 $count 项，进入目标目录后点「粘贴到此处」"
                        }
                        BarAction("粘贴", Icons.Rounded.DriveFileMove, enabled = clipboard != null) { paste() }
                        BarAction("重命名", Icons.Rounded.Edit, enabled = count == 1) {
                            findEntry(selectedPaths.first())?.let { entry ->
                                renameInput = entry.name
                                renameTarget = entry
                            }
                        }
                        BarAction("属性", Icons.Rounded.Info, enabled = count == 1) {
                            findEntry(selectedPaths.first())?.let { showInfo(it) }
                        }
                        BarAction("删除", Icons.Rounded.Delete, enabled = count > 0, danger = true) {
                            pendingDelete = selectedPaths.toList()
                        }
                        BarAction("全选", Icons.Rounded.CheckBox, enabled = true) {
                            val all = displayEntries(activePane()).map { it.path }.toSet()
                            if (activeIsLeft) left.selected = all else right.selected = all
                        }
                        BarAction("退出", Icons.Rounded.Refresh, enabled = true) {
                            selectMode = false
                            left.selected = emptySet()
                            right.selected = emptySet()
                        }
                    }

                    clipboard != null -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${if (clipboardIsCut) "剪切" else "复制"}了 ${clipboard?.size ?: 0} 项",
                            style = MiuixTheme.textStyles.subtitle,
                            modifier = Modifier.weight(1f),
                        )
                        EtaTextButton(text = "取消", onClick = {
                            clipboard = null
                            clipboardIsCut = false
                        })
                        EtaTextButton(text = "粘贴到此处", onClick = { paste() })
                    }
                }
            }
        }
    }

    // ============================================================ 弹窗区

    // 搜索 / 跳转
    if (showGoTo) {
        EtaWindowDialog(
            show = true,
            title = "跳转 / 搜索",
            summary = "输入绝对路径直接前往；输入关键词则搜索当前目录",
            onDismissRequest = { showGoTo = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = goToInput,
                    onValueChange = { goToInput = it },
                    label = "路径或关键词",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                MiuixDialogActions(
                    confirmText = "前往",
                    confirmEnabled = goToInput.isNotBlank(),
                    onCancel = { showGoTo = false },
                    onConfirm = {
                        val value = goToInput.trim()
                        showGoTo = false
                        if (value.startsWith("/")) {
                            load(activePane(), value)
                        } else {
                            searchKeyword = value
                            runSearch()
                        }
                    },
                )
            }
        }
    }

    // 栏内菜单：排序 / 隐藏文件 / 新建
    if (showPaneMenu) {
        EtaWindowDialog(
            show = true,
            title = "视图与新建",
            summary = activePane().path,
            onDismissRequest = { showPaneMenu = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MenuRow("排序：${sortMode.label}${if (sortAscending) " ↑" else " ↓"}") {
                    val order = SortMode.entries
                    val next = order[(order.indexOf(sortMode) + 1) % order.size]
                    if (next == sortMode) sortAscending = !sortAscending else sortMode = next
                    showPaneMenu = false
                }
                MenuRow(if (showHidden) "隐藏文件：显示中（点一下隐藏）" else "隐藏文件：已隐藏（点一下显示）") {
                    showHidden = !showHidden
                    showPaneMenu = false
                }
                MenuRow("新建文件夹") {
                    newNameInput = ""
                    showNewFolder = true
                    showPaneMenu = false
                }
                MenuRow("新建文件") {
                    newNameInput = ""
                    showNewFile = true
                    showPaneMenu = false
                }
                MenuRow("粘贴到此处", enabled = clipboard != null) {
                    showPaneMenu = false
                    paste()
                }
                Spacer(Modifier.height(8.dp))
                MiuixDialogActions(
                    confirmText = "关闭",
                    onCancel = { showPaneMenu = false },
                    onConfirm = { showPaneMenu = false },
                )
            }
        }
    }

    // 新建文件夹
    if (showNewFolder) {
        EtaWindowDialog(
            show = true,
            title = "新建文件夹",
            summary = activePane().path,
            onDismissRequest = { showNewFolder = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = newNameInput,
                    onValueChange = { newNameInput = it },
                    label = "文件夹名",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                MiuixDialogActions(
                    confirmText = "创建",
                    confirmEnabled = newNameInput.isNotBlank(),
                    onCancel = { showNewFolder = false },
                    onConfirm = {
                        val pane = activePane()
                        val name = newNameInput.trim()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FileManagerOps.createDirectory(runShell, pane.path, name)
                            }
                            notice = result.message
                            showNewFolder = false
                            load(pane)
                        }
                    },
                )
            }
        }
    }

    // 新建文件
    if (showNewFile) {
        EtaWindowDialog(
            show = true,
            title = "新建文件",
            summary = activePane().path,
            onDismissRequest = { showNewFile = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = newNameInput,
                    onValueChange = { newNameInput = it },
                    label = "文件名",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                MiuixDialogActions(
                    confirmText = "创建",
                    confirmEnabled = newNameInput.isNotBlank(),
                    onCancel = { showNewFile = false },
                    onConfirm = {
                        val pane = activePane()
                        val name = newNameInput.trim()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FileManagerOps.createFile(runShell, pane.path, name)
                            }
                            notice = result.message
                            showNewFile = false
                            load(pane)
                        }
                    },
                )
            }
        }
    }

    // 重命名
    renameTarget?.let { entry ->
        EtaWindowDialog(
            show = true,
            title = "重命名",
            summary = entry.path,
            onDismissRequest = { renameTarget = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = "新名称",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(12.dp))
                MiuixDialogActions(
                    confirmText = "确定",
                    confirmEnabled = renameInput.isNotBlank() && renameInput != entry.name,
                    onCancel = { renameTarget = null },
                    onConfirm = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FileManagerOps.rename(runShell, entry.path, renameInput.trim())
                            }
                            notice = result.message
                            renameTarget = null
                            selectMode = false
                            left.selected = emptySet()
                            right.selected = emptySet()
                            refreshBoth()
                        }
                    },
                )
            }
        }
    }

    // 属性 / 压缩包内容 / 文本预览
    infoText?.let { (title, body) ->
        EtaWindowDialog(
            show = true,
            title = title,
            onDismissRequest = { infoText = null },
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
                    onCancel = { infoText = null },
                    onConfirm = { infoText = null },
                )
            }
        }
    }

    // 删除确认
    pendingDelete?.let { paths ->
        val risk = FilePathRisk.assess(paths, destructive = true)
        EtaWindowDialog(
            show = true,
            title = "删除 ${paths.size} 项？",
            summary = buildString {
                append(paths.take(3).joinToString("\n") { it.trimEnd('/').substringAfterLast('/') })
                if (paths.size > 3) append("\n…共 ${paths.size} 项")
                append("\n\n会移到回收站，${FilePathRisk.TRASH_RETENTION_DAYS} 天后自动清除。")
                if (risk.warning.isNotBlank()) append("\n\n⚠ ").append(risk.warning)
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
                        selectMode = false
                        left.selected = emptySet()
                        right.selected = emptySet()
                        refreshBoth()
                    }
                },
            )
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

/** 单栏：路径行（↑ 上级 + 路径 + ＋新建 + ⋯菜单）+ 文件列表。 */
@Composable
private fun PaneView(
    pane: PaneState,
    isActive: Boolean,
    entries: List<FileManagerOps.Entry>,
    searchMode: Boolean,
    selectMode: Boolean,
    showHidden: Boolean,
    onActivate: () -> Unit,
    onTap: (FileManagerOps.Entry) -> Unit,
    onLongPress: (FileManagerOps.Entry) -> Unit,
    onUp: () -> Unit,
    onGoPath: () -> Unit,
    onPaneMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(
                if (isActive) MiuixTheme.colorScheme.surface
                else MiuixTheme.colorScheme.surfaceContainer
            ),
    ) {
        // 路径行：↑ 返回上一层（用户明确要的）；点中间路径可跳转
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isActive) MiuixTheme.colorScheme.surfaceContainerHigh
                    else MiuixTheme.colorScheme.surfaceContainer
                )
                .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUp, enabled = true) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = "返回上一层",
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onGoPath() },
            ) {
                Text(
                    text = pane.path,
                    style = MiuixTheme.textStyles.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        pane.loading -> "加载中…"
                        pane.listing?.ok == false -> pane.listing?.error.orEmpty()
                        searchMode -> "搜索结果 ${entries.size} 项"
                        else -> "${entries.size} 项"
                    },
                    style = MiuixTheme.textStyles.subtitle,
                    color = if (pane.listing?.ok == false) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPaneMenu) {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = "更多", modifier = Modifier.size(20.dp))
            }
        }

        if (!pane.loading && pane.listing?.ok == true && entries.isEmpty()) {
            Text(
                text = if (showHidden) "（空目录）" else "（空目录，或只有隐藏文件）",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        selected = entry.path in pane.selected,
                        selectMode = selectMode,
                        showFullPath = searchMode,
                        onTap = {
                            onActivate()
                            onTap(entry)
                        },
                        onLongPress = {
                            onActivate()
                            onLongPress(entry)
                        },
                    )
                }
            }
        }
    }
}

/** 一行：图标 + 名字（最多两行，不再被截成"33094010…"）+ 大小/权限。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileManagerOps.Entry,
    selected: Boolean,
    selectMode: Boolean,
    showFullPath: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Text(
                text = if (selected) "✓" else "○",
                style = MiuixTheme.textStyles.body2,
                color = if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (entry.isDirectory) EtaPreferenceColors.Yellow
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (showFullPath) entry.path
                else if (entry.isDirectory) "目录" else FileManagerOps.formatSize(entry.sizeBytes),
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 底部操作栏里的一项（图标 + 文字，竖排）。 */
@Composable
private fun BarAction(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        danger -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.primary
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = text, modifier = Modifier.size(19.dp), tint = tint)
        Text(text = text, style = MiuixTheme.textStyles.subtitle, color = tint)
    }
}

/** 弹窗里的一行可点项。 */
@Composable
private fun MenuRow(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = if (enabled) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

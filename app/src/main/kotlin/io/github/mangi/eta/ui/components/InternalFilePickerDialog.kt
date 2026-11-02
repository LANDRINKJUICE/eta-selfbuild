package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
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
import io.github.mangi.eta.agent.files.FileShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.layout.DialogDefaults

/**
 * 内置文件选择器：给附件按钮用，替代系统文件选择器。
 *
 * 与系统选择器的分工（为什么保留两者）：
 * - **内置**（本组件）：用 root shell 浏览任意目录，返回**真实路径**，能进 `/data`、`/sdcard/Android/data`
 *   这类系统选择器进不去的地方。
 * - **系统**：返回带持久授权的 content URI，适合受保护存储里的媒体文件。
 *
 * 这里做成对话框而不是独立页面，是为了**选完直接回填到输入框**，不用来回跳页。
 */
@Composable
internal fun InternalFilePickerDialog(
    initialPath: String = "/sdcard/Download",
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var path by remember { mutableStateOf(initialPath) }
    var listing by remember { mutableStateOf<FileManagerOps.Listing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    fun reload(target: String) {
        path = target
        loading = true
        scope.launch {
            listing = withContext(Dispatchers.IO) { FileManagerOps.list(FileShellRunner::run, target) }
            loading = false
        }
    }

    LaunchedEffect(initialPath) { reload(initialPath) }

    WindowDialog(
        show = true,
        cornerRadius = DialogDefaults.CornerRadius,
        title = "选择文件",
        summary = if (selected.isEmpty()) path else "已选 ${selected.size} 项",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 路径行：点一下返回上级
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val parent = path.trimEnd('/').substringBeforeLast('/', "/")
                        if (parent != path) reload(parent)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = EtaPreferenceColors.Blue,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        loading -> "加载中… $path"
                        listing?.ok == false -> "${listing?.error.orEmpty()} · $path"
                        else -> "$path（点此返回上级）"
                    },
                    style = MiuixTheme.textStyles.subtitle,
                    color = if (listing?.ok == false) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(modifier = Modifier.heightIn(max = 360.dp)) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(listing?.entries.orEmpty(), key = { it.path }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        reload(entry.path)
                                    } else {
                                        selected = if (entry.path in selected) {
                                            selected - entry.path
                                        } else {
                                            selected + entry.path
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Rounded.Folder
                                else Icons.Rounded.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (entry.isDirectory) EtaPreferenceColors.Yellow
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MiuixTheme.textStyles.body2,
                                    color = if (entry.path in selected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (entry.isDirectory) "目录" else FileManagerOps.formatSize(entry.sizeBytes),
                                    style = MiuixTheme.textStyles.subtitle,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                            // 目录显示箭头提示，文件显示选中标记
                            Text(
                                text = if (entry.isDirectory) "进入 ›"
                                else if (entry.path in selected) "✓" else "○",
                                style = MiuixTheme.textStyles.subtitle,
                                color = if (entry.path in selected) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            MiuixDialogActions(
                confirmText = if (selected.isEmpty()) "添加" else "添加 ${selected.size} 项",
                confirmEnabled = selected.isNotEmpty(),
                onCancel = onDismiss,
                onConfirm = { onConfirm(selected.toList()) },
            )
        }
    }
}

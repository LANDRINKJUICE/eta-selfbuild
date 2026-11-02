package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.files.FileShellRunner
import io.github.mangi.eta.ui.screens.files.FileManagerScreen

/**
 * 附件按钮用的内置文件选择器。
 *
 * **踩过的坑（不要再退化）**：这里以前是另一套独立的小对话框，功能残缺（点文件只弹"大小"、
 * 目录进不去深层、没有长按操作）。用户的要求是"能内置 MT 最好"，
 * 所以现在**直接复用同一个 [FileManagerScreen]**——浏览、预览、长按操作全都一致，
 * 只是底部多一条「确认选择」。这样以后改文件管理器，选择器自动跟着升级，不会再出现两套界面。
 */
@Composable
internal fun InternalFilePicker(
    initialPath: String = "/sdcard/Download",
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var shown by remember { mutableStateOf(true) }
    if (shown) {
        FileManagerScreen(
            initialPath = initialPath,
            onBack = {
                shown = false
                onDismiss()
            },
            runShell = { command -> FileShellRunner.run(command) },
            onPickPaths = { paths ->
                shown = false
                onConfirm(paths)
            },
        )
    }
}

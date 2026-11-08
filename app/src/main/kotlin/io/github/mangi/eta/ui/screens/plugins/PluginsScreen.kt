package io.github.mangi.eta.ui.screens.plugins

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.plugin.PluginEntry
import io.github.mangi.eta.agent.plugin.PluginImportResult
import io.github.mangi.eta.agent.plugin.PluginInstaller
import io.github.mangi.eta.agent.plugin.PluginKind
import io.github.mangi.eta.agent.plugin.PluginManifest
import io.github.mangi.eta.agent.plugin.PluginRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/**
 * 插件列表页（设置 → 插件 / 首页「⋯」菜单 → 插件）。
 *
 * 列表只回答两件事：**这是什么**（名称 + 类型）和**能不能用**（状态）。
 * 权限、开关、调用记录都放在详情页 —— 一屏塞太多反而看不清（用户要求「简洁」）。
 */
@Composable
internal fun PluginsScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // lint：Composable 里不能用 LocalContext 取字符串资源（配置变化不会失效），
    // 必须走 LocalResources.current —— 这里也正好是项目其它页面的既有写法。
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<PluginRow>>(emptyList()) }
    var scanToken by remember { mutableIntStateOf(0) }
    // 待确认删除的「加载失败」目录（null 表示没有）。
    var brokenPending by remember { mutableStateOf<PluginRow?>(null) }

    // 扫描要读磁盘，放 IO 线程；重新扫描（右上角或空状态按钮）通过 scanToken 触发。
    LaunchedEffect(scanToken) {
        rows = withContext(Dispatchers.IO) { loadRows(context) }
    }

    val zipTypes = arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { PluginInstaller.importZip(context, uri) }
            val message = when (result) {
                is PluginImportResult.Ok -> resources.getString(R.string.plugins_import_ok, result.name)
                is PluginImportResult.Failure -> result.message
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            scanToken += 1
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_plugins),
        onBack = onBack,
        actions = {
            IconButton(onClick = { importLauncher.launch(zipTypes) }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.plugins_import),
                )
            }
            IconButton(onClick = { scanToken += 1 }) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.plugins_rescan),
                )
            }
        },
    ) {
        item(key = "plugins") {
            EtaPreferenceGroupTitle(stringResource(R.string.plugins_configured, rows.size))
            EtaPreferenceGroup {
                if (rows.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.plugins_empty_title),
                        summary = stringResource(R.string.plugins_empty_summary),
                        icon = Icons.Rounded.Extension,
                        action = {
                            EtaTextButton(
                                text = stringResource(R.string.plugins_import),
                                onClick = { importLauncher.launch(zipTypes) },
                            )
                        },
                    )
                } else {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        val manifest = row.manifest
                        if (manifest != null) {
                            EtaArrowPreference(
                                title = row.title,
                                summary = row.summary,
                                onClick = { onNavigate(AppRoute.PluginDetail(manifest.id)) },
                            )
                        } else {
                            // 2026-11-08 补：加载失败的目录以前只有一行字，详情页进不去（onClick 为 null），
                            // 于是它的目录和权限记录**没有任何 UI 路径能清掉** —— 再导入同 id 会永久卡在
                            // 「已经有一个叫 X 的插件了」。这里给一个直接删除入口。
                            EtaArrowPreference(
                                title = row.title,
                                summary = row.summary + " · " + resources.getString(R.string.plugins_broken_delete),
                                onClick = { brokenPending = row },
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认：走的也是普通确认弹窗，不做「静默删除」——删的是磁盘上的目录。
    val deleting = brokenPending
    EtaWindowDialog(
        show = deleting != null,
        title = stringResource(R.string.plugins_broken_delete_title),
        summary = stringResource(R.string.plugins_broken_delete_summary, deleting?.title.orEmpty()),
        onDismissRequest = { brokenPending = null },
    ) {
        MiuixDialogActions(
            confirmText = stringResource(R.string.plugin_delete),
            cancelText = stringResource(R.string.action_cancel),
            destructive = true,
            onCancel = { brokenPending = null },
            onConfirm = {
                val target = deleting
                brokenPending = null
                if (target != null) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { File(target.directory).deleteRecursively() }
                            // 目录名与 id 可能不一致（加载失败时 id 都没解析出来），两个都清一遍。
                            runCatching { PluginRepository.forget(context, target.directory) }
                        }
                        scanToken += 1
                    }
                }
            },
        )
    }
}

/** 列表行需要的全部信息（扫描时一次算好，避免每行各自读偏好）。 */
private data class PluginRow(
    val title: String,
    val summary: String,
    /** null 表示这个目录加载失败（只展示目录名与原因，不给进详情）。 */
    val manifest: PluginManifest?,
    /** 插件目录的绝对路径；加载失败时也要有，删除入口靠它定位。 */
    val directory: String = "",
)

private fun loadRows(context: Context): List<PluginRow> = PluginRepository.scan(context).map { entry ->
    when (entry) {
        is PluginEntry.Broken -> PluginRow(
            title = entry.directoryName,
            summary = context.getString(R.string.plugins_load_failed) + " · " + entry.reason,
            manifest = null,
            directory = java.io.File(PluginRepository.pluginsDir(context), entry.directoryName).absolutePath,
        )

        is PluginEntry.Loaded -> {
            val manifest = entry.manifest
            val enabled = PluginRepository.isEnabled(context, manifest.id)
            val missing = manifest.requiredPermissions() - PluginRepository.granted(context, manifest.id)
            val summary = buildString {
                append(
                    if (manifest.kind == PluginKind.SHELL) {
                        context.getString(R.string.plugins_kind_shell)
                    } else {
                        context.getString(R.string.plugins_kind_http)
                    },
                )
                if (manifest.kind == PluginKind.SHELL) {
                    append(" · ").append(
                        if (manifest.exec?.environment == "linux") {
                            context.getString(R.string.plugins_env_linux)
                        } else {
                            context.getString(R.string.plugins_env_android)
                        },
                    )
                }
                when {
                    !enabled -> Unit
                    missing.isNotEmpty() ->
                        append(" · ").append(context.getString(R.string.plugins_state_need_grant))

                    else -> append(" · ").append(context.getString(R.string.plugins_state_enabled))
                }
            }
            PluginRow(title = manifest.name, summary = summary, manifest = manifest)
        }
    }
}

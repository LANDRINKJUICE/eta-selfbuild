package io.github.mangi.eta.ui.screens.plugins

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.plugin.PluginEntry
import io.github.mangi.eta.agent.plugin.PluginKind
import io.github.mangi.eta.agent.plugin.PluginManifest
import io.github.mangi.eta.agent.plugin.PluginPermission
import io.github.mangi.eta.agent.plugin.PluginRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 插件详情页：**启用开关 + 权限逐项授权 + 参数说明 + 删除**。
 *
 * 设计取舍（2026-11-08）：
 * - 权限默认全关，逐项列出来并各带一句人话说明 —— 用户要能一眼看出「它想干什么」。
 * - 必填权限没给齐时，页面顶部明确写出「还缺什么、所以不可用」，不留悬案。
 * - 删除是不可逆操作，走确认弹窗（和 MCP 服务器一致）。
 */
@Composable
internal fun PluginDetailScreen(
    pluginId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var reloadToken by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<PluginDetailState?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(reloadToken) {
        state = withContext(Dispatchers.IO) { loadDetail(context, pluginId) }
    }

    val detail = state
    val manifest = detail?.manifest

    MiuixScaffoldPage(
        title = manifest?.name ?: stringResource(R.string.route_plugins),
        onBack = onBack,
    ) {
        if (detail == null) return@MiuixScaffoldPage

        if (manifest == null) {
            item(key = "broken") {
                EtaPreferenceGroup {
                    ListEmptyState(
                        title = stringResource(R.string.plugins_load_failed),
                        summary = detail.reason,
                        icon = Icons.Rounded.ErrorOutline,
                    )
                }
            }
            return@MiuixScaffoldPage
        }

        item(key = "enable") {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = stringResource(R.string.plugin_enable_title),
                    summary = stringResource(R.string.plugin_enable_summary),
                    checked = detail.enabled,
                    onCheckedChange = { value ->
                        PluginRepository.setEnabled(context, manifest.id, value)
                        reloadToken += 1
                    },
                )
            }
        }

        item(key = "meta") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(
                        R.string.plugin_version_line,
                        manifest.version,
                        kindLabel(context, manifest),
                    ),
                    onClick = null,
                )
            }
        }

        item(key = "permissions") {
            EtaPreferenceGroupTitle(stringResource(R.string.plugin_permissions_title))
            EtaPreferenceGroup {
                if (manifest.permissions.isEmpty()) {
                    EtaArrowPreference(
                        title = stringResource(R.string.plugin_no_permission),
                        onClick = null,
                    )
                } else {
                    manifest.permissions.sortedBy { it.wire }.forEachIndexed { index, permission ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        EtaSwitchPreference(
                            title = stringResource(permissionTitle(permission)),
                            summary = stringResource(permissionSummary(permission)),
                            checked = permission in detail.granted,
                            onCheckedChange = { value ->
                                val next = detail.granted.toMutableSet()
                                if (value) next += permission else next -= permission
                                PluginRepository.setGranted(context, manifest.id, next)
                                reloadToken += 1
                            },
                        )
                    }
                }
            }
        }

        if (detail.missing.isNotEmpty()) {
            item(key = "missing") {
                PluginMissingGrantNotice(detail.missing)
            }
        }

        if (manifest.params.isNotEmpty()) {
            item(key = "params") {
                EtaPreferenceGroupTitle(stringResource(R.string.plugin_params_title))
                EtaPreferenceGroup {
                    manifest.params.forEachIndexed { index, param ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        val requirement = stringResource(
                            if (param.required) R.string.plugin_param_required else R.string.plugin_param_optional,
                        )
                        EtaArrowPreference(
                            title = param.name,
                            summary = listOf(param.description, requirement)
                                .filter(String::isNotBlank)
                                .joinToString(" · "),
                            onClick = null,
                        )
                    }
                }
            }
        }

        item(key = "delete") {
            EtaTextButton(
                text = stringResource(R.string.plugin_delete),
                onClick = { confirmDelete = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                colors = ButtonDefaults.textButtonColors(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                ),
            )
        }
    }

    EtaWindowDialog(
        show = confirmDelete,
        title = stringResource(R.string.plugin_delete_title),
        summary = stringResource(R.string.plugin_delete_summary, manifest?.name.orEmpty()),
        onDismissRequest = { confirmDelete = false },
    ) {
        MiuixDialogActions(
            confirmText = stringResource(R.string.plugin_delete),
            cancelText = stringResource(R.string.action_cancel),
            destructive = true,
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                runCatching {
                    if (manifest != null && manifest.directory.isNotBlank()) {
                        File(manifest.directory).deleteRecursively()
                    }
                    PluginRepository.forget(context, pluginId)
                }
                onBack()
            },
        )
    }
}

@Composable
private fun PluginMissingGrantNotice(missing: Set<PluginPermission>) {
    Text(
        text = stringResource(
            R.string.plugin_missing_grant,
            missing.sortedBy { it.wire }.joinToString("、") { it.wire },
        ),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    )
}

private data class PluginDetailState(
    val manifest: PluginManifest?,
    val reason: String,
    val enabled: Boolean,
    val granted: Set<PluginPermission>,
    val missing: Set<PluginPermission>,
)

private fun loadDetail(context: Context, pluginId: String): PluginDetailState {
    val entry = PluginRepository.scan(context).firstOrNull { candidate ->
        candidate is PluginEntry.Loaded && candidate.manifest.id == pluginId
    }
    return when (entry) {
        is PluginEntry.Loaded -> {
            val granted = PluginRepository.granted(context, entry.manifest.id)
            PluginDetailState(
                manifest = entry.manifest,
                reason = "",
                enabled = PluginRepository.isEnabled(context, entry.manifest.id),
                granted = granted,
                missing = entry.manifest.requiredPermissions() - granted,
            )
        }

        is PluginEntry.Broken -> PluginDetailState(null, entry.reason, false, emptySet(), emptySet())
        null -> PluginDetailState(null, "", false, emptySet(), emptySet())
    }
}

private fun kindLabel(context: Context, manifest: PluginManifest): String = when {
    manifest.kind == PluginKind.HTTP -> context.getString(R.string.plugins_kind_http)
    manifest.exec?.environment == "linux" ->
        context.getString(R.string.plugins_kind_shell) + " · " + context.getString(R.string.plugins_env_linux)

    else ->
        context.getString(R.string.plugins_kind_shell) + " · " + context.getString(R.string.plugins_env_android)
}

private fun permissionTitle(permission: PluginPermission): Int = when (permission) {
    PluginPermission.SHELL_ANDROID -> R.string.plugin_permission_shell_android
    PluginPermission.SHELL_LINUX -> R.string.plugin_permission_shell_linux
    PluginPermission.SHELL_ROOT -> R.string.plugin_permission_shell_root
    PluginPermission.NETWORK_LOCALHOST -> R.string.plugin_permission_network_localhost
    PluginPermission.NETWORK_ANY -> R.string.plugin_permission_network_any
    PluginPermission.FILES_READ -> R.string.plugin_permission_files_read
    PluginPermission.FILES_WRITE -> R.string.plugin_permission_files_write
}

private fun permissionSummary(permission: PluginPermission): Int = when (permission) {
    PluginPermission.SHELL_ANDROID -> R.string.plugin_permission_shell_android_summary
    PluginPermission.SHELL_LINUX -> R.string.plugin_permission_shell_linux_summary
    PluginPermission.SHELL_ROOT -> R.string.plugin_permission_shell_root_summary
    PluginPermission.NETWORK_LOCALHOST -> R.string.plugin_permission_network_localhost_summary
    PluginPermission.NETWORK_ANY -> R.string.plugin_permission_network_any_summary
    PluginPermission.FILES_READ -> R.string.plugin_permission_files_read_summary
    PluginPermission.FILES_WRITE -> R.string.plugin_permission_files_write_summary
}

package io.github.mangi.eta.ui.screens.plugins

import android.content.Context
import android.widget.Toast
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.plugin.PluginCallRecord
import io.github.mangi.eta.agent.plugin.PluginEntry
import io.github.mangi.eta.agent.plugin.PluginKind
import io.github.mangi.eta.agent.plugin.PluginManifest
import io.github.mangi.eta.agent.plugin.PluginInvoker
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 插件详情页：**启用开关 + 执行身份说明 + 权限逐项授权 + 参数 + 最近调用 + 删除**。
 *
 * 设计取舍（2026-11-08）：
 * - 权限默认全关、逐项列出一句人话说明；不给"按下去没有实际效果"的假开关
 *   （所以没有 files 权限 —— 见 PluginPermission 的注释）。
 * - 必填权限没给齐时明确写"还缺什么、所以不可用"，不留悬案。
 * - 最近调用直接铺在页面上，插件有没有在工作一眼可见。
 * - 删除是不可逆操作，走确认弹窗。
 */
@Composable
internal fun PluginDetailScreen(
    pluginId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloadToken by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<PluginDetailState?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var trying by remember { mutableStateOf(false) }
    // 试跑结果（2026-11-08 改：以前只弹一个 300 字的 Toast，长输出被砍、几秒就没了、还不能复制）。
    var tryResult by remember { mutableStateOf<PluginTryResult?>(null) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

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
                Text(
                    text = identityNote(context, manifest),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
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
                PluginNotice(
                    text = stringResource(
                        R.string.plugin_missing_grant,
                        detail.missing.sortedBy { it.wire }.joinToString("、") { it.wire },
                    ),
                    color = MiuixTheme.colorScheme.error,
                )
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

        // 2026-11-08 补「试跑一次」：导入后到被模型调用之间，用户没法自证插件可用。
        // 不新增页面，就在详情页放一行；只对**无参数**的插件显示（有参数得先填值，
        // 那要一个输入弹窗，这次先不做，避免界面变复杂）。
        if (manifest.params.none { it.required }) {
            item(key = "try") {
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.plugin_try_title),
                        summary = stringResource(
                            if (detail.missing.isEmpty()) {
                                R.string.plugin_try_summary
                            } else {
                                R.string.plugin_try_summary_blocked
                            },
                        ),
                        enabled = detail.missing.isEmpty() && !trying,
                        onClick = {
                            if (trying) return@EtaArrowPreference
                            trying = true
                            tryResult = null
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    // 试跑是用户点的，走交互式入口，confirm:first 的插件才会弹卡而不是被当成无人值守拒绝。
                                    PluginInvoker.invokeFromUi(context, manifest.id)
                                }
                                trying = false
                                // 耗时用执行器回报的「纯执行时间」，不在这里掐表 ——
                                // 这里掐会把等确认卡的时间也算进去（实测 12.9s vs 真实 111ms）。
                                tryResult = when (outcome) {
                                    is PluginInvoker.Outcome.Ok -> PluginTryResult(
                                        ok = true,
                                        millis = outcome.millis,
                                        output = outcome.output.ifBlank { "（没有输出）" },
                                    )

                                    is PluginInvoker.Outcome.Failure -> PluginTryResult(
                                        ok = false,
                                        millis = outcome.millis,
                                        output = outcome.message.ifBlank { "执行失败，没拿到原因" },
                                    )
                                }
                                reloadToken += 1
                            }
                        },
                    )
                }
            }

            // 试跑结果（2026-11-08）：不再用 Toast，直接铺在试跑行下面。
            // 状态行给图标+文字（不只靠颜色），输出块等宽、可选中、限高滚动、可展开。
            if (trying || tryResult != null) {
                item(key = "try-result") {
                    PluginTryResultCard(
                        running = trying,
                        result = tryResult,
                        onDismiss = { tryResult = null },
                    )
                }
            }
        }

        item(key = "calls") {
            EtaPreferenceGroupTitle(stringResource(R.string.plugin_recent_calls))
            EtaPreferenceGroup {
                if (detail.calls.isEmpty()) {
                    EtaArrowPreference(
                        title = stringResource(R.string.plugin_no_calls),
                        onClick = null,
                    )
                } else {
                    detail.calls.forEachIndexed { index, call ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        EtaArrowPreference(
                            title = timeFormat.format(Date(call.at)),
                            summary = callSummary(context, call),
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
                // 2026-11-08 修：deleteRecursively 以前直接跑在 Composable 回调（主线程）上，
                // 插件目录里文件多时会卡界面。挪到 IO，删完再返回。
                val target = manifest
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (target != null && target.directory.isNotBlank()) {
                                File(target.directory).deleteRecursively()
                            }
                            PluginRepository.forget(context, pluginId)
                        }
                    }
                    onBack()
                }
            },
        )
    }
}

@Composable
private fun PluginNotice(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    )
}

private fun callSummary(context: Context, call: PluginCallRecord): String = buildString {
    append(
        context.getString(
            if (call.ok) R.string.plugin_call_ok else R.string.plugin_call_failed,
        ),
    )
    append(" · ").append(context.getString(R.string.plugin_call_duration, formatSeconds(call.millis)))
    if (call.note.isNotBlank()) append(" · ").append(call.note)
}

private fun formatSeconds(millis: Long): String = when {
    millis < 1_000 -> "${millis}ms"
    else -> String.format(Locale.US, "%.1fs", millis / 1000.0)
}

private data class PluginDetailState(
    val manifest: PluginManifest?,
    val reason: String,
    val enabled: Boolean,
    val granted: Set<PluginPermission>,
    val missing: Set<PluginPermission>,
    val calls: List<PluginCallRecord>,
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
                calls = PluginRepository.recentCalls(context, entry.manifest.id, 5),
            )
        }

        is PluginEntry.Broken -> PluginDetailState(null, entry.reason, false, emptySet(), emptySet(), emptyList())
        null -> PluginDetailState(null, "", false, emptySet(), emptySet(), emptyList())
    }
}

private fun kindLabel(context: Context, manifest: PluginManifest): String = when {
    manifest.kind == PluginKind.HTTP -> context.getString(R.string.plugins_kind_http)
    manifest.exec?.environment == "linux" ->
        context.getString(R.string.plugins_kind_shell) + " · " + context.getString(R.string.plugins_env_linux)

    else ->
        context.getString(R.string.plugins_kind_shell) + " · " + context.getString(R.string.plugins_env_android)
}

/** 执行身份说明：让用户清楚这条命令到底以什么身份跑（2026-11-08 加）。 */
private fun identityNote(context: Context, manifest: PluginManifest): String = when {
    manifest.kind == PluginKind.HTTP -> context.getString(R.string.plugin_identity_http)
    manifest.exec?.environment == "linux" -> context.getString(R.string.plugin_identity_linux)
    PluginPermission.SHELL_ROOT in manifest.permissions -> context.getString(R.string.plugin_identity_root)
    else -> context.getString(R.string.plugin_identity_app)
}

private fun permissionTitle(permission: PluginPermission): Int = when (permission) {
    PluginPermission.SHELL_ANDROID -> R.string.plugin_permission_shell_android
    PluginPermission.SHELL_LINUX -> R.string.plugin_permission_shell_linux
    PluginPermission.SHELL_ROOT -> R.string.plugin_permission_shell_root
    PluginPermission.NETWORK_LOCALHOST -> R.string.plugin_permission_network_localhost
    PluginPermission.NETWORK_ANY -> R.string.plugin_permission_network_any
}

private fun permissionSummary(permission: PluginPermission): Int = when (permission) {
    PluginPermission.SHELL_ANDROID -> R.string.plugin_permission_shell_android_summary
    PluginPermission.SHELL_LINUX -> R.string.plugin_permission_shell_linux_summary
    PluginPermission.SHELL_ROOT -> R.string.plugin_permission_shell_root_summary
    PluginPermission.NETWORK_LOCALHOST -> R.string.plugin_permission_network_localhost_summary
    PluginPermission.NETWORK_ANY -> R.string.plugin_permission_network_any_summary
}

/** 一次试跑的结果；UI 只关心成没成、花了多久、输出是什么。 */
private data class PluginTryResult(
    val ok: Boolean,
    val millis: Long,
    val output: String,
)

/**
 * 试跑结果卡片（2026-11-08）。
 *
 * 为什么不用 Toast：命令输出经常几十行，Toast 只能放 300 字、几秒就消失、还没法复制 ——
 * 用户想对照报错改插件时根本抓不住。
 *
 * 排版参照项目里已有的两处成熟做法（危险确认卡片的命令块、AgentToolActivity 的工具输出）：
 * 等宽字体 + 圆角 + 浅底 + 限高内部滚动 + 可选中。
 * 状态行同时给图标与文字，不靠颜色单独表意（色盲可用）。
 */
@Composable
private fun PluginTryResultCard(
    running: Boolean,
    result: PluginTryResult?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    val statusColor = when {
        running -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        result?.ok == true -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.error
    }
    val statusText = when {
        running -> stringResource(R.string.plugin_try_running)
        result?.ok == true -> stringResource(R.string.plugin_try_ok, result.millis)
        else -> stringResource(R.string.plugin_try_failed, result?.millis ?: 0L)
    }
    val statusIcon = when {
        running -> Icons.Rounded.Schedule
        result?.ok == true -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.ErrorOutline
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EtaPreferenceDefaults.SidePadding)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = statusText,
                style = MiuixTheme.textStyles.footnote2,
                color = statusColor,
                modifier = Modifier.weight(1f),
            )
            if (!running) {
                EtaTextButton(
                    text = stringResource(R.string.plugin_try_dismiss),
                    onClick = onDismiss,
                )
            }
        }

        if (running) {
            // 用 miuix 的环形指示器：项目里统一是这个（没有 material3，也没有横向进度条）。
            // 放在状态行右侧而不是新起一行，避免结果块高度跳动。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    progress = null,
                    size = 20.dp,
                    strokeWidth = 2.dp,
                )
            }
        } else if (result != null) {
            val lines = result.output.trimEnd().lines()
            val shown = if (expanded) lines else lines.take(PLUGIN_TRY_PREVIEW_LINES)
            SelectionContainer {
                Text(
                    text = shown.joinToString("\n"),
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                    color = MiuixTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = PLUGIN_TRY_BLOCK_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtaTextButton(
                    text = stringResource(R.string.plugin_try_copy),
                    onClick = { clipboard.setText(AnnotatedString(result.output)) },
                )
                if (lines.size > PLUGIN_TRY_PREVIEW_LINES) {
                    EtaTextButton(
                        text = stringResource(
                            if (expanded) R.string.plugin_try_collapse else R.string.plugin_try_expand_all,
                        ),
                        onClick = { expanded = !expanded },
                    )
                }
            }
        }
    }
}

/** 默认显示多少行；超过就给「展开全部」。 */
private const val PLUGIN_TRY_PREVIEW_LINES = 20

/** 输出块最大高度；超出内部滚动，不把整页撑长。 */
private val PLUGIN_TRY_BLOCK_MAX_HEIGHT = 220.dp

package io.github.mangi.eta.ui.screens.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 内置变更记录（随 APK 打包的 assets，不是工作区文件）。 */
private const val CHANGELOG_ASSET = "eta_changelog.md"

/**
 * 本机档案清单。路径如实照抄自工作区，用于告诉用户「Agent 能直接读来的东西在哪」；
 * 这里只展示文本，不读取、不校验、不申请权限。
 */
private val LOCAL_ARCHIVE_PATHS = listOf(
    LocalArchivePath(R.string.eta_build_notes_path_workspace, "/data/local/tmp/eta/00-速查-START-HERE.md"),
    LocalArchivePath(R.string.eta_build_notes_path_profile, "/data/local/tmp/eta/05-系统分析-sysanalyze/SYSTEM-PROFILE.md"),
    LocalArchivePath(R.string.eta_build_notes_path_ledger, "/data/local/tmp/eta/05-系统分析-sysanalyze/本机改动台账.md"),
    LocalArchivePath(R.string.eta_build_notes_path_snapshot, "/data/local/tmp/eta/04-工具-tools/sys-snapshot.sh"),
)

/**
 * 「Eta 改造说明」：装的是哪一版、这一版改了什么、本机档案在哪。
 *
 * 有意做成纯只读页面：不读工作区文件、不起 shell、不申请任何权限，内容只有版本信息 +
 * 打包进 APK 的 assets 文本 + 静态路径清单，因此自签改造版不会因为这一页多出执行面。
 */
@Composable
internal fun EtaBuildNotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // assets 读取放到 IO 线程；失败只降级成一行提示文本，页面其它部分照常显示。
    val changelog by produceState<String?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(CHANGELOG_ASSET).use { it.readBytes().decodeToString() }
            }.getOrNull()
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_about_build_notes_title),
        onBack = onBack,
    ) {
        item(key = "build_notes_version") {
            EtaCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
            ) {
                // 版本号可长按复制：反馈问题时不用手抄。
                SelectionContainer {
                    Text(
                        text = stringResource(
                            R.string.eta_build_notes_version_line,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        item(key = "build_notes_changes_title") {
            EtaPreferenceGroupTitle(text = stringResource(R.string.eta_build_notes_changes_title))
        }
        item(key = "build_notes_changes") {
            EtaCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
            ) {
                val text = changelog
                if (text == null) {
                    Text(
                        text = stringResource(R.string.eta_build_notes_load_failed),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    // 复用会话里的 Markdown 渲染组件，不另写解析。
                    StaticMarkdown(content = text, tone = MarkdownTone.Answer, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        item(key = "build_notes_paths_title") {
            EtaPreferenceGroupTitle(text = stringResource(R.string.eta_build_notes_paths_title))
        }
        item(key = "build_notes_paths") {
            EtaCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
            ) {
                Text(
                    text = stringResource(R.string.eta_build_notes_paths_intro),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LOCAL_ARCHIVE_PATHS.forEach { entry -> LocalArchivePathRow(entry) }
                }
            }
        }
    }
}

/** 一行档案路径：标签 + 等宽路径 + 复制提示，长按可选中复制。 */
@Composable
private fun LocalArchivePathRow(entry: LocalArchivePath) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(entry.labelRes),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.eta_build_notes_copy_hint),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        SelectionContainer {
            Text(
                // 路径原样展示（可长按复制），不做任何截断或省略，避免复制出错误路径。
                text = entry.path,
                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private data class LocalArchivePath(@StringRes val labelRes: Int, val path: String)

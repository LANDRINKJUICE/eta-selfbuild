package io.github.mangi.eta.ui.screens.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.github.GitHubApiClient
import io.github.mangi.eta.agent.github.GitHubCredentialStore
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SidePadding = 16.dp

/**
 * GitHub 账号页：贴一次 Personal Access Token 就通了。
 *
 * 为什么不做 OAuth 登录：Eta 没有公网回调地址，为自改版注册 OAuth App 也不现实；
 * PAT 用户自己生成一次、可随时吊销、无回调无审核，对这个场景更稳。
 */
@Composable
internal fun GitHubAccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 预取文案：在 Composable 作用域取字符串才感知配置变化（LocalContext 的 getString 会被 Lint 拦）。
    val avatarFailedText = stringResource(R.string.accounts_avatar_failed)
    val genericErrorText = stringResource(R.string.github_error_generic)
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(GitHubCredentialStore.cached(context)) }
    var connected by remember { mutableStateOf(GitHubCredentialStore.isConnected(context)) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var avatarPath by remember { mutableStateOf(LocalAvatarStore.path(context)) }
    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val saved = LocalAvatarStore.save(context, uri)
            if (saved != null) {
                avatarPath = saved
                error = null
            } else {
                error = avatarFailedText
            }
        }
    }

    fun submit() {
        if (busy || token.isBlank()) return
        busy = true
        error = null
        scope.launch {
            val result = GitHubApiClient.verify(token)
            busy = false
            result
                .onSuccess { verified ->
                    GitHubCredentialStore.save(context, token, verified)
                    account = verified
                    connected = true
                    token = ""
                }
                .onFailure { failure ->
                    error = failure.message ?: genericErrorText
                }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.accounts_github),
        onBack = onBack,
    ) {
        item(key = "github-status") {
            val current = account
            EtaPreferenceGroup(
                modifier = Modifier.padding(horizontal = SidePadding).padding(bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GitHubAvatar(
                        login = current?.login,
                        connected = connected,
                        avatarPath = avatarPath,
                        size = 48.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = current?.login?.let { "@$it" }
                                ?: stringResource(R.string.github_not_connected),
                            style = MiuixTheme.textStyles.title4,
                            color = if (connected) {
                                EtaPreferenceColors.Blue
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                        if (connected && current != null) {
                            Text(
                                text = buildString {
                                    append(current.tokenType)
                                    if (current.rateLimit > 0) {
                                        append(" · ")
                                        append(current.rateRemaining)
                                        append('/')
                                        append(current.rateLimit)
                                    }
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        if (!connected) {
            item(key = "github-token-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.github_token_title))
            }
            item(key = "github-token-input") {
                Column(modifier = Modifier.padding(horizontal = SidePadding)) {
                    TextField(
                        value = token,
                        onValueChange = { token = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.github_token_hint),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EtaTextButton(
                            text = if (busy) {
                                stringResource(R.string.github_verifying)
                            } else {
                                stringResource(R.string.github_connect)
                            },
                            enabled = !busy && token.isNotBlank(),
                            onClick = { submit() },
                        )
                        if (busy) {
                            Spacer(Modifier.width(10.dp))
                            InfiniteProgressIndicator(size = 18.dp)
                        }
                    }
                    error?.let { message ->
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // 头像：用户可以自己挑一张本地图片，完全离线；不选就用首字母/图标兜底。
        item(key = "github-avatar") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = SidePadding).padding(bottom = 16.dp)) {
                EtaPreference(
                    title = stringResource(R.string.accounts_avatar_change),
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.Rounded.Image,
                            tint = EtaPreferenceColors.Orange,
                        )
                    },
                    onClick = {
                        avatarPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                if (avatarPath != null) {
                    EtaPreferenceDivider()
                    EtaPreference(
                        title = stringResource(R.string.accounts_avatar_reset),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.RestartAlt,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        },
                        onClick = {
                            LocalAvatarStore.clear(context)
                            avatarPath = null
                        },
                    )
                }
            }
        }

        if (connected) {
            item(key = "github-disconnect") {
                EtaPreferenceGroup(modifier = Modifier.padding(horizontal = SidePadding)) {
                    EtaPreference(
                        title = stringResource(R.string.github_disconnect),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.LinkOff,
                                tint = MiuixTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            GitHubCredentialStore.clear(context)
                            account = null
                            connected = false
                        },
                    )
                }
            }
        }

        item(key = "github-note") {
            Text(
                text = stringResource(R.string.github_note),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = SidePadding, vertical = 16.dp),
            )
        }
    }
}

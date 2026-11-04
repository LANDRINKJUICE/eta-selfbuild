package io.github.mangi.eta.ui.screens.accounts

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 账号头像，三档回落：
 *
 * 1. 用户自己选的本地图片（[avatarPath]，离线可用）
 * 2. 没有图片但已连接 → 登录名首字母（蓝底白字）
 * 3. 都没连 → 灰色占位图标
 *
 * 刻意不加载 GitHub 的 `avatar_url`：那要联网 + 图片库 + 缓存，
 * 而「我的头像」交给用户自己挑更直观，也不受网络影响。
 */
@Composable
internal fun GitHubAvatar(
    login: String?,
    connected: Boolean,
    avatarPath: String? = null,
    size: Dp = 44.dp,
) {
    val shape = CircleShape
    val bitmap = rememberLocalAvatar(avatarPath)
    val ringColor = if (connected) EtaPreferenceColors.Blue else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
    }

    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape)
                .border(2.dp, ringColor, shape),
        )

        connected && !login.isNullOrBlank() -> Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(EtaPreferenceColors.Blue)
                .border(2.dp, EtaPreferenceColors.Blue, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = login.take(1).uppercase(),
                color = Color.White,
                style = MiuixTheme.textStyles.title4.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.42f).sp,
                ),
            )
        }

        else -> Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .border(2.dp, ringColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountCircle,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/** 从本地文件读头像；路径变化才重新解码，避免每次重组都读盘。 */
@Composable
private fun rememberLocalAvatar(path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return bitmap
}

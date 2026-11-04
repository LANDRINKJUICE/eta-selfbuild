package io.github.mangi.eta.ui.screens.accounts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 本地头像：用户自己选一张图，复制进应用私有目录后长期使用。
 *
 * 为什么不加载网络头像：那要联网、要缓存、要图片加载库，而这个头像是「我的标识」，
 * 用户自己挑一张更符合直觉，也完全离线。
 *
 * 存的是**重新编码过的缩略图**（最长边 [TARGET_PX]），不是原图：
 * 手机相册里的图动辄 4000px，直接存下来既占空间、每次解码又慢。
 *
 * [name] 让同一个仓库服务多个用途（GitHub 账号、通知风格角色…），互不覆盖。
 */
internal object LocalAvatarStore {
    private const val DIR_NAME = "avatars"
    private const val TARGET_PX = 256

    /** GitHub 账号头像的默认文件名，保持向后兼容。 */
    const val GITHUB_NAME = "github_avatar"

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    fun file(context: Context, name: String = GITHUB_NAME): File =
        File(dir(context), "$name.png")

    /** 当前头像路径；没有就返回 null（界面回落到首字母/图标）。 */
    fun path(context: Context, name: String = GITHUB_NAME): String? {
        val target = file(context, name)
        return if (target.isFile && target.length() > 0) target.absolutePath else null
    }

    /**
     * 从系统选择器给的 URI 复制进来。
     *
     * @return 成功返回新路径；图片读不出来返回 null（调用方给提示，不崩）。
     */
    fun save(context: Context, uri: Uri, name: String = GITHUB_NAME): String? {
        return runCatching {
            val appContext = context.applicationContext
            val bitmap = appContext.contentResolver.openInputStream(uri)?.use { input ->
                // 先只读尺寸，算出采样率，避免把 4000px 的原图整张读进内存。
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(
                    appContext.contentResolver.openInputStream(uri),
                    null,
                    bounds,
                )
                val sample = calculateSample(bounds.outWidth, bounds.outHeight, TARGET_PX)
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null

            val scaled = scaleDown(bitmap, TARGET_PX)
            val target = file(appContext, name)
            target.outputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            target.absolutePath
        }.getOrNull()
    }

    fun clear(context: Context, name: String = GITHUB_NAME) {
        runCatching { file(context, name).delete() }
    }

    /** 采样率取 2 的幂，直到缩到目标尺寸以下。 */
    private fun calculateSample(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= target) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(source: Bitmap, target: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= target) return source
        val ratio = target.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}

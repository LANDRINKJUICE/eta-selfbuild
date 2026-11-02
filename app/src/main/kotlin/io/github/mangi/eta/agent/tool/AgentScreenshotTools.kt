package io.github.mangi.eta.agent.tool

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.view.Display
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** 把当前屏幕截图保存为图片文件：默认写入系统相册 Pictures/Eta，也支持调用方指定的绝对路径。 */
internal class AgentScreenshotTools(
    private val context: Context,
    private val deviceController: RootShellDeviceController,
    private val activeDisplayId: () -> Int = { Display.DEFAULT_DISPLAY },
) {
    fun saveScreenshot(args: JSONObject): AgentModelClient.ToolResult {
        val png = deviceController.captureScreenshotPng(activeDisplayId())
            ?: return sensitive(
                error("SCREENSHOT_UNAVAILABLE", "当前无法截取屏幕，请确认 Root 授权与无障碍服务状态"),
            )
        val displayName = resolveDisplayName(args)
        val requestedPath = args.optString("path").trim()
        return if (requestedPath.isEmpty()) {
            saveToGallery(png, displayName)
        } else {
            saveToPath(png, requestedPath, displayName)
        }
    }

    private fun saveToPath(png: ByteArray, path: String, displayName: String): AgentModelClient.ToolResult {
        if (!path.startsWith("/") || path.contains('\u0000')) {
            return sensitive(error("SCREENSHOT_PATH_DENIED", "截图保存路径必须是绝对路径且不含空字符"))
        }
        val file = File(path)
        val saved = runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { output -> output.write(png) }
            true
        }.getOrDefault(false)
        if (!saved) {
            runCatching { file.delete() }
            return sensitive(error("SCREENSHOT_SAVE_FAILED", "无法写入指定路径"))
        }
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/png"), null)
        }
        return sensitive(
            JSONObject()
                .put("ok", true)
                .put("tool", "save_screenshot")
                .put("saved_to", path)
                .put("uri", "file://$path")
                .put("display_name", file.name.ifEmpty { displayName })
                .put("size_bytes", png.size)
                .also { result -> pixelSize(png)?.let { result.put("pixel_size", it) } }
                .toString(),
        )
    }

    private fun saveToGallery(png: ByteArray, displayName: String): AgentModelClient.ToolResult {
        val fileName = "$displayName.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Eta")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return sensitive(error("SCREENSHOT_SAVE_FAILED", "无法在系统相册中创建截图"))
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output -> output.write(png) } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return sensitive(error("SCREENSHOT_SAVE_FAILED", "无法写入系统相册"))
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        val published = runCatching {
            context.contentResolver.update(uri, values, null, null) > 0
        }.getOrDefault(false)
        if (!published) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return sensitive(error("SCREENSHOT_SAVE_FAILED", "无法完成系统相册中的截图"))
        }
        return sensitive(
            JSONObject()
                .put("ok", true)
                .put("tool", "save_screenshot")
                .put("saved_to", uri.toString())
                .put("display_name", fileName)
                .put("size_bytes", png.size)
                .also { result -> pixelSize(png)?.let { result.put("pixel_size", it) } }
                .toString(),
        )
    }

    /** 相册文件名：默认按时间命名，调用方给出 name 时过滤路径分隔符与跳转片段并截断。 */
    private fun resolveDisplayName(args: JSONObject): String {
        val requested = args.optString("name").trim()
        if (requested.isEmpty()) return defaultDisplayName()
        var sanitized = requested.filterNot { it == '/' || it == '\\' || it == '\u0000' }
        while (sanitized.contains("..")) {
            sanitized = sanitized.replace("..", "")
        }
        return sanitized.trim().take(64).ifEmpty { defaultDisplayName() }
    }

    private fun defaultDisplayName(): String =
        "Eta-截图-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** 只读 PNG 头部尺寸，避免为拿宽高而整张解码。 */
    private fun pixelSize(png: ByteArray): String? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) "${options.outWidth}x${options.outHeight}" else null
    }.getOrNull()

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content = content, sensitive = true)
}

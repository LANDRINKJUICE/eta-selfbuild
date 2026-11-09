package io.github.mangi.eta.agent.mcp

import android.util.Base64
import android.view.Display
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P0：Eta 进程内最小 MCP Server（streamable HTTP, JSON-RPC 2.0）。
 *
 * 让 OpenCode（chroot 内）把 Eta 的 Android 能力当成 MCP 工具来调用：
 *   OpenCode  --HTTP/MCP-->  127.0.0.1:PORT  --dispatch-->  Eta 工具层
 *
 * 为什么放 Eta 进程内：
 *   - 工具（无障碍 / 系统 API / 通知 / 日历 / 截图）天然在 App 权限域里，权限单源；
 *   - 避免 python shim 那种跨进程/跨环境的脆弱边界（之前 eta-mcp-shim.py 就是挂在这）。
 *
 * P0 先暴露只读工具（phone_status / screenshot / ui_tree），写类/敏感类留到后续阶梯。
 * 传输层有意保持极简（raw ServerSocket），不引入新依赖，离线可编译；后续可替换为 Ktor/NanoHTTPD。
 */
class EtaMcpServer(
    private val port: Int = DEFAULT_PORT,
    private val token: String = DEFAULT_TOKEN,
) {
    companion object {
        const val DEFAULT_PORT = 17891
        const val DEFAULT_TOKEN = "eta-dev-token" // TODO(P2): Keystore 随机 token + env 注入
        @Volatile var instance: EtaMcpServer? = null
            private set
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    // RootShellDeviceController 懒加载：避免 App 启动时即起 shell 进程监护。
    private val deviceController: RootShellDeviceController by lazy {
        RootShellDeviceController(AndroidAgentLogger)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        instance = this
        scope.launch {
            try {
                val ss = ServerSocket(port).apply { reuseAddress = true }
                serverSocket = ss
                while (running.get()) {
                    val client = try { ss.accept() } catch (_: Exception) {
                        if (running.get()) null else break
                    } ?: continue
                    scope.launch { handleClient(client) }
                }
            } catch (_: Exception) {
                // P0: 静默；后续接入 DiagnosticLogFiles
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        job.cancel()
        instance = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val req = readRequest(input) ?: run { socket.close(); return }
            val auth = req.headers["authorization"] ?: req.headers["Authorization"]
            if (auth != "Bearer $token") {
                writeResponse(output, 401, """{"error":"unauthorized"}""")
                return
            }
            if (req.method != "POST") {
                writeResponse(output, 405, """{"error":"method not allowed"}""")
                return
            }
            val rpc = try { JSONObject(req.body) } catch (_: Exception) {
                writeResponse(output, 400, """{"error":"bad json"}"""); return
            }
            val method = rpc.optString("method")
            val id = rpc.opt("id")
            val params = rpc.optJSONObject("params")
            val (result, isError, errorMsg) = dispatch(method, params)
            val resp = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                if (isError) put("error", JSONObject().put("code", -32000).put("message", errorMsg))
                else put("result", result)
            }
            writeResponse(output, 200, resp.toString())
        } catch (_: Exception) {
            // ignore per-connection errors
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private data class HttpRequest(val method: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(input: java.io.InputStream): HttpRequest? {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val headers = mutableMapOf<String, String>()
        var line: String?
        while (true) {
            line = reader.readLine()
            if (line == null || line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyChars = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val r = reader.read(bodyChars, read, contentLength - read)
            if (r < 0) break
            read += r
        }
        return HttpRequest(method, headers, String(bodyChars, 0, read))
    }

    private fun writeResponse(output: java.io.OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 405 -> "Method Not Allowed"; else -> "Error" }
        val head = "HTTP/1.1 $code $statusText\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun dispatch(method: String, params: JSONObject?): Triple<JSONObject?, Boolean, String> {
        return when (method) {
            "initialize" -> {
                val r = JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", "eta-mcp").put("version", "0.2"))
                Triple(r, false, "")
            }
            "notifications/initialized", "ping" -> Triple(JSONObject(), false, "")
            "tools/list" -> Triple(toolsList(), false, "")
            "tools/call" -> {
                val name = params?.optString("name") ?: ""
                val args = params?.optJSONObject("arguments") ?: JSONObject()
                callTool(name, args)
            }
            else -> Triple(null, true, "method not found: $method")
        }
    }

    private fun toolsList(): JSONObject {
        val tools = JSONArray()
        tools.put(
            toolDef(
                "eta_phone_status",
                "Battery level, temperature, memory and load average of the phone",
                JSONObject(),
            ),
        )
        tools.put(
            toolDef(
                "eta_screenshot",
                "Capture the current screen as a PNG image (with text summary). Requires Root or Accessibility screenshot capability.",
                JSONObject().put(
                    "properties",
                    JSONObject().put("displayId", JSONObject().put("type", "integer")),
                ),
            ),
        )
        tools.put(
            toolDef(
                "eta_ui_tree",
                "Structured text of the current screen's UI nodes (text/desc/clickable/bounds). Requires Accessibility service.",
                JSONObject().put(
                    "properties",
                    JSONObject()
                        .put("maxNodes", JSONObject().put("type", "integer"))
                        .put("displayId", JSONObject().put("type", "integer")),
                ),
            ),
        )
        return JSONObject().put("tools", tools)
    }

    private fun toolDef(name: String, desc: String, schema: JSONObject): JSONObject {
        if (!schema.has("type")) schema.put("type", "object")
        if (!schema.has("properties")) schema.put("properties", JSONObject())
        return JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)
    }

    private fun success(text: String, imageBase64: String? = null): Triple<JSONObject?, Boolean, String> {
        val content = JSONArray()
        if (imageBase64 != null) {
            content.put(JSONObject().put("type", "image").put("data", imageBase64).put("mimeType", "image/png"))
        }
        content.put(JSONObject().put("type", "text").put("text", text))
        return Triple(JSONObject().put("content", content).put("isError", false), false, "")
    }

    private fun failure(message: String): Triple<JSONObject?, Boolean, String> =
        Triple(null, true, message)

    private fun callTool(name: String, args: JSONObject): Triple<JSONObject?, Boolean, String> {
        return when (name) {
            "eta_phone_status" -> success(buildPhoneStatus())
            "eta_screenshot" -> {
                val displayId = args.optInt("displayId", Display.DEFAULT_DISPLAY)
                val png = deviceController.captureScreenshotPng(displayId)
                    ?: return failure("SCREENSHOT_UNAVAILABLE: 需要 Root 授权或无障碍截图能力")
                val b64 = Base64.encodeToString(png, Base64.NO_WRAP)
                success("screenshot captured, ${png.size} bytes, base64 length ${b64.length}", b64)
            }
            "eta_ui_tree" -> {
                val maxNodes = args.optInt("maxNodes", 120).coerceIn(1, 400)
                val displayId = args.optInt("displayId", Display.DEFAULT_DISPLAY)
                val service = AgentAccessibilityService.current()
                    ?: return failure("ACCESSIBILITY_UNAVAILABLE: 无障碍服务未连接")
                val pkg = service.currentPackageName(displayId)
                val nodes = service.queryNodes(maxNodes, displayId)
                val lines = nodes.mapTo(mutableListOf()) { n ->
                    val b = n.bounds
                    val text = n.text.trim().replace('\n', ' ').take(80)
                    val desc = n.desc.trim().replace('\n', ' ').take(80)
                    "#${n.index} [${n.packageName}] ${n.className.substringAfterLast('.')} " +
                        "bounds=(${b.left},${b.top},${b.right},${b.bottom}) " +
                        "clickable=${n.clickable} editable=${n.editable}" +
                        (if (text.isNotBlank()) " text=\"$text\"" else "") +
                        (if (desc.isNotBlank()) " desc=\"$desc\"" else "")
                }
                success("foreground=${pkg ?: "unknown"} nodes=${lines.size}\n" + lines.joinToString("\n"))
            }
            else -> failure("unknown tool: $name")
        }
    }

    private fun buildPhoneStatus(): String {
        fun read(path: String): String = try { File(path).readText().trim() } catch (_: Exception) { "n/a" }
        val cap = read("/sys/class/power_supply/battery/capacity")
        val tempRaw = read("/sys/class/power_supply/battery/temp")
        val tempC = tempRaw.toIntOrNull()?.let { it / 10.0 } ?: tempRaw
        val memAvail = read("/proc/meminfo").lineSequence()
            .firstOrNull { it.startsWith("MemAvailable") }?.substringAfter(":")?.trim() ?: "n/a"
        val load = read("/proc/loadavg").split(" ").take(3).joinToString(" ")
        return "battery=$cap% temp=${tempC}°C mem_available=$memAvail loadavg=$load"
    }
}

package com.dsh.phoneact.mcp

import com.dsh.phoneact.core.Lg
import com.dsh.phoneact.core.Prefs
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 传输层。同时支持两种传输：
 *   • Streamable HTTP（2025-06-18）：POST /mcp 收 JSON-RPC，直接返回 application/json
 *   • HTTP+SSE（2024-11-05 旧版）：GET /sse 建立流，POST /messages?sessionId=… 发送
 * 另外提供 GET /health、GET /、GET /screen.png 等便于人工排查的端点。
 */
object McpServer {

    @Volatile private var http: Http? = null
    @Volatile var running: Boolean = false
        private set
    @Volatile var port: Int = 8517
        private set
    @Volatile var bindAll: Boolean = true
        private set
    @Volatile var lastError: String = ""
        private set
    val requestCount = AtomicLong(0)
    @Volatile var lastRequestAt: Long = 0

    private val sessions = ConcurrentHashMap<String, SseStreamResponse>()

    fun start(port: Int, bindAll: Boolean): Boolean {
        stop()
        return try {
            val h = Http(if (bindAll) null else "127.0.0.1", port)
            h.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            http = h
            this.port = port
            this.bindAll = bindAll
            running = true
            lastError = ""
            Lg.i("MCP 服务已启动: ${if (bindAll) "0.0.0.0" else "127.0.0.1"}:$port/mcp")
            true
        } catch (t: Throwable) {
            running = false
            lastError = "启动失败: ${t.message}"
            Lg.e("MCP 启动失败", t)
            false
        }
    }

    fun stop() {
        sessions.values.forEach { runCatching { it.closeStream() } }
        sessions.clear()
        runCatching { http?.stop() }
        http = null
        running = false
    }

    fun localAddresses(): List<String> {
        val out = ArrayList<String>()
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                nif.inetAddresses.toList().forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        out.add(addr.hostAddress ?: "")
                    }
                }
            }
        }
        return out.filter { it.isNotBlank() }
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("running", running)
        put("port", port)
        put("bindAll", bindAll)
        put("url", if (running) "http://${if (bindAll) (localAddresses().firstOrNull() ?: "127.0.0.1") else "127.0.0.1"}:$port/mcp" else "")
        put("sseClients", sessions.size)
        put("requests", requestCount.get())
        put("lastRequestAt", lastRequestAt)
        put("requireTokenForRemote", Prefs.current.mcpAuthToken.isNotEmpty())
        if (lastError.isNotEmpty()) put("lastError", lastError)
    }

    /** 向所有 SSE 客户端推送一条 JSON-RPC 通知。 */
    fun broadcast(method: String, params: JSONObject) {
        if (sessions.isEmpty()) return
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()
        sessions.values.forEach { runCatching { it.push("message", payload) } }
    }

    // ==================================================================

    private fun authorized(session: NanoHTTPD.IHTTPSession): Boolean {
        val token = Prefs.current.mcpAuthToken
        if (token.isEmpty()) return true
        val remote = session.remoteIpAddress ?: ""
        if (remote == "127.0.0.1" || remote == "::1" || remote == "0:0:0:0:0:0:0:1") return true
        val auth = session.headers["authorization"] ?: session.headers["Authorization"] ?: ""
        if (auth.startsWith("Bearer ")) return auth.removePrefix("Bearer ").trim() == token
        val q = session.parameters["token"]?.firstOrNull()
        return q == token
    }

    private class Http(host: String?, p: Int) : NanoHTTPD(host, p) {

        override fun serve(session: IHTTPSession): Response {
            requestCount.incrementAndGet()
            McpServer.lastRequestAt = System.currentTimeMillis()
            val uri = session.uri.trimEnd('/').ifEmpty { "/" }
            return try {
                when {
                    session.method == Method.OPTIONS -> cors(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
                    uri == "/health" -> cors(json(Response.Status.OK, healthJson().toString()))
                    uri == "/" -> cors(html(indexHtml()))
                    uri == "/mcp" -> handleMcp(session)
                    uri == "/sse" -> handleSse(session)
                    uri == "/messages" -> handleMessages(session)
                    uri == "/screen.png" -> screenPng()
                    uri == "/screen.json" -> cors(json(Response.Status.OK,
                        com.dsh.phoneact.core.FrameHub.model.value?.toJson()?.toString() ?: "{\"error\":\"尚无识别结果\"}"))
                    else -> cors(json(Response.Status.NOT_FOUND, JSONObject().put("error", "not found: $uri").toString()))
                }
            } catch (t: Throwable) {
                Lg.e("MCP 请求处理异常 $uri", t)
                cors(json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", t.message ?: "internal").toString()))
            }
        }

        private fun handleMcp(session: IHTTPSession): Response {
            if (!authorized(session)) return cors(json(Response.Status.UNAUTHORIZED,
                JSONObject().put("error", "unauthorized: 需要 Authorization: Bearer <token>").toString()))
            if (session.method == Method.GET) return handleSse(session)
            if (session.method != Method.POST) return cors(json(Response.Status.METHOD_NOT_ALLOWED,
                JSONObject().put("error", "仅支持 POST /mcp").toString()))
            val body = readBody(session) ?: return cors(json(Response.Status.BAD_REQUEST,
                JSONObject().put("error", "无法读取请求体").toString()))
            val reply = McpProtocol.handleBody(body)
            if (reply.body == null) return cors(NanoHTTPD.newFixedLengthResponse(Response.Status.ACCEPTED, "text/plain", ""))
            return cors(json(Response.Status.OK, reply.body))
        }

        private fun handleSse(session: IHTTPSession): Response {
            if (!authorized(session)) return cors(json(Response.Status.UNAUTHORIZED,
                JSONObject().put("error", "unauthorized").toString()))
            val id = UUID.randomUUID().toString()
            val sse = SseStreamResponse(": phoneact mcp sse ready\n\n")
            sse.push("endpoint", "/messages?sessionId=$id")
            sse.setOnClose { McpServer.sessions.remove(id) }
            McpServer.sessions[id] = sse
            Lg.i("SSE 客户端接入 " + id + "，当前 " + McpServer.sessions.size + " 个")
            return sse
        }

        private fun handleMessages(session: IHTTPSession): Response {
            if (!authorized(session)) return cors(json(Response.Status.UNAUTHORIZED,
                JSONObject().put("error", "unauthorized").toString()))
            if (session.method != Method.POST) return cors(json(Response.Status.METHOD_NOT_ALLOWED,
                JSONObject().put("error", "仅支持 POST /messages").toString()))
            val id = session.parameters["sessionId"]?.firstOrNull()
            val sse = id?.let { McpServer.sessions[it] }
            val body = readBody(session) ?: return cors(json(Response.Status.BAD_REQUEST,
                JSONObject().put("error", "无法读取请求体").toString()))
            val reply = McpProtocol.handleBody(body)
            if (reply.body != null) {
                if (sse != null) sse.push("message", reply.body) else Lg.w("SSE 会话不存在: $id")
            }
            return cors(NanoHTTPD.newFixedLengthResponse(Response.Status.ACCEPTED, "text/plain", ""))
        }

        private fun screenPng(): Response {
            val cap = com.dsh.phoneact.core.Capture.capture()
                ?: return cors(json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "截屏失败").toString()))
            val bos = java.io.ByteArrayOutputStream()
            cap.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
            return cors(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/png",
                java.io.ByteArrayInputStream(bos.toByteArray()), bos.size().toLong()))
        }

        /**
         * NanoHTTPD 的 ContentType 在未声明 charset 时默认 US-ASCII，会破坏 UTF-8 请求体
         * （中文工具参数会变成乱码）。这里直接按 Content-Length 读原始字节并按 UTF-8 解码。
         */
        private fun readBody(session: IHTTPSession): String? {
            val len = session.headers["content-length"]?.trim()?.toIntOrNull() ?: -1
            if (len in 1..(8 * 1024 * 1024)) {
                return try {
                    val buf = ByteArray(len)
                    val ins = session.inputStream
                    var off = 0
                    while (off < len) {
                        val n = ins.read(buf, off, len - off)
                        if (n < 0) break
                        off += n
                    }
                    String(buf, 0, off, Charsets.UTF_8)
                } catch (e: IOException) {
                    Lg.e("读取请求体失败", e)
                    null
                }
            }
            return try {
                val map = HashMap<String, String>()
                session.parseBody(map)
                map["postData"]
            } catch (e: IOException) {
                Lg.e("读取请求体失败", e)
                null
            } catch (e: ResponseException) {
                Lg.e("读取请求体失败", e)
                null
            }
        }

        private fun json(status: Response.Status, body: String): Response =
            NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body)

        private fun html(body: String): Response =
            NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

        private fun cors(r: Response): Response {
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Mcp-Session-Id, Accept")
            r.addHeader("Access-Control-Expose-Headers", "Mcp-Session-Id")
            return r
        }

        private fun healthJson(): JSONObject = JSONObject().apply {
            put("ok", true)
            put("server", McpProtocol.serverInfo)
            put("protocolVersion", McpProtocol.PROTOCOL_VERSION)
            put("tools", McpTools.list().length())
            put("status", statusJson())
            put("frameHub", com.dsh.phoneact.core.FrameHub.statusJson())
        }

        private fun indexHtml(): String = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PhoneAct MCP</title>
            <style>
              body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:#0b1220;color:#e6edf7;margin:0;padding:24px}
              h1{font-size:20px;margin:0 0 4px} .m{color:#93a4bd;font-size:13px;margin-bottom:18px}
              .card{background:#111c33;border:1px solid #1e2c48;border-radius:16px;padding:16px;margin-bottom:12px}
              code{background:#0b1220;padding:2px 6px;border-radius:6px;color:#7dd3fc}
              a{color:#7dd3fc} table{border-collapse:collapse;width:100%} td{padding:4px 8px;border-bottom:1px solid #1e2c48;font-size:13px}
              .k{color:#93a4bd;width:190px}
            </style></head><body>
            <h1>PhoneAct MCP 服务</h1><div class="m">Android 屏幕分块识别 + 坐标操作 (MCP ${McpProtocol.PROTOCOL_VERSION})</div>
            <div class="card"><b>端点</b><table>
              <tr><td class="k">Streamable HTTP</td><td><code>POST /mcp</code></td></tr>
              <tr><td class="k">SSE (旧版)</td><td><code>GET /sse</code> + <code>POST /messages</code></td></tr>
              <tr><td class="k">截图</td><td><code>GET /screen.png</code></td></tr>
              <tr><td class="k">识别结果</td><td><code>GET /screen.json</code></td></tr>
              <tr><td class="k">健康检查</td><td><code>GET /health</code></td></tr>
            </table></div>
            <div class="card"><b>客户端配置示例</b><pre><code>{
  "mcpServers": {
    "phoneact": { "url": "http://127.0.0.1:$port/mcp" }
  }
}</code></pre></div>
            </body></html>
        """.trimIndent()
    }
}
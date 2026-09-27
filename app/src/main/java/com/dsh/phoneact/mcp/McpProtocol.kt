package com.dsh.phoneact.mcp

import com.dsh.phoneact.core.FrameHub
import com.dsh.phoneact.core.Lg
import com.dsh.phoneact.core.Prefs
import org.json.JSONArray
import org.json.JSONObject

data class RpcReply(val status: Int, val body: String?, val contentType: String = "application/json")

/**
 * MCP (Model Context Protocol) JSON-RPC 2.0 语义层。
 * 兼容 2025-06-18 / 2024-11-05 两个协议版本的 tools 能力。
 */
object McpProtocol {

    const val PROTOCOL_VERSION = "2025-06-18"
    private val SUPPORTED = setOf("2025-06-18", "2025-03-26", "2024-11-05")

    private const val PARSE_ERROR = -32700
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601
    private const val INVALID_PARAMS = -32602
    private const val INTERNAL_ERROR = -32603

    val serverInfo: JSONObject = JSONObject()
        .put("name", "phoneact")
        .put("title", "PhoneAct Android 屏幕识别与操作")
        .put("version", "1.0.0")

    fun handleBody(body: String): RpcReply {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return err(null, PARSE_ERROR, "空请求体")
        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val out = JSONArray()
                for (i in 0 until arr.length()) {
                    val r = handleOne(arr.optJSONObject(i) ?: continue)
                    if (r != null) out.put(r)
                }
                if (out.length() == 0) RpcReply(202, null) else RpcReply(200, out.toString())
            } else {
                val obj = JSONObject(trimmed)
                val r = handleOne(obj) ?: return RpcReply(202, null)
                RpcReply(200, r.toString())
            }
        } catch (t: Throwable) {
            Lg.e("MCP 解析失败", t)
            err(null, PARSE_ERROR, "JSON 解析失败: ${t.message}")
        }
    }

    /** 返回 null 表示这是 notification（无需响应）。 */
    private fun handleOne(req: JSONObject): JSONObject? {
        val id = if (req.has("id")) req.get("id") else null
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()
        if (method.isEmpty()) return errorObj(id, INVALID_REQUEST, "缺少 method")

        // notification：无 id
        val isNotification = !req.has("id")
        if (isNotification) {
            when (method) {
                "notifications/initialized", "notifications/cancelled", "notifications/roots/list_changed" -> return null
                else -> return null
            }
        }

        return when (method) {
            "initialize" -> resultObj(id, JSONObject().apply {
                put("protocolVersion", negotiate(params.optString("protocolVersion", PROTOCOL_VERSION)))
                put("capabilities", JSONObject().apply {
                    put("tools", JSONObject().put("listChanged", false))
                    put("resources", JSONObject().apply {
                        put("subscribe", false)
                        put("listChanged", false)
                    })
                    put("logging", JSONObject())
                })
                put("serverInfo", serverInfo)
                put("instructions", INSTRUCTIONS)
            })

            "ping" -> resultObj(id, JSONObject())

            "tools/list" -> resultObj(id, JSONObject().put("tools", McpTools.list()))

            "tools/call" -> {
                val name = params.optString("name", "")
                if (name.isEmpty()) return errorObj(id, INVALID_PARAMS, "缺少工具名 name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val t0 = System.currentTimeMillis()
                val r = McpTools.call(name, args)
                Lg.i("MCP tools/call $name -> ${if (r.isError) "错误" else "成功"} (${System.currentTimeMillis() - t0}ms)")
                resultObj(id, JSONObject().apply {
                    put("content", JSONArray().apply {
                        r.contents.forEach { c ->
                            put(JSONObject().apply {
                                put("type", c.type)
                                if (c.text != null) put("text", c.text)
                                if (c.data != null) put("data", c.data)
                                if (c.mimeType != null) put("mimeType", c.mimeType)
                            })
                        }
                    })
                    put("isError", r.isError)
                })
            }

            "resources/list" -> resultObj(id, JSONObject().put("resources", resources()))

            "resources/read" -> {
                val uri = params.optString("uri", "")
                resultObj(id, JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("uri", uri)
                        put("mimeType", "application/json")
                        put("text", readResource(uri))
                    }))
                })
            }

            "prompts/list" -> resultObj(id, JSONObject().put("prompts", JSONArray()))

            "completion/complete" -> resultObj(id, JSONObject().put("completion", JSONObject()
                .put("values", JSONArray()).put("total", 0).put("hasMore", false)))

            "logging/setLevel" -> resultObj(id, JSONObject())

            "shutdown" -> resultObj(id, JSONObject())

            else -> errorObj(id, METHOD_NOT_FOUND, "不支持的方法: $method")
        }
    }

    private fun negotiate(client: String): String =
        if (SUPPORTED.contains(client)) client else PROTOCOL_VERSION

    private fun resources(): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("uri", "phoneact://status")
            put("name", "设备自动化状态")
            put("description", "Root/无障碍/Xposed/MCP/识别循环的实时状态")
            put("mimeType", "application/json")
        })
        put(JSONObject().apply {
            put("uri", "phoneact://screen/model")
            put("name", "最近一次屏幕识别结果")
            put("description", "分块 + 元素坐标 + 颜色不一致度")
            put("mimeType", "application/json")
        })
        put(JSONObject().apply {
            put("uri", "phoneact://settings")
            put("name", "识别与操作参数")
            put("mimeType", "application/json")
        })
    }

    private fun readResource(uri: String): String = when (uri) {
        "phoneact://status" -> JSONObject()
            .put("frameHub", FrameHub.statusJson())
            .put("mcp", McpServer.statusJson())
            .toString()
        "phoneact://screen/model" -> FrameHub.model.value?.toJson()?.toString() ?: "{\"error\":\"尚无识别结果\"}"
        "phoneact://settings" -> Prefs.current.toJson().toString()
        else -> "{\"error\":\"未知资源 $uri\"}"
    }

    private fun resultObj(id: Any?, result: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun errorObj(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun err(id: Any?, code: Int, message: String): RpcReply =
        RpcReply(200, errorObj(id, code, message).toString())

    private val INSTRUCTIONS = """
        你正通过 MCP 控制一台真实的 Android 手机（PhoneAct）。
        推荐工作流：
        1. 先调用 device_status 确认 root / 无障碍 / 截屏通道是否可用。
        2. 用 screen_recognize 获取当前屏幕的分块识别结果：每个元素都带有 text、bounds、center([x,y])、colorDelta(颜色与背景的不一致度)。
        3. 点击优先用 ui_tap_text(text=...)，它会先走无障碍节点、再回退 OCR 坐标；需要精确坐标时用 ui_tap(x,y)。
        4. 操作后若界面会刷新，用 screen_wait_update 等待，而不是盲目 sleep。
        5. 应用级操作使用 app_launch / app_stop / app_current；需要底层能力时用 shell_exec(root)。
        元素来源说明：ocr=视觉识别到的文字(判定依据：颜色与背景不一致且含文字)，accessibility=无障碍节点，color=颜色异常但无文字的组件(通常是图标/按钮)。
    """.trimIndent()
}

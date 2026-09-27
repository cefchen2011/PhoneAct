package com.dsh.phoneact.mcp

import android.graphics.Bitmap
import android.util.Base64
import com.dsh.phoneact.core.Actions
import com.dsh.phoneact.core.AppOps
import com.dsh.phoneact.core.Capture
import com.dsh.phoneact.core.Clipboard
import com.dsh.phoneact.core.ElementSource
import com.dsh.phoneact.core.FrameHub
import com.dsh.phoneact.core.Lg
import com.dsh.phoneact.core.Prefs
import com.dsh.phoneact.core.Recognizer
import com.dsh.phoneact.core.RootShell
import com.dsh.phoneact.core.ScreenModel
import com.dsh.phoneact.core.UiTree
import com.dsh.phoneact.core.XiaoAi
import com.dsh.phoneact.service.ActAccessibilityService
import com.dsh.phoneact.service.CaptureService
import com.dsh.phoneact.xposed.XposedStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class ToolContent(val type: String, val text: String? = null, val data: String? = null, val mimeType: String? = null)

data class ToolResult(val contents: List<ToolContent>, val isError: Boolean = false) {
    companion object {
        fun text(s: String) = ToolResult(listOf(ToolContent("text", text = s)))
        fun json(o: Any) = ToolResult(listOf(ToolContent("text", text = o.toString())))
        fun error(s: String) = ToolResult(listOf(ToolContent("text", text = s)), isError = true)
        fun image(bmp: Bitmap, caption: String? = null): ToolResult {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            val list = ArrayList<ToolContent>()
            if (caption != null) list.add(ToolContent("text", text = caption))
            list.add(ToolContent("image", data = b64, mimeType = "image/png"))
            return ToolResult(list)
        }
    }
}

/** 对外的全部 MCP 工具。 */
object McpTools {

    // ==================================================================
    // 工具清单
    // ==================================================================

    fun list(): JSONArray {
        val arr = JSONArray()
        fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) {
            arr.put(JSONObject().apply {
                put("name", name)
                put("description", desc)
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", props)
                    put("required", JSONArray(required))
                    put("additionalProperties", false)
                })
            })
        }
        fun p(type: String, desc: String, def: Any? = null): JSONObject = JSONObject().apply {
            put("type", type); put("description", desc)
            if (def != null) put("default", def)
        }

        tool("device_status", "查询设备自动化能力：Root(su)、无障碍服务、Xposed 模块、MCP 服务、截屏通道、超级小爱、当前前台应用。", JSONObject())

        tool("xiaoi_act",
            "【小爱主力通道】用文字把指令发给超级小爱，但把 QueryOrigin 伪造成 VoiceButton，" +
                "让它按『语音指令』处理 —— 从而解锁它只对语音开放的能力（微信/QQ 发消息、打电话等）。" +
                "实测依据：文字输入框的 origin 是 QueryEditBar（会被拒），语音按钮是 VoiceButton（放行）。" +
                "需要 LSPosed 作用域包含 com.miui.voiceassist。",
            JSONObject()
                .put("instruction", p("string", "自然语言指令，例如：给微信联系人sunshine发消息说你好"))
                .put("wait_ms", p("integer", "等待执行/回答的毫秒数", 12000))
                .put("origin", p("string", "auto=走语音路径并篡改识别文本（推荐）；" +
                    "也可指定要伪造的 QueryOrigin 值，如 VoiceButton", "auto")),
            listOf("instruction"))

        tool("xiaoi_tts_set",
            "【语音内容工具】设定要注入小爱麦克风的语音队列 —— 也就是注入(篡改)进去的内容。" +
                "小爱的技能多为多轮追问，按顺序逐轮播报：第 1 轮说指令，页面跳转后的第 2 轮说「确认」。" +
                "设定后用 xiaoi_voice_task {use_prepared:true} 触发。",
            JSONObject()
                .put("texts", JSONObject().put("type", "array")
                    .put("description", "按轮次排列的文本，例如 [\"给微信好友sunshine发消息说你好\", \"确认\"]")
                    .put("items", JSONObject().put("type", "string")))
                .put("rate", p("integer", "语速 -10..10", 1)),
            listOf("texts"))

        tool("xiaoi_tts_queue",
            "查看当前语音队列：每段时长、是否已就绪。",
            JSONObject(), emptyList())

        tool("xiaoi_trace",
            "读取被注入进程实时回传的 hook 轨迹（AudioRecord 注入 / 输入层篡改 / origin 覆盖 / 语音载入）。" +
                "执行类工具默认也会在返回里带上这段轨迹。",
            JSONObject().put("lines", p("integer", "返回行数", 60)), emptyList())

        tool("tts_status",
            "测试 PC 端 TTS 服务是否可用（GET {base}/health），并返回当前配置。" +
                "superxiaoi 需要语音指令，设备自带 TTS 引擎常不可用，用 PC 的 Windows SAPI 最省事。",
            JSONObject(), emptyList())

        tool("tts_self_test",
            "让手机实际去 PC 端 TTS 服务取一段音频并解析，验证整条链路（网络 + WAV 解析）。",
            JSONObject().put("text", p("string", "测试文本", "语音通道测试")), emptyList())

        tool("xiaoi_voice_task",
            "【小爱语音通道】把指令用 TTS 合成成语音，再通过 Xposed 替换小爱进程的麦克风数据喂给它，" +
                "从而绕过『超级小爱只认语音指令』的限制。用于小爱拒绝文字指令的能力，例如微信/QQ 发消息。" +
                "需要：已安装小爱 + LSPosed 作用域包含 com.miui.voiceassist + root。" +
                "失败时用 xiaoi_inject_log 看注入是否生效，再决定是否回退 ui_* 通道。",
            JSONObject()
                .put("instruction", p("string", "要说的那句话，例如：给微信联系人sunshine发消息说你好"))
                .put("wait_ms", p("integer", "注入后等待小爱执行的毫秒数", 15000))
                .put("wav_base64", p("string", "可选：外部合成好的 16bit PCM WAV（base64）。一般不用，留空走 TTS"))
                .put("use_prepared", p("boolean", "true=使用 xiaoi_tts_set 备好的队列，不再合成", false))
                .put("step_ms", p("integer", "每一步之间的固定等待毫秒（默认 5000）。" +
                    "指令播完等 5s；补点麦克风（重发/确认）后同样等 5s", 5000))
                .put("max_retry", p("integer", "未跳转时最多补点几次麦克风", 3))
                .put("follow_ups", JSONObject()
                    .put("type", "array")
                    .put("description", "后续轮次要说的内容。小爱的技能会多轮追问，默认追加「确认」")
                    .put("items", JSONObject().put("type", "string"))),
            listOf("instruction"))

        tool("xiaoi_inject_log",
            "读取语音注入的诊断日志（LSPosed 模块日志），确认 AudioRecord hook 是否被小爱调用、语音是否载入。",
            JSONObject().put("lines", p("integer", "返回行数", 40)), emptyList())

        tool("xiaoi_status",
            "检测『超级小爱』是否可用：是否安装、版本、是否为默认助理、能否唤起。",
            JSONObject())

        tool("xiaoi_task",
            "把任务用一句自然语言交给『超级小爱』执行，并返回它的回答。" +
                "【优先使用】查天气/汇率/百科、给某人发消息、设闹钟提醒、打开某个 App 的某个功能、" +
                "点外卖/打车/买票、控制智能家居、翻译、总结屏幕 —— 这类有语义或需要云端能力的任务，" +
                "超级小爱有系统级权限，比逐像素点坐标更快更稳。" +
                "需要精确坐标、批量循环、读取具体控件、或小爱答非所问时，再改用 ui_* 工具。",
            JSONObject()
                .put("instruction", p("string", "自然语言指令，例如：给微信 sunshine 发消息说下午开会"))
                .put("send", p("boolean", "true=自动发送并等待回答；false=只填入输入框等人工确认", true))
                .put("wait_ms", p("integer", "发送后等待回答的毫秒数", 8000)),
            listOf("instruction"))

        tool("screen_recognize",
            "对当前屏幕做一次分块识别（状态栏 + 主体 + 导航栏）。识别依据：与背景颜色不一致、且含有文字的组件，输出其屏幕坐标。返回 blocks/elements 结构。",
            JSONObject()
                .put("force", p("boolean", "忽略画面未变化判断，强制重新识别", true))
                .put("region", p("string", "只返回指定分块：all / status_bar / main / nav_bar", "all"))
                .put("with_image", p("boolean", "是否同时返回带标注框的截图", false)),
            emptyList())

        tool("screen_model", "读取最近一次的识别结果（不重新截屏，最低开销）。", JSONObject())

        tool("screen_wait_update",
            "阻塞等待屏幕内容发生下一次更新，返回更新后的识别结果。用于自动化流程中的「等待界面刷新」。",
            JSONObject()
                .put("since_seq", p("integer", "已知的识别序号；留空表示当前序号", 0))
                .put("timeout_ms", p("integer", "超时毫秒", 15000)),
            emptyList())

        tool("screen_screenshot",
            "截屏。format=png 返回图片；format=base64 返回 base64 字符串；format=info 只返回尺寸。",
            JSONObject()
                .put("format", p("string", "png / base64 / info", "png"))
                .put("region", p("string", "all / status_bar / main / nav_bar", "all"))
                .put("annotate", p("boolean", "是否叠加识别标注框", false)),
            emptyList())

        tool("ui_tap", "在指定屏幕坐标点击。优先使用无障碍手势，失败回退 Magisk su + input。",
            JSONObject()
                .put("x", p("integer", "屏幕 X 坐标"))
                .put("y", p("integer", "屏幕 Y 坐标"))
                .put("duration_ms", p("integer", "按住时长（长按请设 600+）", 60)),
            listOf("x", "y"))

        tool("ui_tap_text",
            "按文本查找并点击：先无障碍节点匹配，再用识别结果（OCR 文本）按坐标点击。",
            JSONObject()
                .put("text", p("string", "要点击的文本"))
                .put("exact", p("boolean", "是否精确匹配", false))
                .put("index", p("integer", "同文本第几个（从 0 开始）", 0)),
            listOf("text"))

        tool("ui_long_press", "长按屏幕坐标。",
            JSONObject().put("x", p("integer", "X")).put("y", p("integer", "Y"))
                .put("duration_ms", p("integer", "时长", 700)),
            listOf("x", "y"))

        tool("ui_swipe", "滑动。",
            JSONObject()
                .put("x1", p("integer", "起点 X")).put("y1", p("integer", "起点 Y"))
                .put("x2", p("integer", "终点 X")).put("y2", p("integer", "终点 Y"))
                .put("duration_ms", p("integer", "时长", 300)),
            listOf("x1", "y1", "x2", "y2"))

        tool("ui_scroll", "按方向滚动屏幕（自动换算为从屏幕中部滑动的坐标）。",
            JSONObject()
                .put("direction", p("string", "up / down / left / right"))
                .put("distance", p("integer", "滚动距离像素", 600))
                .put("duration_ms", p("integer", "时长", 300)),
            listOf("direction"))

        tool("ui_drag_path", "按自定义路径拖动（解锁图案、连续手势）。points 为 [[x,y],...]。",
            JSONObject().put("points", p("array", "坐标点数组")).put("duration_ms", p("integer", "总时长", 500)),
            listOf("points"))

        tool("ui_set_text", "向当前聚焦的输入框写入文本（无障碍 ACTION_SET_TEXT，回退 input text）。",
            JSONObject().put("text", p("string", "文本内容")), listOf("text"))

        tool("ui_key", "发送按键：back/home/menu/enter/del/volume_up/power 等，或直接给 keycode。",
            JSONObject().put("key", p("string", "按键名或数字 keycode")), listOf("key"))

        tool("ui_global", "执行系统全局动作：back / home / recents / notifications / quick_settings / lock_screen。",
            JSONObject().put("action", p("string", "动作名")), listOf("action"))

        tool("ui_tap_frame",
            "点击几何检测出的『框体』—— 没有文字、与背景对比度极低、无障碍树也拿不到的控件" +
                "（典型：微信/QQ 的聊天输入框、搜索框、空按钮）。" +
                "kind 取 input / button / container / any，index 从 0 开始。",
            JSONObject()
                .put("kind", p("string", "input / button / container / any", "input"))
                .put("index", p("integer", "同类框体中第几个（从 0 开始）", 0))
                .put("contains_text", p("string", "可选：只匹配内部包含该文本的框体", "")),
            emptyList())

        tool("ui_clipboard_set",
            "写入系统剪贴板。用于输入非 ASCII 文本：先 set，再对输入框 ui_key(key=\"279\") 触发粘贴" +
                "（因为 root 的 input text 不支持中文；部分应用如微信还会屏蔽无障碍节点树）。",
            JSONObject().put("text", p("string", "要写入剪贴板的文本")), listOf("text"))

        tool("ui_clipboard_get", "读取当前系统剪贴板文本。", JSONObject())

        tool("ui_dump_tree", "导出无障碍节点树（含文本、坐标、可点击性）。depth 控制深度。",
            JSONObject().put("depth", p("integer", "最大深度", 25)), emptyList())

        tool("app_list", "列出已安装应用。",
            JSONObject()
                .put("filter", p("string", "包名或应用名关键字", ""))
                .put("include_system", p("boolean", "是否包含系统应用", false))
                .put("limit", p("integer", "上限", 200)),
            emptyList())

        tool("app_launch", "启动应用（Launcher Intent，失败回退 root monkey）。",
            JSONObject().put("package", p("string", "包名")), listOf("package"))

        tool("app_stop", "强制停止应用（am force-stop，需 root）。",
            JSONObject().put("package", p("string", "包名")), listOf("package"))

        tool("app_current", "获取当前前台应用的包名与 Activity。", JSONObject())

        tool("app_install", "安装 APK（pm install -r，需 root）。",
            JSONObject().put("apk_path", p("string", "设备上的 APK 路径")).put("reinstall", p("boolean", "是否覆盖安装", true)),
            listOf("apk_path"))

        tool("app_uninstall", "卸载应用（需 root）。",
            JSONObject().put("package", p("string", "包名")).put("keep_data", p("boolean", "保留数据", false)),
            listOf("package"))

        tool("shell_exec", "以 root(su) 执行任意 shell 命令并返回输出。",
            JSONObject().put("command", p("string", "命令")).put("timeout_ms", p("integer", "超时", 20000)),
            listOf("command"))

        tool("screen_state", "获取屏幕/电源/分辨率/旋转状态。", JSONObject())

        tool("settings_get", "读取识别与操作参数。", JSONObject())

        tool("settings_set", "修改识别与操作参数（立即生效）。",
            JSONObject()
                .put("color_delta_threshold", p("integer", "颜色不一致阈值 0-255"))
                .put("min_element_area", p("integer", "最小元素面积"))
                .put("analyze_scale", p("integer", "分析降采样倍数 1-8"))
                .put("ocr_enabled", p("boolean", "是否启用 OCR"))
                .put("split_status_bar", p("boolean", "是否分块状态栏"))
                .put("split_nav_bar", p("boolean", "是否分块导航栏"))
                .put("keep_only_text_elements", p("boolean", "只保留含文字的元素"))
                .put("status_bar_height", p("integer", "状态栏高度覆盖，-1 为自动"))
                .put("nav_bar_height", p("integer", "导航栏高度覆盖，-1 为自动"))
                .put("debounce_ms", p("integer", "识别去抖毫秒"))
                .put("tts_server_url", p("string", "PC 端 TTS 服务地址，如 http://192.168.1.80:8518"))
                .put("tts_rate", p("integer", "TTS 语速 -10..10"))
                .put("detect_frames", p("boolean", "是否检测输入框/按钮/卡片等框体"))
                .put("frame_flat_tol", p("integer", "框体分割的亮度容差，越小切得越细"))
                .put("max_frames", p("integer", "每块最多输出多少个框体"))
                .put("recognize_on_change", p("boolean", "屏幕更新即识别"))
                .put("action_backend", p("string", "auto / accessibility / root"))
                .put("capture_backend", p("string", "auto / accessibility / root / media_projection")),
            emptyList())

        tool("logs_tail", "读取最近的运行日志，便于排障。",
            JSONObject().put("lines", p("integer", "行数", 120)), emptyList())

        return arr
    }

    // ==================================================================
    // 分发
    // ==================================================================

    fun call(name: String, args: JSONObject): ToolResult = try {
        when (name) {
            "device_status" -> deviceStatus()
            "xiaoi_status" -> ToolResult.json(XiaoAi.statusJson())
            "xiaoi_tts_set" -> xiaoiTtsSet(args)
            "xiaoi_tts_queue" -> xiaoiTtsQueue()
            "xiaoi_trace" -> ToolResult.text(com.dsh.phoneact.core.Trace.format(
                com.dsh.phoneact.core.Trace.tail(args.optInt("lines", 60))))
            "tts_status" -> ttsStatus()
            "tts_self_test" -> ttsSelfTest(args)
            "xiaoi_act" -> xiaoiAct(args)
            "xiaoi_voice_task" -> xiaoiVoiceTask(args)
            "xiaoi_inject_log" -> ToolResult.text(XiaoAi.injectLog(args.optInt("lines", 40)))
            "xiaoi_task" -> xiaoiTask(args)
            "screen_recognize" -> screenRecognize(args)
            "screen_model" -> screenModelTool()
            "screen_wait_update" -> screenWaitUpdate(args)
            "screen_screenshot" -> screenScreenshot(args)
            "ui_tap" -> op(Actions.tap(args.getInt("x"), args.getInt("y"), args.optLong("duration_ms", 60)))
            "ui_tap_text" -> tapText(args)
            "ui_long_press" -> op(Actions.longPress(args.getInt("x"), args.getInt("y"), args.optLong("duration_ms", 700)))
            "ui_swipe" -> op(Actions.swipe(args.getInt("x1"), args.getInt("y1"), args.getInt("x2"), args.getInt("y2"), args.optLong("duration_ms", 300)))
            "ui_scroll" -> uiScroll(args)
            "ui_drag_path" -> dragPath(args)
            "ui_set_text" -> op(Actions.setText(args.getString("text")))
            "ui_key" -> uiKey(args)
            "ui_global" -> op(Actions.globalAction(args.getString("action")))
            "ui_tap_frame" -> tapFrame(args)
            "ui_clipboard_set" -> clipboardSet(args)
            "ui_clipboard_get" -> clipboardGet()
            "ui_dump_tree" -> uiDumpTree(args)
            "app_list" -> appList(args)
            "app_launch" -> op(AppOps.launch(args.getString("package")))
            "app_stop" -> op(AppOps.forceStop(args.getString("package")))
            "app_current" -> appCurrent()
            "app_install" -> op(AppOps.install(args.getString("apk_path"), args.optBoolean("reinstall", true)))
            "app_uninstall" -> op(AppOps.uninstall(args.getString("package"), args.optBoolean("keep_data", false)))
            "shell_exec" -> shellExec(args)
            "screen_state" -> ToolResult.json(AppOps.screenState())
            "settings_get" -> ToolResult.json(Prefs.current.toJson())
            "settings_set" -> settingsSet(args)
            "logs_tail" -> logsTail(args)
            else -> ToolResult.error("未知工具: $name")
        }
    } catch (t: Throwable) {
        Lg.e("工具 $name 执行异常", t)
        ToolResult.error("工具 $name 执行异常: ${t.message}")
    }

    private fun op(r: com.dsh.phoneact.core.ActionResult): ToolResult =
        if (r.ok) ToolResult.json(JSONObject().put("ok", true).put("backend", r.backend))
        else ToolResult.error("操作失败: ${r.detail}")

    // ==================================================================
    // 实现
    // ==================================================================

    private fun deviceStatus(): ToolResult {
        val o = JSONObject()
        o.put("root", JSONObject()
            .put("available", RootShell.available)
            .put("suPath", RootShell.suPath)
            .put("message", RootShell.lastError))
        o.put("accessibility", JSONObject()
            .put("connected", ActAccessibilityService.isConnected))
        o.put("xposed", XposedStatus.toJson())
        o.put("captureService", JSONObject().put("running", CaptureService.running))
        o.put("mcp", McpServer.statusJson())
        o.put("frameHub", FrameHub.statusJson())
        o.put("ocr", JSONObject().put("available", com.dsh.phoneact.core.Ocr.available).put("error", com.dsh.phoneact.core.Ocr.lastError))
        o.put("xiaoai", XiaoAi.statusJson())
        o.put("xiaoaiVoice", JSONObject().put("hasClip", com.dsh.phoneact.core.XiaoAiVoice.hasClip).put("seq", com.dsh.phoneact.core.XiaoAiVoice.seq))
        o.put("settings", Prefs.current.toJson())
        return ToolResult.json(o)
    }

    private fun screenRecognize(args: JSONObject): ToolResult {
        val force = args.optBoolean("force", true)
        val model = FrameHub.recognizeNow(force) ?: return ToolResult.error("识别失败：无法截屏（无障碍/root/MediaProjection 均不可用）")
        val region = args.optString("region", "all")
        val payload = filterRegion(model, region)
        return if (args.optBoolean("with_image", false)) {
            val bmp = FrameHub.preview.value
            val annotated = bmp?.let { Recognizer.overlay(it, model) }
            if (annotated != null) {
                ToolResult(listOf(
                    ToolContent("text", text = payload.toString()),
                    ToolContent("image", data = pngBase64(annotated), mimeType = "image/png"),
                ))
            } else ToolResult.json(payload)
        } else ToolResult.json(payload)
    }

    private fun screenModelTool(): ToolResult {
        val m = FrameHub.model.value ?: return ToolResult.error("尚无识别结果，请先调用 screen_recognize")
        return ToolResult.json(m.toJson())
    }

    private fun screenWaitUpdate(args: JSONObject): ToolResult {
        val since = if (args.has("since_seq") && args.optLong("since_seq", 0L) > 0L) args.optLong("since_seq")
        else FrameHub.lastSeq()
        val timeout = args.optLong("timeout_ms", 15000)
        val m = kotlinx.coroutines.runBlocking { FrameHub.awaitUpdate(since, timeout) }
            ?: return ToolResult.json(JSONObject().put("updated", false).put("timeout", true).put("since_seq", since))
        return ToolResult.json(JSONObject().put("updated", true).put("model", m.toJson()))
    }

    private fun screenScreenshot(args: JSONObject): ToolResult {
        val cap = Capture.capture() ?: return ToolResult.error("截屏失败（无障碍/root/MediaProjection 均不可用）")
        val region = args.optString("region", "all")
        val model = if (args.optBoolean("annotate", false)) FrameHub.model.value else null
        var bmp: Bitmap = if (model != null) Recognizer.overlay(cap.bitmap, model) else cap.bitmap
        bmp = when (region) {
            "status_bar", "main", "nav_bar" -> cropRegion(bmp, region, model) ?: bmp
            else -> bmp
        }
        return when (args.optString("format", "png")) {
            "info" -> ToolResult.json(JSONObject()
                .put("width", bmp.width).put("height", bmp.height)
                .put("backend", cap.backend).put("ms", cap.ms))
            "base64" -> ToolResult.json(JSONObject()
                .put("format", "png").put("base64", pngBase64(bmp))
                .put("width", bmp.width).put("height", bmp.height))
            else -> ToolResult.image(bmp, "截图 ${bmp.width}x${bmp.height} via ${cap.backend} (${cap.ms}ms)")
        }
    }

    private fun cropRegion(bmp: Bitmap, region: String, model: ScreenModel?): Bitmap? {
        val blocks = Recognizer.splitBlocks(bmp.width, bmp.height, Prefs.current)
        val b = blocks.firstOrNull { it.id == region } ?: return null
        return runCatching {
            Bitmap.createBitmap(bmp, b.left, b.top, b.width, b.height)
        }.getOrNull()
    }

    private fun tapText(args: JSONObject): ToolResult {
        val text = args.getString("text")
        val exact = args.optBoolean("exact", false)
        val index = args.optInt("index", 0)
        // 1) 无障碍节点精确命中
        if (ActAccessibilityService.isConnected) {
            val nodes = UiTree.labeledNodes().filter {
                if (exact) it.label.equals(text, true) else it.label.contains(text, true)
            }
            if (nodes.size > index) {
                val n = nodes[index]
                val r = Actions.tap(n.bounds.centerX, n.bounds.centerY)
                if (r.ok) return ToolResult.json(JSONObject()
                    .put("ok", true).put("via", "accessibility-node")
                    .put("bounds", n.bounds.toJson()).put("text", n.label))
            }
        }
        // 2) 识别结果（OCR）坐标点击
        val model = FrameHub.model.value ?: FrameHub.recognizeNow(true)
        if (model != null) {
            val hits = model.elements.filter {
                it.text.isNotBlank() && (if (exact) it.text.equals(text, true) else it.text.contains(text, true))
            }
            if (hits.size > index) {
                val e = hits[index]
                val r = Actions.tap(e.bounds.centerX, e.bounds.centerY)
                return if (r.ok) ToolResult.json(JSONObject()
                    .put("ok", true).put("via", "ocr-coordinate").put("backend", r.backend)
                    .put("bounds", e.bounds.toJson()).put("text", e.text))
                else ToolResult.error("命中元素但点击失败: ${r.detail}")
            }
        }
        return ToolResult.error("未找到文本「$text」对应的可点击元素")
    }

    private fun uiScroll(args: JSONObject): ToolResult {
        val dir = args.getString("direction").lowercase()
        val dist = args.optInt("distance", 600)
        val dur = args.optLong("duration_ms", 300)
        val m = FrameHub.model.value
        val w = m?.width ?: 1080
        val h = m?.height ?: 2400
        val cx = w / 2
        val cy = h / 2
        return when (dir) {
            "up" -> op(Actions.swipe(cx, cy + dist / 2, cx, cy - dist / 2, dur))
            "down" -> op(Actions.swipe(cx, cy - dist / 2, cx, cy + dist / 2, dur))
            "left" -> op(Actions.swipe(cx + dist / 2, cy, cx - dist / 2, cy, dur))
            "right" -> op(Actions.swipe(cx - dist / 2, cy, cx + dist / 2, cy, dur))
            else -> ToolResult.error("未知方向: $dir")
        }
    }

    private fun dragPath(args: JSONObject): ToolResult {
        val arr = args.optJSONArray("points") ?: return ToolResult.error("points 必须是 [[x,y],...]")
        val pts = ArrayList<Pair<Int, Int>>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            if (p.length() < 2) continue
            pts.add(p.getInt(0) to p.getInt(1))
        }
        if (pts.size < 2) return ToolResult.error("至少需要两个点")
        return op(Actions.path(pts, args.optLong("duration_ms", 500)))
    }

    private fun uiKey(args: JSONObject): ToolResult {
        val k = args.getString("key")
        val code = k.toIntOrNull()
        return if (code != null) op(Actions.keyEvent(code)) else op(Actions.keyName(k))
    }

    private fun xiaoiTask(args: JSONObject): ToolResult {
        val instruction = args.optString("instruction", "")
        if (instruction.isBlank()) return ToolResult.error("缺少 instruction")
        val send = args.optBoolean("send", true)
        val waitMs = args.optLong("wait_ms", 8000)
        val o = XiaoAi.ask(instruction, send, waitMs)
        Lg.i("xiaoi_task 「$instruction」 -> ${o.stage} ${if (o.ok) "成功" else "失败"}")
        return if (o.ok) ToolResult.json(XiaoAi.outcomeJson(o)) else ToolResult.error("${o.stage}: ${o.detail}")
    }

    private fun xiaoiTtsSet(args: JSONObject): ToolResult {
        val arr = args.optJSONArray("texts") ?: return ToolResult.error("缺少 texts 数组")
        if (arr.length() == 0) return ToolResult.error("texts 为空")
        if (args.has("rate")) Prefs.update { it.copy(ttsRate = args.getInt("rate").coerceIn(-10, 10)) }
        com.dsh.phoneact.core.XiaoAiVoice.resetQueue()
        val durations = JSONArray()
        val errors = JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optString(i, "").trim()
            if (t.isEmpty()) { durations.put(0); continue }
            val ok = com.dsh.phoneact.core.XiaoAiVoice.appendFromText(t)
            durations.put(if (ok) com.dsh.phoneact.core.XiaoAiVoice.clipCount.let { _ ->
                com.dsh.phoneact.core.XiaoAiVoice.metaJson().optJSONArray("durations")?.optInt(i, 0) ?: 0
            } else 0)
            if (!ok) errors.put(JSONObject().put("index", i).put("error", com.dsh.phoneact.core.XiaoAiVoice.error))
        }
        return ToolResult.json(JSONObject()
            .put("ok", errors.length() == 0)
            .put("clipCount", com.dsh.phoneact.core.XiaoAiVoice.clipCount)
            .put("durations", durations)
            .put("seq", com.dsh.phoneact.core.XiaoAiVoice.seq)
            .put("errors", errors))
    }

    private fun xiaoiTtsQueue(): ToolResult {
        val meta = com.dsh.phoneact.core.XiaoAiVoice.metaJson()
        meta.put("ready", com.dsh.phoneact.core.XiaoAiVoice.clipCount > 0)
        return ToolResult.json(meta)
    }

    private fun ttsStatus(): ToolResult {
        val url = Prefs.current.ttsServerUrl
        val o = JSONObject().put("configuredUrl", url).put("rate", Prefs.current.ttsRate)
        if (url.isBlank()) {
            o.put("reachable", false).put("hint", "用 settings_set {tts_server_url:\"http://<PC-IP>:8518\"} 配置")
            return ToolResult.json(o)
        }
        return try {
            val conn = (java.net.URL(url.trimEnd('/') + "/health").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 6000; requestMethod = "GET"
            }
            val body = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            conn.disconnect()
            o.put("reachable", true).put("health", body)
            ToolResult.json(o)
        } catch (t: Throwable) {
            o.put("reachable", false).put("error", t.message ?: "连接失败")
            ToolResult.json(o)
        }
    }

    private fun ttsSelfTest(args: JSONObject): ToolResult {
        val text = args.optString("text", "语音通道测试")
        val t0 = System.currentTimeMillis()
        val bytes = com.dsh.phoneact.core.XiaoAiVoice.probeServer(text)
        return ToolResult.json(JSONObject()
            .put("ok", bytes > 0)
            .put("ms", System.currentTimeMillis() - t0)
            .put("pcmBytes", bytes)
            .put("durationMs", if (bytes > 0) bytes / 2 * 1000 / 16000 else 0)
            .put("error", com.dsh.phoneact.core.XiaoAiVoice.error))
    }

    private fun xiaoiAct(args: JSONObject): ToolResult {
        val instruction = args.optString("instruction", "")
        if (instruction.isBlank()) return ToolResult.error("缺少 instruction")
        val waitMs = args.optLong("wait_ms", 12000)
        val origin = args.optString("origin", "VoiceButton").ifBlank { "VoiceButton" }
        val o = if (origin.equals("auto", true)) XiaoAi.askVoiceTamper(instruction, waitMs)
        else XiaoAi.askAsVoice(instruction, waitMs, origin)
        Lg.i("xiaoi_act 「$instruction」 -> ${o.stage} ${if (o.ok) "成功" else "失败"}")
        return if (o.ok) ToolResult.json(XiaoAi.outcomeJson(o)) else ToolResult.error("${o.stage}: ${o.detail}")
    }

    private fun xiaoiVoiceTask(args: JSONObject): ToolResult {
        val instruction = args.optString("instruction", "")
        if (instruction.isBlank()) return ToolResult.error("缺少 instruction")
        val waitMs = args.optLong("wait_ms", 15000)
        val wav = args.optString("wav_base64", "").ifBlank { null }
        val arr = args.optJSONArray("follow_ups")
        val followUps = if (arr != null) (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        else listOf("确认")
        val usePrepared = args.optBoolean("use_prepared", false)
        val stepMs = args.optLong("step_ms", 5000L).coerceIn(1000L, 30000L)
        val maxRetry = args.optInt("max_retry", 3).coerceIn(0, 6)
        val o = XiaoAi.askByVoice(instruction, waitMs, wav, followUps, usePrepared, stepMs, maxRetry)
        Lg.i("xiaoi_voice_task 「$instruction」 -> ${o.stage} ${if (o.ok) "成功" else "失败"}")
        return if (o.ok) ToolResult.json(XiaoAi.outcomeJson(o)) else ToolResult.error("${o.stage}: ${o.detail}")
    }

    private fun tapFrame(args: JSONObject): ToolResult {
        val kind = args.optString("kind", "input").lowercase()
        val index = args.optInt("index", 0)
        val contains = args.optString("contains_text", "")
        val model = FrameHub.model.value ?: FrameHub.recognizeNow(true)
            ?: return ToolResult.error("识别失败：无法截屏")
        var frames = model.elements.filter { it.source == ElementSource.FRAME }
        if (kind != "any") frames = frames.filter { it.kind == kind }
        if (contains.isNotBlank()) {
            val inside = model.elements.filter { it.source != ElementSource.FRAME && it.text.contains(contains, true) }
            frames = frames.filter { f -> inside.any { iouRects(it.bounds, f.bounds) > 0.5 } }
        }
        if (frames.size <= index) {
            return ToolResult.error("没有第 $index 个 kind=$kind 的框体（当前共 ${frames.size} 个" +
                (if (kind == "any") "" else "，全部框体 ${model.elements.count { it.source == ElementSource.FRAME }} 个") + "）")
        }
        val f = frames[index]
        val r = Actions.tap(f.bounds.centerX, f.bounds.centerY)
        return if (r.ok) {
            ToolResult.json(JSONObject()
                .put("ok", true).put("backend", r.backend).put("kind", f.kind)
                .put("bounds", f.bounds.toJson())
                .put("center", JSONArray().put(f.bounds.centerX).put(f.bounds.centerY))
                .put("colorDelta", f.colorDelta))
        } else {
            ToolResult.error("命中框体但点击失败: ${r.detail}")
        }
    }

    private fun iouRects(a: com.dsh.phoneact.core.Rect2, b: com.dsh.phoneact.core.Rect2): Double {
        val l = maxOf(a.left, b.left); val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right); val bo = minOf(a.bottom, b.bottom)
        val inter = maxOf(0, r - l) * maxOf(0, bo - t)
        if (inter <= 0) return 0.0
        val union = a.area + b.area - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    private fun clipboardSet(args: JSONObject): ToolResult {
        val text = args.getString("text")
        return if (Clipboard.setText(text)) ToolResult.json(JSONObject().put("ok", true).put("length", text.length))
        else ToolResult.error("写入剪贴板失败")
    }

    private fun clipboardGet(): ToolResult {
        val t = Clipboard.getText() ?: return ToolResult.json(JSONObject().put("text", JSONObject.NULL))
        return ToolResult.json(JSONObject().put("text", t).put("length", t.length))
    }

    private fun uiDumpTree(args: JSONObject): ToolResult {
        val root = UiTree.capture(args.optInt("depth", 25))
            ?: return ToolResult.error("无障碍服务未连接或无法获取节点树")
        return ToolResult.json(root.toJson())
    }

    private fun appList(args: JSONObject): ToolResult {
        val apps = AppOps.list(
            filter = args.optString("filter", "").ifBlank { null },
            includeSystem = args.optBoolean("include_system", false),
            limit = args.optInt("limit", 200),
        )
        return ToolResult.json(JSONObject().put("count", apps.size).put("apps", AppOps.packagesJson(apps)))
    }

    private fun appCurrent(): ToolResult {
        val (pkg, act) = AppOps.currentForeground()
        return ToolResult.json(JSONObject()
            .put("package", pkg).put("activity", act)
            .put("frameHubPackage", FrameHub.foregroundPackage)
            .put("frameHubActivity", FrameHub.foregroundActivity))
    }

    private fun shellExec(args: JSONObject): ToolResult {
        val cmd = args.getString("command")
        val timeout = args.optLong("timeout_ms", 20000)
        if (!RootShell.available) RootShell.probe()
        val r = RootShell.run(cmd, timeout)
        return ToolResult.json(r.toJson())
    }

    private fun settingsSet(args: JSONObject): ToolResult {
        Prefs.update { s ->
            var n = s
            if (args.has("color_delta_threshold")) n = n.copy(colorDeltaThreshold = args.getInt("color_delta_threshold").coerceIn(1, 255))
            if (args.has("min_element_area")) n = n.copy(minElementArea = args.getInt("min_element_area").coerceIn(1, 100000))
            if (args.has("analyze_scale")) n = n.copy(analyzeScale = args.getInt("analyze_scale").coerceIn(1, 8))
            if (args.has("ocr_enabled")) n = n.copy(ocrEnabled = args.getBoolean("ocr_enabled"))
            if (args.has("split_status_bar")) n = n.copy(splitStatusBar = args.getBoolean("split_status_bar"))
            if (args.has("split_nav_bar")) n = n.copy(splitNavBar = args.getBoolean("split_nav_bar"))
            if (args.has("keep_only_text_elements")) n = n.copy(keepOnlyTextElements = args.getBoolean("keep_only_text_elements"))
            if (args.has("status_bar_height")) n = n.copy(statusBarHeightOverride = args.getInt("status_bar_height"))
            if (args.has("nav_bar_height")) n = n.copy(navBarHeightOverride = args.getInt("nav_bar_height"))
            if (args.has("debounce_ms")) n = n.copy(debounceMs = args.getLong("debounce_ms").coerceIn(50, 10000))
            if (args.has("tts_server_url")) n = n.copy(ttsServerUrl = args.getString("tts_server_url").trim())
            if (args.has("tts_rate")) n = n.copy(ttsRate = args.getInt("tts_rate").coerceIn(-10, 10))
            if (args.has("detect_frames")) n = n.copy(detectFrames = args.getBoolean("detect_frames"))
            if (args.has("frame_flat_tol")) n = n.copy(frameFlatTol = args.getInt("frame_flat_tol").coerceIn(1, 24))
            if (args.has("max_frames")) n = n.copy(maxFrames = args.getInt("max_frames").coerceIn(0, 40))
            if (args.has("recognize_on_change")) n = n.copy(recognizeOnChange = args.getBoolean("recognize_on_change"))
            if (args.has("action_backend")) n = n.copy(actionBackend = runCatching {
                com.dsh.phoneact.core.ActionBackend.valueOf(args.getString("action_backend").uppercase())
            }.getOrDefault(n.actionBackend))
            if (args.has("capture_backend")) n = n.copy(captureBackend = runCatching {
                com.dsh.phoneact.core.CaptureBackend.valueOf(args.getString("capture_backend").uppercase())
            }.getOrDefault(n.captureBackend))
            n
        }
        return ToolResult.json(Prefs.current.toJson())
    }

    private fun logsTail(args: JSONObject): ToolResult {
        val n = args.optInt("lines", 120)
        val all = Lg.dump().lineSequence().toList()
        return ToolResult.text(all.takeLast(n).joinToString("\n"))
    }

    // ==================================================================

    private fun filterRegion(model: ScreenModel, region: String): JSONObject {
        val o = model.toJson()
        if (region == "all") return o
        o.put("blocks", JSONArray().apply {
            model.blocks.filter { it.id == region }.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id); put("name", b.name); put("bounds", b.bounds.toJson())
                    put("elements", JSONArray().apply { b.elements.forEach { put(it.toJson()) } })
                })
            }
        })
        o.put("elements", JSONArray().apply {
            model.blocks.filter { it.id == region }.flatMap { it.elements }.forEach { put(it.toJson()) }
        })
        return o
    }

    private fun pngBase64(bmp: Bitmap): String {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("unused")
    private fun sourceName(s: ElementSource) = s.name.lowercase()
}

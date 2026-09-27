package com.dsh.phoneact.core

import android.content.Context
import com.dsh.phoneact.mcp.McpServer
import com.dsh.phoneact.service.ActAccessibilityService
import com.dsh.phoneact.service.CaptureService
import com.dsh.phoneact.xposed.XposedStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class CoreState(
    val rootAvailable: Boolean = false,
    val rootMessage: String = "检测中…",
    val a11yConnected: Boolean = false,
    val xposedActive: Boolean = false,
    val mcpRunning: Boolean = false,
    val mcpUrl: String = "",
    val pipelineRunning: Boolean = false,
    val frames: Long = 0,
    val seq: Long = 0,
    val elementCount: Int = 0,
    val lastRecognizeMs: Long = 0,
    val ocrAvailable: Boolean = true,
    val foregroundPackage: String = "",
)

/** 应用级总装：把所有子系统串起来，并向 GUI 暴露统一状态。 */
object ActCore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var ctx: Context

    private val _state = MutableStateFlow(CoreState())
    val state: StateFlow<CoreState> = _state.asStateFlow()

    fun init(context: Context) {
        ctx = context.applicationContext
        scope.launch {
            RootShell.probe()
            refresh()
        }
        scope.launch {
            FrameHub.model.collect { m ->
                _state.value = _state.value.copy(
                    seq = m?.seq ?: 0L,
                    elementCount = m?.elements?.size ?: 0,
                    lastRecognizeMs = m?.totalMs ?: 0L,
                    foregroundPackage = m?.packageName.orEmpty(),
                )
            }
        }
        refresh()
        if (Prefs.current.mcpEnabled) {
            scope.launch { startMcp() }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(
            rootAvailable = RootShell.available,
            rootMessage = if (RootShell.available) "已获取 root（${RootShell.suPath}）" else RootShell.lastError.ifBlank { "未授权/不可用" },
            a11yConnected = ActAccessibilityService.isConnected,
            xposedActive = XposedStatus.active,
            mcpRunning = McpServer.running,
            mcpUrl = McpServer.statusJson().optString("url", ""),
            pipelineRunning = FrameHub.running,
            frames = FrameHub.framesSeen,
            ocrAvailable = Ocr.available,
        )
    }

    fun probeRoot() {
        scope.launch {
            RootShell.probe()
            refresh()
        }
    }

    @Synchronized
    fun startMcp(): Boolean {
        val s = Prefs.current
        val ok = McpServer.start(s.mcpPort, s.mcpBindAll)
        refresh()
        return ok
    }

    @Synchronized
    fun stopMcp() {
        McpServer.stop()
        refresh()
    }

    @Synchronized
    fun startPipeline(): Boolean {
        if (!RootShell.available) RootShell.probe()
        if (ActAccessibilityService.inst == null && !RootShell.available) {
            Lg.w("启动识别循环失败：无障碍与 root 均不可用")
            refresh()
            return false
        }
        CaptureService.start(ctx)
        FrameHub.start()
        refresh()
        return true
    }

    @Synchronized
    fun stopPipeline() {
        FrameHub.stop()
        CaptureService.stop(ctx)
        refresh()
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("root", JSONObject().put("available", RootShell.available).put("path", RootShell.suPath).put("message", RootShell.lastError))
        put("accessibility", JSONObject().put("connected", ActAccessibilityService.isConnected))
        put("xposed", XposedStatus.toJson())
        put("mcp", McpServer.statusJson())
        put("frameHub", FrameHub.statusJson())
        put("captureService", JSONObject().put("running", CaptureService.running))
        put("ocr", JSONObject().put("available", Ocr.available).put("error", Ocr.lastError))
        put("settings", Prefs.current.toJson())
        put("lastModel", FrameHub.model.value?.toJson() ?: JSONObject.NULL)
    }

    fun context(): Context = ctx
}

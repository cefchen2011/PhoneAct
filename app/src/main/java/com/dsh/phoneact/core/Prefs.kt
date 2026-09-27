package com.dsh.phoneact.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActionBackend { AUTO, ACCESSIBILITY, ROOT }
enum class CaptureBackend { AUTO, ACCESSIBILITY, ROOT, MEDIA_PROJECTION }

/** 运行时全部可调参数。所有字段都会同步到 SharedPreferences 并由 [flow] 暴露。 */
data class Settings(
    // ---- MCP ----
    val mcpEnabled: Boolean = false,
    val mcpPort: Int = 8517,
    val mcpBindAll: Boolean = true,
    val mcpAuthToken: String = "",

    // ---- 识别 ----
    val recognizeOnChange: Boolean = true,
    val splitStatusBar: Boolean = true,
    val splitNavBar: Boolean = true,
    val statusBarHeightOverride: Int = -1,
    val navBarHeightOverride: Int = -1,
    val colorDeltaThreshold: Int = 42,
    val minElementArea: Int = 120,
    val analyzeScale: Int = 2,
    val ocrEnabled: Boolean = true,
    val keepOnlyTextElements: Boolean = false,
    val debounceMs: Long = 350,
    val maxElements: Int = 120,

    // ---- 操作 ----
    val actionBackend: ActionBackend = ActionBackend.AUTO,
    val defaultTapDelayMs: Long = 120,
    val captureBackend: CaptureBackend = CaptureBackend.AUTO,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("mcpEnabled", mcpEnabled)
        put("mcpPort", mcpPort)
        put("mcpBindAll", mcpBindAll)
        put("recognizeOnChange", recognizeOnChange)
        put("splitStatusBar", splitStatusBar)
        put("splitNavBar", splitNavBar)
        put("statusBarHeightOverride", statusBarHeightOverride)
        put("navBarHeightOverride", navBarHeightOverride)
        put("colorDeltaThreshold", colorDeltaThreshold)
        put("minElementArea", minElementArea)
        put("analyzeScale", analyzeScale)
        put("ocrEnabled", ocrEnabled)
        put("keepOnlyTextElements", keepOnlyTextElements)
        put("debounceMs", debounceMs)
        put("maxElements", maxElements)
        put("actionBackend", actionBackend.name)
        put("captureBackend", captureBackend.name)
    }
}

object Prefs {
    private const val NAME = "phoneact"
    private lateinit var sp: SharedPreferences
    private val _flow = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> = _flow.asStateFlow()

    val current: Settings get() = _flow.value

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        _flow.value = read()
        if (_flow.value.mcpAuthToken.isEmpty()) {
            val token = newToken()
            sp.edit().putString("mcpAuthToken", token).apply()
            _flow.value = _flow.value.copy(mcpAuthToken = token)
        }
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            mcpEnabled = sp.getBoolean("mcpEnabled", d.mcpEnabled),
            mcpPort = sp.getInt("mcpPort", d.mcpPort),
            mcpBindAll = sp.getBoolean("mcpBindAll", d.mcpBindAll),
            mcpAuthToken = sp.getString("mcpAuthToken", "") ?: "",
            recognizeOnChange = sp.getBoolean("recognizeOnChange", d.recognizeOnChange),
            splitStatusBar = sp.getBoolean("splitStatusBar", d.splitStatusBar),
            splitNavBar = sp.getBoolean("splitNavBar", d.splitNavBar),
            statusBarHeightOverride = sp.getInt("statusBarHeightOverride", d.statusBarHeightOverride),
            navBarHeightOverride = sp.getInt("navBarHeightOverride", d.navBarHeightOverride),
            colorDeltaThreshold = sp.getInt("colorDeltaThreshold", d.colorDeltaThreshold),
            minElementArea = sp.getInt("minElementArea", d.minElementArea),
            analyzeScale = sp.getInt("analyzeScale", d.analyzeScale),
            ocrEnabled = sp.getBoolean("ocrEnabled", d.ocrEnabled),
            keepOnlyTextElements = sp.getBoolean("keepOnlyTextElements", d.keepOnlyTextElements),
            debounceMs = sp.getLong("debounceMs", d.debounceMs),
            maxElements = sp.getInt("maxElements", d.maxElements),
            actionBackend = runCatching { ActionBackend.valueOf(sp.getString("actionBackend", "AUTO")!!) }.getOrDefault(ActionBackend.AUTO),
            captureBackend = runCatching { CaptureBackend.valueOf(sp.getString("captureBackend", "AUTO")!!) }.getOrDefault(CaptureBackend.AUTO),
        )
    }

    /** 生成新的 MCP 访问令牌（旧令牌立即失效）。 */
    fun regenerateToken(): String {
        val token = newToken()
        update { it.copy(mcpAuthToken = token) }
        Lg.i("MCP 访问令牌已重置")
        return token
    }

    private fun newToken(): String = (1..24).map { "0123456789abcdef".random() }.joinToString("")

    fun update(block: (Settings) -> Settings) {
        val next = block(_flow.value)
        _flow.value = next
        sp.edit().apply {
            putBoolean("mcpEnabled", next.mcpEnabled)
            putInt("mcpPort", next.mcpPort)
            putBoolean("mcpBindAll", next.mcpBindAll)
            putString("mcpAuthToken", next.mcpAuthToken)
            putBoolean("recognizeOnChange", next.recognizeOnChange)
            putBoolean("splitStatusBar", next.splitStatusBar)
            putBoolean("splitNavBar", next.splitNavBar)
            putInt("statusBarHeightOverride", next.statusBarHeightOverride)
            putInt("navBarHeightOverride", next.navBarHeightOverride)
            putInt("colorDeltaThreshold", next.colorDeltaThreshold)
            putInt("minElementArea", next.minElementArea)
            putInt("analyzeScale", next.analyzeScale)
            putBoolean("ocrEnabled", next.ocrEnabled)
            putBoolean("keepOnlyTextElements", next.keepOnlyTextElements)
            putLong("debounceMs", next.debounceMs)
            putInt("maxElements", next.maxElements)
            putString("actionBackend", next.actionBackend.name)
            putString("captureBackend", next.captureBackend.name)
        }.apply()
    }
}

package com.dsh.phoneact.core

import org.json.JSONArray
import org.json.JSONObject

data class Rect2(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Int get() = width * height
    fun toJson(): JSONObject = JSONObject()
        .put("left", left).put("top", top).put("right", right).put("bottom", bottom)
        .put("centerX", centerX).put("centerY", centerY)
        .put("width", width).put("height", height)

    companion object {
        fun fromJson(o: JSONObject) = Rect2(o.optInt("left"), o.optInt("top"), o.optInt("right"), o.optInt("bottom"))
    }
}

/** 元素来源：无障碍节点 / OCR 文字 / 纯颜色块。 */
enum class ElementSource { ACCESSIBILITY, OCR, COLOR }

data class ScreenElement(
    val id: String,
    val text: String,
    val bounds: Rect2,
    val source: ElementSource,
    val blockId: String,
    val confidence: Float = 1f,
    /** 前景主色 (ARGB) */
    val fgColor: Int = 0,
    /** 局部背景主色 (ARGB) */
    val bgColor: Int = 0,
    /** 颜色不一致度 0..255，越大越“不一致” */
    val colorDelta: Int = 0,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val className: String = "",
    /** 元素相对屏幕中心的方向，方便模型理解布局: left/center/right + top/middle/bottom */
    val position: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("source", source.name.lowercase())
        put("block", blockId)
        put("bounds", bounds.toJson())
        put("center", JSONArray().put(bounds.centerX).put(bounds.centerY))
        put("confidence", confidence.toDouble())
        put("fgColor", hex(fgColor))
        put("bgColor", hex(bgColor))
        put("colorDelta", colorDelta)
        put("clickable", clickable)
        put("editable", editable)
        if (className.isNotEmpty()) put("className", className)
        if (position.isNotEmpty()) put("position", position)
    }

    private fun hex(c: Int) = String.format("#%08X", c)
}

data class ScreenBlock(
    val id: String,
    val name: String,
    val bounds: Rect2,
    val elements: List<ScreenElement>,
)

data class ScreenModel(
    val ts: Long,
    val width: Int,
    val height: Int,
    val frameHash: Long,
    val changed: Boolean,
    val seq: Long,
    val packageName: String,
    val activity: String,
    val blocks: List<ScreenBlock>,
    val ocrMs: Long,
    val colorMs: Long,
    val totalMs: Long,
    val captureBackend: String,
    val note: String = "",
) {
    val elements: List<ScreenElement> get() = blocks.flatMap { it.elements }

    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", ts)
        put("seq", seq)
        put("changed", changed)
        put("screen", JSONObject().put("width", width).put("height", height))
        put("frameHash", frameHash.toString())
        put("packageName", packageName)
        put("activity", activity)
        put("captureBackend", captureBackend)
        put("timing", JSONObject().put("ocrMs", ocrMs).put("colorMs", colorMs).put("totalMs", totalMs))
        if (note.isNotEmpty()) put("note", note)
        put("blocks", JSONArray().apply {
            blocks.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id)
                    put("name", b.name)
                    put("bounds", b.bounds.toJson())
                    put("elements", JSONArray().apply { b.elements.forEach { put(it.toJson()) } })
                })
            }
        })
        put("elements", JSONArray().apply { elements.forEach { put(it.toJson()) } })
    }
}

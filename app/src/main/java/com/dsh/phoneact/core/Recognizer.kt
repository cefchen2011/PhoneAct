package com.dsh.phoneact.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.dsh.phoneact.PhoneActApp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 视觉识别引擎。
 *
 * 流水线：
 *   1. 分块            —— 状态栏 / 主体 / 导航栏（可配置）
 *   2. 颜色不一致检测  —— 量化直方图求局部背景色，逐像素求色差，连通域得到"与背景颜色不一致"的组件
 *   3. 文字检测        —— 对每块做端侧 OCR，得到带包围盒的文本行
 *   4. 判定            —— 同时满足「颜色不一致」且「含文字」的组件被认定为可操作元素，输出屏幕坐标
 *   5. 无障碍互校      —— 用无障碍节点树补全/校正文本与可点击性（IoU 合并）
 */
object Recognizer {

    data class ScreenContext(val packageName: String, val activity: String)

    private data class Region(
        val rect: Rect2,
        val fg: Int,
        val bg: Int,
        val delta: Int,
        val masked: Int,
    )

    data class BlockDef(val id: String, val name: String, val left: Int, val top: Int, val width: Int, val height: Int) {
        val rect: Rect2 get() = Rect2(left, top, left + width, top + height)
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    fun recognize(
        bmp: Bitmap,
        ctxInfo: ScreenContext,
        settings: Settings,
        frameHash: Long,
        changed: Boolean,
        seq: Long,
        captureBackend: String,
        note: String = "",
    ): ScreenModel {
        val t0 = System.currentTimeMillis()
        val src = Capture.ensureArgb(bmp)
        val w = src.width
        val h = src.height

        val blocks = splitBlocks(w, h, settings)
        val a11yNodes = if (com.dsh.phoneact.service.ActAccessibilityService.isConnected) {
            runCatching { UiTree.labeledNodes() }.getOrDefault(emptyList())
        } else emptyList()

        val outBlocks = ArrayList<ScreenBlock>(blocks.size)
        var ocrMs = 0L
        var colorMs = 0L
        var globalIndex = 0

        for (def in blocks) {
            val cropIsNew = !(def.left == 0 && def.top == 0 && def.width == w && def.height == h)
            val crop = if (cropIsNew) Bitmap.createBitmap(src, def.left, def.top, def.width, def.height) else src

            // ---- 2. 颜色不一致组件 ----
            val tc0 = System.currentTimeMillis()
            val regions = analyzeColors(crop, def.left, def.top, settings)
            colorMs += System.currentTimeMillis() - tc0

            // ---- 3. 文字 ----
            val to0 = System.currentTimeMillis()
            val ocrLines = if (settings.ocrEnabled && Ocr.available) {
                runCatching { Ocr.recognize(crop) }.getOrDefault(emptyList())
            } else emptyList()
            ocrMs += System.currentTimeMillis() - to0

            val elements = ArrayList<ScreenElement>(regions.size + ocrLines.size)

            // 4a. 有文字的元素（优先，符合"有文字的组件"判定）
            val textBoxes = ArrayList<Rect2>()
            for (line in ocrLines) {
                val box = Rect2(
                    def.left + line.rect.left,
                    def.top + line.rect.top,
                    def.left + line.rect.right,
                    def.top + line.rect.bottom,
                ).clamp(w, h)
                if (box.width < 6 || box.height < 6) continue
                val (fg, bg, delta) = sampleContrast(crop, line.rect, def.left, def.top)
                textBoxes.add(box)
                if (delta < settings.colorDeltaThreshold) continue
                elements.add(
                    ScreenElement(
                        id = "e${++globalIndex}",
                        text = line.text,
                        bounds = box,
                        source = ElementSource.OCR,
                        blockId = def.id,
                        confidence = line.confidence,
                        fgColor = fg,
                        bgColor = bg,
                        colorDelta = delta,
                    )
                )
            }

            // 4b. 无文字但与背景颜色明显不一致的组件（图标/按钮/色块）
            if (!settings.keepOnlyTextElements) {
                for (r in regions) {
                    if (r.rect.area < settings.minElementArea) continue
                    if (r.delta < settings.colorDeltaThreshold) continue
                    if (textBoxes.any { iou(it, r.rect) > 0.35 }) continue
                    elements.add(
                        ScreenElement(
                            id = "e${++globalIndex}",
                            text = "",
                            bounds = r.rect,
                            source = ElementSource.COLOR,
                            blockId = def.id,
                            fgColor = r.fg,
                            bgColor = r.bg,
                            colorDelta = r.delta,
                        )
                    )
                }
            }

            // 5. 无障碍互校
            val a11yHere = a11yNodes.filter { it.bounds.centerY in def.top until (def.top + def.height) }
            for ((i, node) in a11yHere.withIndex()) {
                val nb = node.bounds.clamp(w, h)
                if (nb.width <= 0 || nb.height <= 0) continue
                val hit = elements.indexOfFirst { iou(it.bounds, nb) > 0.3 }
                if (hit >= 0) {
                    val e = elements[hit]
                    elements[hit] = e.copy(
                        text = if (e.text.isBlank()) node.label else e.text,
                        bounds = if (node.label.isNotBlank() || node.clickable) nb else e.bounds,
                        clickable = node.clickable || e.clickable,
                        editable = node.editable || e.editable,
                        className = node.className,
                        source = if (e.source == ElementSource.COLOR && node.label.isNotBlank()) ElementSource.ACCESSIBILITY else e.source,
                        confidence = max(e.confidence, 0.95f),
                    )
                } else if (node.clickable || node.label.isNotBlank()) {
                    val delta = contrastOfRegion(crop, nb, def.left, def.top)
                    elements.add(
                        ScreenElement(
                            id = "e${++globalIndex}",
                            text = node.label,
                            bounds = nb,
                            source = ElementSource.ACCESSIBILITY,
                            blockId = def.id,
                            confidence = 0.95f,
                            fgColor = delta.first,
                            bgColor = delta.second,
                            colorDelta = delta.third,
                            clickable = node.clickable,
                            editable = node.editable,
                            className = node.className,
                        )
                    )
                }
            }

            val sorted = suppress(elements)
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                .take(settings.maxElements)
                .mapIndexed { i, e ->
                    e.copy(id = "e" + (globalIndex + i + 1), position = position(e.bounds, w, h))
                }
            globalIndex += sorted.size

            outBlocks.add(ScreenBlock(def.id, def.name, def.rect, sorted))
            if (cropIsNew) crop.recycle()
        }

        return ScreenModel(
            ts = System.currentTimeMillis(),
            width = w,
            height = h,
            frameHash = frameHash,
            changed = changed,
            seq = seq,
            packageName = ctxInfo.packageName,
            activity = ctxInfo.activity,
            blocks = outBlocks,
            ocrMs = ocrMs,
            colorMs = colorMs,
            totalMs = System.currentTimeMillis() - t0,
            captureBackend = captureBackend,
            note = note,
        )
    }

    // ------------------------------------------------------------------
    // 分块
    // ------------------------------------------------------------------

    fun splitBlocks(w: Int, h: Int, settings: Settings): List<BlockDef> {
        val sb = if (settings.splitStatusBar) statusBarHeight(h) else 0
        val nb = if (settings.splitNavBar) navBarHeight(h) else 0
        val out = ArrayList<BlockDef>(3)
        if (sb > 0) out.add(BlockDef("status_bar", "状态栏", 0, 0, w, sb))
        val mainTop = sb
        val mainBottom = (h - nb).coerceAtLeast(mainTop + 1)
        out.add(BlockDef("main", "主体", 0, mainTop, w, mainBottom - mainTop))
        if (nb > 0 && mainBottom < h) out.add(BlockDef("nav_bar", "导航栏", 0, mainBottom, w, h - mainBottom))
        return out
    }

    private fun statusBarHeight(h: Int): Int {
        val o = Prefs.current.statusBarHeightOverride
        if (o > 0) return o.coerceAtMost(h / 3)
        return runCatching {
            val res = PhoneActApp.instance.resources
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) res.getDimensionPixelSize(id) else (h * 0.045f).toInt()
        }.getOrDefault((h * 0.045f).toInt()).coerceIn(0, h / 4)
    }

    private fun navBarHeight(h: Int): Int {
        val o = Prefs.current.navBarHeightOverride
        if (o > 0) return o.coerceAtMost(h / 4)
        return runCatching {
            val res = PhoneActApp.instance.resources
            val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) res.getDimensionPixelSize(id) else (h * 0.035f).toInt()
        }.getOrDefault((h * 0.035f).toInt()).coerceIn(0, h / 5)
    }

    // ------------------------------------------------------------------
    // 颜色不一致检测（替代 OpenCV 的纯 Kotlin 实现：量化直方图 + 连通域）
    // ------------------------------------------------------------------

    private fun analyzeColors(crop: Bitmap, offsetX: Int, offsetY: Int, settings: Settings): List<Region> {
        val scale = settings.analyzeScale.coerceIn(1, 8)
        val sw = max(1, crop.width / scale)
        val sh = max(1, crop.height / scale)
        if (sw < 8 || sh < 8) return emptyList()
        val small = if (sw == crop.width && sh == crop.height) crop else Bitmap.createScaledBitmap(crop, sw, sh, true)
        val n = sw * sh
        val px = IntArray(n)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== crop) small.recycle()

        // 量化到 4bit/通道
        val bins = IntArray(4096)
        for (c in px) bins[quant(c)]++

        // 取占比最高的若干个 bin 作为"背景候选"（容忍渐变/壁纸）
        val peak = bins.maxOrNull() ?: 0
        if (peak <= 0) return emptyList()
        val bgCandidates = ArrayList<Int>(4)
        val order = (0 until 4096).sortedByDescending { bins[it] }
        for (b in order) {
            if (bins[b] <= 0) break
            if (bins[b] < peak / 6) break
            bgCandidates.add(binColor(b))
            if (bgCandidates.size >= 3) break
        }
        val thr = settings.colorDeltaThreshold

        val mask = BooleanArray(n)
        for (i in 0 until n) {
            val c = px[i]
            var best = 255
            for (bg in bgCandidates) {
                val d = colorDistance(c, bg)
                if (d < best) best = d
            }
            mask[i] = best > thr
        }

        // 连通域（4 邻域，迭代式）
        val label = IntArray(n) { -1 }
        val stack = IntArray(n)
        val regions = ArrayList<Region>(64)
        val minMasked = max(3, settings.minElementArea / (scale * scale))

        for (start in 0 until n) {
            if (!mask[start] || label[start] >= 0) continue
            var sp = 0
            stack[sp++] = start
            label[start] = 1
            var minX = sw; var maxX = -1; var minY = sh; var maxY = -1
            var cnt = 0
            var sr = 0L; var sg = 0L; var sb = 0L
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % sw
                val y = p / sw
                cnt++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                val c = px[p]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
                if (x > 0 && mask[p - 1] && label[p - 1] < 0) { label[p - 1] = 1; stack[sp++] = p - 1 }
                if (x < sw - 1 && mask[p + 1] && label[p + 1] < 0) { label[p + 1] = 1; stack[sp++] = p + 1 }
                if (y > 0 && mask[p - sw] && label[p - sw] < 0) { label[p - sw] = 1; stack[sp++] = p - sw }
                if (y < sh - 1 && mask[p + sw] && label[p + sw] < 0) { label[p + sw] = 1; stack[sp++] = p + sw }
            }
            if (cnt < minMasked) continue
            val fg = Color.rgb((sr / cnt).toInt(), (sg / cnt).toInt(), (sb / cnt).toInt())
            var bgBest = bgCandidates[0]
            var bgDist = Int.MAX_VALUE
            for (bg in bgCandidates) {
                // 取与前景差最大的背景色，保证 delta 反映真实对比
                val d = colorDistance(fg, bg)
                if (d < bgDist) { bgDist = d; bgBest = bg }
            }
            val rect = Rect2(
                offsetX + minX * scale,
                offsetY + minY * scale,
                offsetX + min(maxX + 1, sw) * scale,
                offsetY + min(maxY + 1, sh) * scale,
            )
            val merged = Region(rect, fg, bgBest, colorDistance(fg, bgBest), cnt)
            regions.add(merged)
        }

        return mergeRegions(regions, scale)
    }

    /** 把同一行的字符合并成"组件"（词/行级包围盒）。 */
    private fun mergeRegions(input: List<Region>, scale: Int): List<Region> {
        if (input.isEmpty()) return input
        val list = input.sortedWith(compareBy({ it.rect.top }, { it.rect.left })).toMutableList()
        val gapX = max(6, 8 * scale)
        val gapY = max(3, 3 * scale)
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val a = list[i]
                    val b = list[j]
                    val vOverlap = min(a.rect.bottom, b.rect.bottom) - max(a.rect.top, b.rect.top)
                    val hOverlap = min(a.rect.right, b.rect.right) - max(a.rect.left, b.rect.left)
                    val closeX = b.rect.left - a.rect.right
                    val closeY = b.rect.top - a.rect.bottom
                    val sameRow = vOverlap > min(a.rect.height, b.rect.height) * 0.45 && closeX in -gapX..gapX
                    val sameCol = hOverlap > min(a.rect.width, b.rect.width) * 0.45 && closeY in -gapY..gapY
                    if (sameRow || sameCol) {
                        val u = Rect2(
                            min(a.rect.left, b.rect.left), min(a.rect.top, b.rect.top),
                            max(a.rect.right, b.rect.right), max(a.rect.bottom, b.rect.bottom),
                        )
                        val m = a.masked + b.masked
                        val fg = blend(a.fg, a.masked, b.fg, b.masked)
                        list[i] = Region(u, fg, a.bg, colorDistance(fg, a.bg), m)
                        list.removeAt(j)
                        merged = true
                        break@outer
                    }
                }
            }
        }
        return list
    }

    private fun blend(c1: Int, n1: Int, c2: Int, n2: Int): Int {
        val t = (n1 + n2).coerceAtLeast(1)
        val r = (((c1 shr 16) and 0xFF) * n1 + ((c2 shr 16) and 0xFF) * n2) / t
        val g = (((c1 shr 8) and 0xFF) * n1 + ((c2 shr 8) and 0xFF) * n2) / t
        val b = ((c1 and 0xFF) * n1 + (c2 and 0xFF) * n2) / t
        return Color.rgb(r, g, b)
    }

    // ------------------------------------------------------------------
    // 单框对比度采样
    // ------------------------------------------------------------------

    private fun sampleContrast(crop: Bitmap, box: Rect, offsetX: Int, offsetY: Int): Triple<Int, Int, Int> {
        val r = Rect(box).apply {
            left = left.coerceIn(0, crop.width - 1)
            right = right.coerceIn(1, crop.width)
            top = top.coerceIn(0, crop.height - 1)
            bottom = bottom.coerceIn(1, crop.height)
        }
        if (r.width() <= 0 || r.height() <= 0) return Triple(Color.BLACK, Color.WHITE, 0)
        val area = r.width() * r.height()
        if (area > 400000) {
            val s = kotlin.math.sqrt(400000.0 / area).toFloat()
            val sw = max(1, (r.width() * s).toInt())
            val sh = max(1, (r.height() * s).toInt())
            val scaled = Bitmap.createBitmap(crop, r.left, r.top, r.width(), r.height())
            val small = Bitmap.createScaledBitmap(scaled, sw, sh, true)
            scaled.recycle()
            return contrastFromPixels(small, sw, sh).also { small.recycle() }
        }
        val px = IntArray(area)
        crop.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
        return contrastFromPixels(px)
    }

    private fun contrastOfRegion(crop: Bitmap, box: Rect2, offsetX: Int, offsetY: Int): Triple<Int, Int, Int> {
        val r = Rect(box.left - offsetX, box.top - offsetY, box.right - offsetX, box.bottom - offsetY)
        return sampleContrast(crop, r, offsetX, offsetY)
    }

    private fun contrastFromPixels(bitmap: Bitmap, w: Int, h: Int): Triple<Int, Int, Int> {
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        return contrastFromPixels(px)
    }

    private fun contrastFromPixels(px: IntArray): Triple<Int, Int, Int> {
        if (px.isEmpty()) return Triple(Color.BLACK, Color.WHITE, 0)
        val bins = IntArray(4096)
        for (c in px) bins[quant(c)]++
        val bgBin = (0 until 4096).maxByOrNull { bins[it] } ?: 0
        val bg = binColor(bgBin)
        var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0
        for (c in px) {
            if (colorDistance(c, bg) > 30) {
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; cnt++
            }
        }
        val fg = if (cnt > 0) Color.rgb((sr / cnt).toInt(), (sg / cnt).toInt(), (sb / cnt).toInt()) else bg
        return Triple(fg, bg, colorDistance(fg, bg))
    }

    /**
     * 碎片抑制：颜色连通域常把同一段文字/图标切碎成多个小块。
     * 规则：按面积从大到小保留；若某元素有 60% 以上被已保留元素覆盖，则丢弃。
     */
    private fun suppress(input: List<ScreenElement>): List<ScreenElement> {
        if (input.size < 2) return input
        // 优先级：有文字的 OCR 元素 > 无障碍节点 > 纯颜色块（颜色块常是大面积容器）
        val ordered = input.sortedWith(
            compareBy(
                {
                    when (it.source) {
                        ElementSource.OCR -> 0
                        ElementSource.ACCESSIBILITY -> 1
                        ElementSource.COLOR -> 2
                    }
                },
                { -it.bounds.area },
            )
        )
        val kept = ArrayList<ScreenElement>(ordered.size)
        for (e in ordered) {
            // 只对"纯颜色块"做抑制：被更优先的元素覆盖 60% 以上就丢弃
            val drop = e.source == ElementSource.COLOR && kept.any { k -> containment(k.bounds, e.bounds) > 0.6 }
            if (!drop) kept.add(e)
        }
        return kept
    }

    /** 交并比之外的"被包含度"：交集 / 较小者面积。 */
    private fun containment(a: Rect2, b: Rect2): Double {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bo = min(a.bottom, b.bottom)
        val inter = max(0, r - l) * max(0, bo - t)
        if (inter <= 0) return 0.0
        return inter.toDouble() / min(a.area, b.area).coerceAtLeast(1)
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    fun quant(c: Int): Int {
        val r = ((c shr 16) and 0xFF) shr 4
        val g = ((c shr 8) and 0xFF) shr 4
        val b = (c and 0xFF) shr 4
        return (r shl 8) or (g shl 4) or b
    }

    fun binColor(bin: Int): Int {
        val r = ((bin shr 8) and 0xF) * 17
        val g = ((bin shr 4) and 0xF) * 17
        val b = (bin and 0xF) * 17
        return Color.rgb(r, g, b)
    }

    fun colorDistance(c1: Int, c2: Int): Int {
        val dr = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
        val dg = abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
        val db = abs((c1 and 0xFF) - (c2 and 0xFF))
        return max(dr, max(dg, db))
    }

    fun iou(a: Rect2, b: Rect2): Double {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bo = min(a.bottom, b.bottom)
        val inter = max(0, r - l) * max(0, bo - t)
        if (inter <= 0) return 0.0
        val union = a.area + b.area - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    private fun position(r: Rect2, w: Int, h: Int): String {
        val hx = when {
            r.centerX < w / 3 -> "left"
            r.centerX > w * 2 / 3 -> "right"
            else -> "center"
        }
        val hy = when {
            r.centerY < h / 3 -> "top"
            r.centerY > h * 2 / 3 -> "bottom"
            else -> "middle"
        }
        return "$hy-$hx"
    }

    private fun Rect2.clamp(w: Int, h: Int): Rect2 = Rect2(
        left.coerceIn(0, w), top.coerceIn(0, h),
        right.coerceIn(0, w), bottom.coerceIn(0, h),
    )

    /** 生成调试叠加图：在截图上画出识别出的组件框。 */
    fun overlay(src: Bitmap, model: ScreenModel): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: src
        val canvas = Canvas(bmp)
        val stroke = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
        val label = Paint().apply { isAntiAlias = true; textSize = 26f; color = Color.WHITE }
        val labelBg = Paint().apply { color = 0xAA000000.toInt() }
        for (e in model.elements) {
            stroke.color = when (e.source) {
                ElementSource.OCR -> 0xFF34D399.toInt()
                ElementSource.ACCESSIBILITY -> 0xFF60A5FA.toInt()
                ElementSource.COLOR -> 0xFFFBBF24.toInt()
            }
            stroke.strokeWidth = if (e.text.isNotBlank()) 4f else 2f
            canvas.drawRect(
                e.bounds.left.toFloat(), e.bounds.top.toFloat(),
                e.bounds.right.toFloat(), e.bounds.bottom.toFloat(), stroke,
            )
            if (e.text.isNotBlank()) {
                val t = e.text.take(14)
                val tw = label.measureText(t)
                val boxTop = (e.bounds.top - 30).toFloat().coerceAtLeast(0f)
                canvas.drawRect(
                    e.bounds.left.toFloat(), boxTop,
                    e.bounds.left + tw + 8f, boxTop + 30f, labelBg,
                )
                canvas.drawText(t, e.bounds.left.toFloat() + 4f, (e.bounds.top - 8).toFloat().coerceAtLeast(20f), label)
            }
        }
        return bmp
    }
}

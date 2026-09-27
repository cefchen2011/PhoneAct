package com.dsh.phoneact.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Display
import com.dsh.phoneact.service.ActAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CaptureResult(val bitmap: Bitmap, val backend: String, val ms: Long)

/**
 * 截屏三通道，按配置与可用性择优：
 *  1. AccessibilityService#takeScreenshot (API30+，无弹窗、最快)
 *  2. Magisk su + screencap (兜底，root 强权限)
 *  3. MediaProjection (需授权，Xposed 可自动放行)
 */
object Capture {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "phoneact-cap") }

    @Volatile private var lastBackend: String = "none"
    val backendName: String get() = lastBackend

    @Volatile private var lastError: String = ""

    fun capture(): CaptureResult? {
        val t0 = System.currentTimeMillis()
        val order = when (Prefs.current.captureBackend) {
            CaptureBackend.ACCESSIBILITY -> listOf("a11y", "root")
            CaptureBackend.ROOT -> listOf("root", "a11y")
            CaptureBackend.MEDIA_PROJECTION -> listOf("mp", "a11y", "root")
            CaptureBackend.AUTO -> listOf("a11y", "root", "mp")
        }
        for (b in order) {
            val bmp = when (b) {
                "a11y" -> captureViaAccessibility()
                "root" -> captureViaRoot()
                "mp" -> MediaProjectionCapture.grab()
                else -> null
            }
            if (bmp != null) {
                lastBackend = b
                lastError = ""
                return CaptureResult(bmp, b, System.currentTimeMillis() - t0)
            }
        }
        Lg.w("截屏失败: $lastError")
        return null
    }

    private fun captureViaAccessibility(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val svc: AccessibilityService = ActAccessibilityService.inst ?: return null
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        runCatching {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        runCatching {
                            val hb: HardwareBuffer = screenshot.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            result = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                        }.onFailure { lastError = "a11y 位图转换失败: ${it.message}" }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        lastError = "a11y takeScreenshot 失败 code=$errorCode"
                        latch.countDown()
                    }
                })
            latch.await(4000, TimeUnit.MILLISECONDS)
        }.onFailure { lastError = "a11y 截屏异常: ${it.message}" }
        return result
    }

    private fun captureViaRoot(): Bitmap? {
        if (!RootShell.available) return null
        val png = RootShell.screencapPng() ?: run {
            lastError = RootShell.lastError.ifBlank { "root screencap 无输出" }
            return null
        }
        return runCatching {
            BitmapFactory.decodeByteArray(png, 0, png.size)
        }.getOrElse {
            lastError = "PNG 解码失败: ${it.message}"
            null
        }
    }

    /** 让 32 位图变 ARGB_8888，便于像素读取。 */
    fun ensureArgb(bmp: Bitmap): Bitmap =
        if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp

    // ------------------------------------------------------------------
    // 变化检测：32x32 灰度 + 平均绝对差 + aHash
    // ------------------------------------------------------------------
    data class Fingerprint(val hash: Long, val gray: IntArray, val w: Int, val h: Int)

    fun fingerprint(bmp: Bitmap, size: Int = 32): Fingerprint {
        val small = Bitmap.createScaledBitmap(bmp, size, size, true)
        val px = IntArray(size * size)
        small.getPixels(px, 0, size, 0, 0, size, size)
        if (small !== bmp) small.recycle()
        val gray = IntArray(px.size)
        var sum = 0L
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val v = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = v
            sum += v
        }
        val avg = (sum / px.size).toInt()
        var hash = 0L
        for (i in 0 until minOf(64, gray.size)) {
            if (gray[i] >= avg) hash = hash or (1L shl i)
        }
        return Fingerprint(hash, gray, size, size)
    }

    fun diff(a: Fingerprint, b: Fingerprint): Double {
        if (a.gray.size != b.gray.size) return 255.0
        var acc = 0L
        for (i in a.gray.indices) acc += kotlin.math.abs(a.gray[i] - b.gray[i])
        return acc.toDouble() / a.gray.size
    }
}

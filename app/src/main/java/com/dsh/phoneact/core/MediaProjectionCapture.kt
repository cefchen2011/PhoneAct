package com.dsh.phoneact.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MediaProjection 截屏通道。Xposed 侧可自动放行授权 Intent（见 MediaProjectionHook），
 * 未安装 Xposed 时需要用户手动点一次“开始录制”。
 */
object MediaProjectionCapture {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var width = 0
    private var height = 0
    private var density = 0

    val ready: Boolean get() = projection != null && reader != null

    /** 授权结果回调：由 MainActivity 的 onActivityResult 转交。 */
    fun onActivityResult(resultCode: Int, data: Intent?, ctx: Context) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Lg.w("MediaProjection 授权被拒绝")
            return
        }
        runCatching {
            val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            start(mpm.getMediaProjection(resultCode, data), ctx)
        }.onFailure { Lg.e("启动 MediaProjection 失败", it) }
    }

    @SuppressLint("WrongConstant")
    fun start(mp: MediaProjection, ctx: Context) {
        stop()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi

        val ht = HandlerThread("phoneact-mp").also { it.start() }
        thread = ht
        handler = Handler(ht.looper)
        val ir = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = ir
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Lg.w("MediaProjection 被停止")
            }
        }, handler)
        display = mp.createVirtualDisplay(
            "phoneact", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, handler
        )
        Lg.i("MediaProjection 已启动 ${width}x$height")
    }

    fun stop() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null; reader = null; projection = null
        thread?.quitSafely(); thread = null; handler = null
    }

    fun grab(timeoutMs: Long = 1500): Bitmap? {
        val ir = reader ?: return null
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        val listener = ImageReader.OnImageAvailableListener { r ->
            runCatching {
                val img = r.acquireLatestImage() ?: return@runCatching
                try {
                    val plane = img.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val rowPadding = rowStride - pixelStride * width
                    val b = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    b.copyPixelsFromBuffer(plane.buffer)
                    bmp = Bitmap.createBitmap(b, 0, 0, width, height)
                } finally {
                    img.close()
                }
            }.onFailure { Lg.e("MediaProjection 取帧失败", it) }
            latch.countDown()
        }
        ir.setOnImageAvailableListener(listener, handler)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        ir.setOnImageAvailableListener(null, null)
        return bmp
    }
}

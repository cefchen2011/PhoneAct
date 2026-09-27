package com.dsh.phoneact.core

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class OcrLine(val text: String, val rect: Rect, val confidence: Float = 1f)

/** 端侧 OCR（ML Kit 中文模型，离线内置，不依赖 GMS）。 */
object Ocr {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "phoneact-ocr") }
    private val client by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    @Volatile var lastError: String = ""
        private set

    @Volatile private var initFailed = false

    val available: Boolean get() = !initFailed

    fun recognize(bitmap: Bitmap, timeoutMs: Long = 4000): List<OcrLine> {
        if (initFailed) return emptyList()
        val latch = CountDownLatch(1)
        var out: List<OcrLine> = emptyList()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            client.process(image)
                .addOnSuccessListener { visionText ->
                    out = visionText.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            val t = line.text.trim()
                            if (t.isEmpty()) null else OcrLine(t, box)
                        }
                    }
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    lastError = "OCR 失败: ${e.message}"
                    latch.countDown()
                }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            out
        } catch (t: Throwable) {
            lastError = "OCR 异常: ${t.message}"
            initFailed = true
            emptyList()
        }
    }
}

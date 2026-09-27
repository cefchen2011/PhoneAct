package com.dsh.phoneact.core

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dsh.phoneact.PhoneActApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 把文字合成为语音 PCM，供 Xposed 侧注入到超级小爱的录音流里。
 *
 * 背景：超级小爱对"发消息/打电话"这类能力只认**语音**指令，文字会被拒
 * （实测回答："微信发消息、打电话、打视频电话功能当前仅支持语音对话方式"）。
 * 所以要让小爱真正 act，必须让它"听到"话 —— 做法是在 AudioRecord 读取处把
 * 真实的麦克风数据替换成这里合成的 PCM。
 */
object XiaoAiVoice {

    private const val TTS_RATE = 16000

    @Volatile private var lastError: String = ""

    data class Clip(val pcm: ByteArray, val sampleRate: Int, val seq: Long)

    @Volatile private var clip: Clip? = null

    val error: String get() = lastError
    val hasClip: Boolean get() = clip != null
    val seq: Long get() = clip?.seq ?: 0L
    /** 语音时长（毫秒），小爱的录音窗口只有约 5 秒，超了就白说。 */
    val durationMs: Int get() = ((clip?.pcm?.size ?: 0) / 2) * 1000 / TTS_RATE

    fun pcmFile(): File = File(PhoneActApp.instance.filesDir, "xiaoi_voice.pcm")

    fun metaJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("seq", seq)
        put("sampleRate", clip?.sampleRate ?: 0)
        put("bytes", clip?.pcm?.size ?: 0)
        put("hasClip", hasClip)
        if (lastError.isNotEmpty()) put("error", lastError)
    }

    /** 合成文本 -> 16k/单声道/16bit PCM。成功返回 true。 */
    fun synthesize(text: String, timeoutMs: Long = 15000): Boolean {
        lastError = ""
        val ctx: Context = PhoneActApp.instance
        val wav = File(ctx.cacheDir, "xiaoi_tts.wav")
        if (wav.exists()) wav.delete()

        val latch = CountDownLatch(1)
        var tts: TextToSpeech? = null
        var ok = false
        try {
            tts = TextToSpeech(ctx) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    lastError = "TTS 初始化失败 status=$status"
                    latch.countDown()
                }
            }
            // 等初始化
            var waited = 0
            while (waited < 6000 && latch.count != 0L) {
                Thread.sleep(100); waited += 100
            }
            if (latch.count == 0L) return false

            val engine = tts ?: return false
            runCatching { engine.setLanguage(Locale.CHINA) }
            runCatching { engine.setSpeechRate(1.0f) }

            val done = CountDownLatch(1)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { done.countDown() }
                @Deprecated("deprecated in API 21")
                override fun onError(utteranceId: String?) { done.countDown() }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    lastError = "TTS 合成失败 code=$errorCode"
                    done.countDown()
                }
            })

            val params = Bundle()
            val rc = engine.synthesizeToFile(text, params, wav, "phoneact")
            if (rc != TextToSpeech.SUCCESS) {
                lastError = "synthesizeToFile 返回 $rc"
                return false
            }
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!wav.exists() || wav.length() < 100) {
                lastError = lastError.ifBlank { "TTS 未生成音频文件" }
                return false
            }

            val (pcm, rate) = parseWav(wav.readBytes())
            if (pcm.isEmpty()) {
                lastError = "WAV 解析失败"
                return false
            }
            val resampled = if (rate == TTS_RATE) pcm else resample(pcm, rate, TTS_RATE)
            pcmFile().writeBytes(resampled)
            clip = Clip(resampled, TTS_RATE, System.currentTimeMillis())
            ok = true
            Lg.i("小爱语音已合成: ${text.length} 字 -> ${resampled.size} 字节 PCM @${TTS_RATE}Hz (源 ${rate}Hz)")
        } catch (t: Throwable) {
            lastError = "合成异常: ${t.message}"
            Lg.e("小爱语音合成失败", t)
        } finally {
            runCatching { tts?.shutdown() }
        }
        return ok
    }

    /** 直接用外部合成好的 WAV（base64）。设备上没有可用 TTS 引擎时的通道。 */
    fun loadWavBase64(b64: String): Boolean {
        lastError = ""
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val (pcm, rate) = parseWav(bytes)
            if (pcm.isEmpty()) { lastError = "WAV 解析失败（需要 16bit PCM WAV）"; return false }
            val resampled = if (rate == TTS_RATE) pcm else resample(pcm, rate, TTS_RATE)
            pcmFile().writeBytes(resampled)
            clip = Clip(resampled, TTS_RATE, System.currentTimeMillis())
            Lg.i("外部语音已载入: ${bytes.size}B WAV (${rate}Hz) -> ${resampled.size}B PCM @${TTS_RATE}Hz")
            true
        } catch (t: Throwable) {
            lastError = "载入外部语音失败: ${t.message}"
            false
        }
    }

    /**
     * 载入内置的触发词音频（assets/xiaoi_trigger.b64，约 1 秒的"你好"）。
     *
     * 用途：小爱只在语音路径下才开放"发消息/打电话"这类能力。
     * 我们不伪造来源，而是让它正常进入语音路径 —— 只喂一句极短的触发词让 ASR 出结果，
     * 再由输入层 hook 把识别文本篡改成真正的指令。
     * 这样既拿到 VoiceButton 来源，又不必让 ASR 去听一整句话。
     */
    fun loadTriggerBlip(): Boolean {
        lastError = ""
        return try {
            val txt = PhoneActApp.instance.assets.open("xiaoi_trigger.b64")
                .use { String(it.readBytes(), Charsets.UTF_8) }.trim()
            if (txt.isEmpty()) { lastError = "触发词音频为空"; return false }
            loadWavBase64(txt)
        } catch (t: Throwable) {
            lastError = "载入触发词失败: ${t.message}"
            false
        }
    }

    fun clear() {
        clip = null
        runCatching { pcmFile().delete() }
    }

    // ------------------------------------------------------------------

    /** 解析 TTS 输出的 WAV，返回 (单声道16bit PCM, 采样率)。 */
    private fun parseWav(b: ByteArray): Pair<ByteArray, Int> {
        if (b.size < 44 || b[0] != 'R'.code.toByte() || b[1] != 'I'.code.toByte()) return ByteArray(0) to 0
        var pos = 12
        var rate = 16000
        var channels = 1
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val sz = le32(b, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = le16(b, body + 2)
                    rate = le32(b, body + 4)
                    bits = le16(b, body + 14)
                }
                "data" -> {
                    dataOff = body
                    dataLen = minOf(sz, b.size - body)
                }
            }
            if (sz <= 0) break
            pos = body + sz + (sz and 1)
        }
        if (dataOff < 0 || dataLen <= 0) return ByteArray(0) to 0

        // 统一成单声道 16bit
        val src = b.copyOfRange(dataOff, dataOff + dataLen)
        val mono: ByteArray = when {
            bits == 16 && channels == 1 -> src
            bits == 16 && channels == 2 -> {
                val out = ByteArray(src.size / 2)
                var i = 0; var o = 0
                while (i + 3 < src.size) {
                    val l = le16(src, i)
                    val r = le16(src, i + 2)
                    val m = ((l + r) / 2)
                    out[o] = (m and 0xFF).toByte()
                    out[o + 1] = ((m shr 8) and 0xFF).toByte()
                    i += 4; o += 2
                }
                out
            }
            bits == 8 -> ByteArray(src.size * 2).also { out ->
                for (i in src.indices) {
                    val v = ((src[i].toInt() and 0xFF) - 128) shl 8
                    out[i * 2] = (v and 0xFF).toByte()
                    out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
            }
            else -> src
        }
        return mono to rate
    }

    /** 线性插值重采样（16bit 单声道）。 */
    private fun resample(pcm: ByteArray, from: Int, to: Int): ByteArray {
        if (from == to || from <= 0 || to <= 0) return pcm
        val inN = pcm.size / 2
        val outN = (inN.toLong() * to / from).toInt()
        val out = ByteArray(outN * 2)
        for (i in 0 until outN) {
            val x = i.toDouble() * from / to
            val i0 = x.toInt().coerceIn(0, inN - 1)
            val i1 = (i0 + 1).coerceIn(0, inN - 1)
            val f = x - i0
            val s0 = le16(pcm, i0 * 2).toShort().toInt()
            val s1 = le16(pcm, i1 * 2).toShort().toInt()
            val v = (s0 + (s1 - s0) * f).toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}

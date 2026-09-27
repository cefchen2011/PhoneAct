package com.dsh.phoneact.core

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dsh.phoneact.PhoneActApp
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 把文字合成为语音 PCM，供 Xposed 侧注入到超级小爱的录音流里。
 *
 * 小爱对"发消息/打电话"这类能力只认语音，且技能多为**多轮追问**
 * （例如先问发给谁说啥，再问"确认发送吗"）。因此这里维护的是一个**语音队列**：
 * 每一轮新的录音会话依次取下一段，而不是反复播同一句。
 */
object XiaoAiVoice {

    private const val TTS_RATE = 16000

    @Volatile private var lastError: String = ""
    val error: String get() = lastError

    data class Clip(val pcm: ByteArray, val sampleRate: Int = TTS_RATE) {
        val durationMs: Int get() = (pcm.size / 2) * 1000 / sampleRate
    }

    /** 当前会话的语音队列；seq 标识"第几次任务"，hook 靠它判断是否要重置轮次。 */
    @Volatile private var clips: List<Clip> = emptyList()
    @Volatile private var seqValue: Long = 0L

    /** 当前任务编号；hook 靠它区分"新任务"从而重置轮次。 */
    val seq: Long get() = seqValue

    val clipCount: Int get() = clips.size
    val hasClip: Boolean get() = clips.isNotEmpty()
    val durationMs: Int get() = clips.firstOrNull()?.durationMs ?: 0

    fun clipFile(i: Int): File = File(PhoneActApp.instance.filesDir, "xiaoi_voice_$i.pcm")

    fun resetQueue() {
        clips.forEachIndexed { i, _ -> runCatching { clipFile(i).delete() } }
        clips = emptyList()
        seqValue = 0L
    }

    fun metaJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("seq", seqValue)
        put("count", clips.size)
        put("sampleRate", TTS_RATE)
        put("durations", org.json.JSONArray().apply { clips.forEach { put(it.durationMs) } })
        if (lastError.isNotEmpty()) put("error", lastError)
    }

    /** 追加一段语音（本机 TTS 失败则回退 PC 端服务）。 */
    fun appendFromText(text: String): Boolean {
        if (text.isBlank()) return true
        val pcm = synthLocal(text) ?: synthFromServer(text) ?: return false
        appendPcm(pcm)
        return true
    }

    /**
     * 追加内置触发词（assets/xiaoi_trigger.b64，约 1 秒的"你好"）。
     * 用途：走语音路径但不想让 ASR 去听一整句时，只喂一个短触发词出结果，
     * 真正的指令由输入层 hook 替换。
     */
    fun appendTriggerBlip(): Boolean = try {
        val b64 = PhoneActApp.instance.assets.open("xiaoi_trigger.b64")
            .use { String(it.readBytes(), Charsets.UTF_8) }.trim()
        if (b64.isEmpty()) { lastError = "触发词资源为空"; false } else appendFromWavBase64(b64)
    } catch (t: Throwable) {
        lastError = "载入触发词失败: ${t.message}"
        false
    }

    /** 直接用外部合成好的 WAV（base64）追加一段。 */
    fun appendFromWavBase64(b64: String): Boolean = try {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val (pcm, rate) = parseWav(bytes)
        if (pcm.isEmpty()) { lastError = "WAV 解析失败（需要 16bit PCM WAV）"; false }
        else { appendPcm(if (rate == TTS_RATE) pcm else resample(pcm, rate, TTS_RATE)); true }
    } catch (t: Throwable) {
        lastError = "载入外部语音失败: ${t.message}"
        false
    }

    private fun appendPcm(pcm: ByteArray) {
        val list = clips.toMutableList()
        val idx = list.size
        clipFile(idx).writeBytes(pcm)
        list.add(Clip(pcm))
        clips = list
        if (seqValue == 0L) seqValue = System.currentTimeMillis()
        Lg.i("语音队列 +1: 第$idx 段 ${pcm.size}B ${(pcm.size / 2) * 1000 / TTS_RATE}ms，共${clips.size}段")
    }

    // ------------------------------------------------------------------
    // 本机 TTS
    // ------------------------------------------------------------------

    private fun synthLocal(text: String): ByteArray? {
        lastError = ""
        val ctx: Context = PhoneActApp.instance
        val wav = File(ctx.cacheDir, "xiaoi_tts.wav")
        if (wav.exists()) wav.delete()
        var tts: TextToSpeech? = null
        return try {
            val init = CountDownLatch(1)
            tts = TextToSpeech(ctx) { st -> if (st != TextToSpeech.SUCCESS) init.countDown() }
            var waited = 0
            while (waited < 6000 && init.count != 0L) { Thread.sleep(100); waited += 100 }
            if (init.count == 0L) { lastError = "TTS 初始化失败"; return null }
            val engine = tts
            runCatching { engine.setLanguage(Locale.CHINA) }
            runCatching { engine.setSpeechRate(1.0f) }
            val done = CountDownLatch(1)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("deprecated")
                override fun onError(id: String?) { done.countDown() }
                override fun onError(id: String?, errorCode: Int) { done.countDown() }
            })
            if (engine.synthesizeToFile(text, Bundle(), wav, "phoneact") != TextToSpeech.SUCCESS) {
                lastError = "synthesizeToFile 失败"; return null
            }
            done.await(12000, TimeUnit.MILLISECONDS)
            if (!wav.exists() || wav.length() < 100) { lastError = "TTS 未生成音频文件"; return null }
            val (pcm, rate) = parseWav(wav.readBytes())
            if (pcm.isEmpty()) { lastError = "WAV 解析失败"; return null }
            if (rate == TTS_RATE) pcm else resample(pcm, rate, TTS_RATE)
        } catch (t: Throwable) {
            lastError = "本机 TTS 异常: ${t.message}"
            null
        } finally {
            runCatching { tts?.shutdown() }
        }
    }

    // ------------------------------------------------------------------
    // PC 端 SAPI TTS 服务
    // ------------------------------------------------------------------

    private fun synthFromServer(text: String): ByteArray? {
        val base = Prefs.current.ttsServerUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            if (lastError.isEmpty()) lastError = "未配置 TTS 服务器地址，且本机无可用引擎"
            return null
        }
        val url = base + "/tts?rate=" + Prefs.current.ttsRate + "&text=" +
            java.net.URLEncoder.encode(text, "UTF-8")
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 20000; requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code != 200) { lastError = "TTS 服务器返回 $code"; conn.disconnect(); return null }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val (pcm, rate) = parseWav(bytes)
            if (pcm.isEmpty()) { lastError = "服务器 WAV 解析失败"; return null }
            if (rate == TTS_RATE) pcm else resample(pcm, rate, TTS_RATE)
        } catch (t: Throwable) {
            lastError = "访问 TTS 服务器失败: ${t.message}"
            null
        }
    }

    /** 取一段文本的音频（不加入队列），用于连通性自测。 */
    fun probeServer(text: String): Int {
        val pcm = synthFromServer(text) ?: return -1
        return pcm.size
    }

    // ------------------------------------------------------------------
    // WAV 解析 / 重采样
    // ------------------------------------------------------------------

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
        val src = b.copyOfRange(dataOff, dataOff + dataLen)
        val mono: ByteArray = when {
            bits == 16 && channels == 1 -> src
            bits == 16 && channels == 2 -> {
                val out = ByteArray(src.size / 2)
                var i = 0; var o = 0
                while (i + 3 < src.size) {
                    val m = (le16(src, i) + le16(src, i + 2)) / 2
                    out[o] = (m and 0xFF).toByte(); out[o + 1] = ((m shr 8) and 0xFF).toByte()
                    i += 4; o += 2
                }
                out
            }
            bits == 8 -> ByteArray(src.size * 2).also { out ->
                for (i in src.indices) {
                    val v = ((src[i].toInt() and 0xFF) - 128) shl 8
                    out[i * 2] = (v and 0xFF).toByte(); out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
            }
            else -> src
        }
        return mono to rate
    }

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
            out[i * 2] = (v and 0xFF).toByte(); out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}

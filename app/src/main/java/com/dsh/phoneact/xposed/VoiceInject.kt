package com.dsh.phoneact.xposed

import android.content.ContentResolver
import android.media.AudioRecord
import android.net.Uri
import org.json.JSONObject
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 在被注入的小爱进程里，把 AudioRecord 读到的麦克风数据替换成我们合成的语音。
 *
 * 为什么这样做：超级小爱对"发消息/打电话"等能力只认语音，文字指令会被拒；
 * 而它内部用的是自研引擎（不走标准 SpeechRecognizer），录音类还是混淆的。
 * 与其逆向它的私有 ASR 回调，不如在**框架层**动手 ——
 * hook android.media.AudioRecord 对所有 Java 层录音都有效，且不依赖版本内部结构。
 *
 * 数据来源：宿主 App 通过 XiaoAiAudioProvider 提供 TTS 合成的 PCM。
 * 触发时机：每次 startRecording() 时查一次序号，有新的就载入。
 */
object VoiceInject {

    private const val AUTHORITY = "com.dsh.phoneact.xiaoi"
    private const val KEY_SEQ = "phoneact_inject_seq"

    /** 当前任务已经喂到第几轮（多轮对话会多次 startRecording）。 */
    @Volatile private var turnIndex = 0

    @Volatile private var pcm: ByteArray? = null
    @Volatile private var cursor = 0
    @Volatile private var loadedSeq = -1L
    @Volatile private var silenceLeft = 0

    val readCalls = AtomicLong(0)
    val injectedBytes = AtomicLong(0)

    @Volatile var lastInfo: String = ""
        private set

    /** 一次注入结束后，再补这么多字节的静音，让小爱的 VAD 判定"说完了"。 */
    // 小爱的录音窗口只有约 5 秒，语音 + 静音必须留有余量
    private const val TAIL_SILENCE_BYTES = 16000 * 2 * 3 / 5   // 约 0.6 秒 @16k/16bit（单声道字节数）

    /**
     * 前导静音。
     *
     * 实测（关键）：直接从头喂语音时，**开头的字会被吞掉** ——
     *   真人 ASR: 给SUNSHINE发微信说你好
     *   注入 ASR: SUNSHINE发微信说你好     <- "给" 丢了
     * 丢首字会改变意图判定，小爱就不会走到"交棒微信"那一步。
     * 前面垫 0.4 秒静音后首字保留。
     */
    private const val LEAD_SILENCE_BYTES = 16000 * 2 * 2 / 5   // 约 0.4 秒

    @Volatile private var encoding = 2   // AudioFormat.ENCODING_PCM_16BIT

    fun onStartRecording(rec: AudioRecord?, enc: Int = 2) {
        readCalls.set(0)
        pcm = null
        cursor = 0
        silenceLeft = 0
        encoding = enc
        if (rec == null) return
        runCatching { loadClip(rec) }.onFailure { lastInfo = "载入失败: ${it.message}" }
    }

    fun onStop() {
        pcm = null
        cursor = 0
        silenceLeft = 0
    }

    private fun loadClip(rec: AudioRecord) {
        val ctx = HookContext.app() ?: run { lastInfo = "拿不到 Context"; return }
        val res: ContentResolver = ctx.contentResolver
        val uri = Uri.parse("content://$AUTHORITY/meta")
        val bundle = res.call(uri, "meta", null, null) ?: run { lastInfo = "provider 无响应"; return }
        val meta = JSONObject(bundle.getString("meta") ?: "{}")
        // 动态 hook：没武装就完全不碰麦克风数据
        if (!meta.optBoolean("armed", false)) {
            lastInfo = "注入未武装，跳过"
            return
        }
        val seq = meta.optLong("seq", 0L)
        val count = meta.optInt("count", 0)
        if (seq <= 0L || count <= 0) return
        // 新任务 -> 轮次归零；否则沿用进度（小爱的技能多为多轮追问，
        // 每轮都会重新 startRecording，必须逐轮喂不同的音频）
        if (seq != loadedSeq) {
            loadedSeq = seq
            turnIndex = 0
        }
        if (turnIndex >= count) {
            lastInfo = "语音队列已用完（共$count 轮）"
            return
        }

        val raw = readAll(res, Uri.parse("content://$AUTHORITY/pcm?i=$turnIndex")) ?: run {
            lastInfo = "读取第$turnIndex 段失败"; return
        }
        if (raw.isEmpty()) { lastInfo = "第$turnIndex 段为空"; return }
        val thisTurn = turnIndex
        turnIndex++

        val srcRate = meta.optInt("sampleRate", 16000).coerceAtLeast(8000)
        val devRate = runCatching { rec.sampleRate }.getOrDefault(16000).coerceAtLeast(8000)
        // 实测小爱用 CHANNEL_IN_2POINT0POINT2（getChannelCount()=4），所以不能只处理 1/2 声道
        val channels = runCatching { rec.channelCount }.getOrDefault(1).coerceIn(1, 8)

        var data = if (srcRate == devRate) raw else resample(raw, srcRate, devRate)
        // 按设备实际编码把 16bit 再转一次，否则喂进去就是噪声
        when (encoding) {
            3 -> data = s16ToFloat32(data)      // ENCODING_PCM_FLOAT
            1 -> data = s16ToU8(data)           // ENCODING_PCM_8BIT
            21, 22 -> data = s16ToS32(data)     // ENCODING_PCM_32BIT / 24BIT_PACKED
            else -> Unit                        // 16bit 直接用
        }
        if (channels > 1) data = monoToN(data, channels, encoding)

        // 前置静音：避免开头第一个字被录音启动过程吞掉
        val lead = ByteArray(LEAD_SILENCE_BYTES * channels)
        val withLead = ByteArray(lead.size + data.size)
        System.arraycopy(lead, 0, withLead, 0, lead.size)
        System.arraycopy(data, 0, withLead, lead.size, data.size)
        data = withLead

        pcm = data
        cursor = 0
        silenceLeft = TAIL_SILENCE_BYTES
        lastInfo = "已载入第 $thisTurn/${count - 1} 轮语音 ${raw.size}B (${srcRate}Hz) -> ${devRate}Hz/${channels}ch" +
            "，前置静音 ${LEAD_SILENCE_BYTES / 32}ms 共 ${data.size}B"
        hookLog("[PhoneAct] $lastInfo")
    }

    /** 用我们的数据覆盖缓冲区；返回 true 表示已注入。 */
    fun fill(buf: ByteArray, off: Int, len: Int): Boolean {
        val data = pcm ?: return false
        if (len <= 0) return false
        var written = 0
        var i = off
        while (written < len) {
            if (cursor < data.size) {
                val n = minOf(len - written, data.size - cursor)
                System.arraycopy(data, cursor, buf, i, n)
                cursor += n; i += n; written += n
            } else if (silenceLeft > 0) {
                val n = minOf(len - written, silenceLeft)
                java.util.Arrays.fill(buf, i, i + n, 0)
                silenceLeft -= n; i += n; written += n
            } else {
                pcm = null
                return written > 0
            }
        }
        injectedBytes.addAndGet(len.toLong())
        return true
    }

    fun fillShorts(buf: ShortArray, off: Int, len: Int): Boolean {
        val data = pcm ?: return false
        if (len <= 0) return false
        var written = 0
        var i = off
        while (written < len) {
            if (cursor < data.size) {
                val n = minOf(len - written, (data.size - cursor) / 2)
                if (n <= 0) { cursor = data.size; continue }
                for (k in 0 until n) {
                    val lo = data[cursor + k * 2].toInt() and 0xFF
                    val hi = data[cursor + k * 2 + 1].toInt()
                    buf[i + k] = ((hi shl 8) or lo).toShort()
                }
                cursor += n * 2; i += n; written += n
            } else if (silenceLeft > 0) {
                val n = minOf(len - written, silenceLeft / 2)
                if (n <= 0) { silenceLeft = 0; continue }
                java.util.Arrays.fill(buf, i, i + n, 0.toShort())
                silenceLeft -= n * 2; i += n; written += n
            } else {
                pcm = null
                return written > 0
            }
        }
        injectedBytes.addAndGet(len.toLong() * 2)
        return true
    }

    // ------------------------------------------------------------------

    private fun readAll(res: ContentResolver, uri: Uri): ByteArray? = runCatching {
        val pfd = res.openFileDescriptor(uri, "r") ?: return null
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
    }.getOrNull()

    private fun resample(pcm: ByteArray, from: Int, to: Int): ByteArray {
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

    /**
     * 单声道 -> n 声道。
     *
     * 实测（小爱 7.13.33）：把同一份样本复制到全部 4 个声道时，ASR 返回空
     * （recordQueryInfo error: query=），而真人说话正常。
     * 怀疑多麦阵列做了差分/波束成形处理，4 路同相信号会被抵消。
     * 因此默认只把语音放进第 0 声道，其余声道置零 —— 由 CH_MODE 控制，便于对比验证。
     */
    private fun monoToN(mono: ByteArray, n: Int, enc: Int): ByteArray {
        val bytesPerSample = when (enc) {
            3, 21, 22 -> 4
            1 -> 1
            else -> 2
        }
        val frames = mono.size / bytesPerSample
        val out = ByteArray(frames * bytesPerSample * n)
        var o = 0
        for (i in 0 until frames) {
            // 声道 0 = 真实语音
            System.arraycopy(mono, i * bytesPerSample, out, o, bytesPerSample)
            o += bytesPerSample
            // 其余声道保持 0
            o += bytesPerSample * (n - 1)
        }
        return out
    }

    /** 1 = 仅声道0（默认），2 = 全声道复制。 */
    @Volatile var channelMode: Int = 1

    private fun s16ToFloat32(s: ByteArray): ByteArray {
        val n = s.size / 2
        val out = ByteArray(n * 4)
        val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = (((s[i * 2 + 1].toInt() shl 8) or (s[i * 2].toInt() and 0xFF)).toShort()).toFloat() / 32768f
            bb.putFloat(v)
        }
        return out
    }

    private fun s16ToS32(s: ByteArray): ByteArray {
        val n = s.size / 2
        val out = ByteArray(n * 4)
        val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = ((s[i * 2 + 1].toInt() shl 8) or (s[i * 2].toInt() and 0xFF)).toShort().toInt()
            bb.putInt(v shl 16)
        }
        return out
    }

    private fun s16ToU8(s: ByteArray): ByteArray {
        val n = s.size / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val v = ((s[i * 2 + 1].toInt() shl 8) or (s[i * 2].toInt() and 0xFF)).toShort().toInt()
            out[i] = (((v shr 8) + 128).coerceIn(0, 255)).toByte()
        }
        return out
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun hookLog(m: String) = Bridge.log(m, "inject")
}

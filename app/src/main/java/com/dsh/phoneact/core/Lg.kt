package com.dsh.phoneact.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 轻量日志：logcat + 内存环形缓冲(GC 可见) + 文件落盘。 */
object Lg {
    private const val TAG = "PhoneAct"
    private const val MAX_LINES = 800
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val ring = ArrayDeque<String>(MAX_LINES)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(ctx: Context) {
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "phoneact.log")
            if (f.exists() && f.length() > MAX_FILE_BYTES) f.delete()
            logFile = f
        }
    }

    fun i(msg: String) = write('I', msg, null)
    fun w(msg: String) = write('W', msg, null)
    fun e(msg: String, t: Throwable? = null) = write('E', msg, t)
    fun d(msg: String) = write('D', msg, null)

    private fun write(level: Char, msg: String, t: Throwable?) {
        val line = "${fmt.format(Date())} [$level] $msg"
        when (level) {
            'E' -> if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
            'W' -> Log.w(TAG, msg)
            'D' -> Log.d(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > MAX_LINES) ring.removeFirst()
            _lines.value = ring.toList()
        }
        if (t != null) Log.getStackTraceString(t).lineSequence().forEach { Log.e(TAG, "    at $it") }
        runCatching {
            logFile?.appendText(line + if (t != null) "\n" + Log.getStackTraceString(t) else "" + "\n")
        }
    }

    fun clear() {
        synchronized(ring) {
            ring.clear()
            _lines.value = emptyList()
        }
    }

    fun dump(): String = synchronized(ring) { ring.joinToString("\n") }
}

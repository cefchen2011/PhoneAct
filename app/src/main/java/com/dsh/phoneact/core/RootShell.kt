package com.dsh.phoneact.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String, val ms: Long) {
    val ok: Boolean get() = exitCode == 0
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("exitCode", exitCode).put("stdout", stdout).put("stderr", stderr).put("ms", ms)
}

/**
 * 常驻 Magisk su 会话。用随机哨兵行把多条命令串行化，文本命令走管道；
 * 二进制（screencap）走 /data/local/tmp 中转文件，避免大字节流解析开销。
 */
object RootShell {
    private val seq = AtomicInteger(0)

    @Volatile private var proc: Process? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var stdout: InputStream? = null

    private val lock = Any()

    @Volatile var available: Boolean = false
        private set

    @Volatile var lastError: String = ""
        private set

    @Volatile var suPath: String = "su"
        private set

    fun probe(): Boolean {
        val r = run("id", 8000)
        available = r.ok && r.stdout.contains("uid=0")
        if (available) {
            val w = run("which su || command -v su", 8000)
            w.stdout.trim().lineSequence().firstOrNull { it.isNotBlank() }?.let { suPath = it.trim() }
            lastError = ""
        } else {
            lastError = "su 不可用: " + r.stderr.ifBlank { r.stdout }.trim().take(200)
        }
        return available
    }

    private fun ensureSession(): Boolean {
        val p = proc
        if (p != null && p.isAlive && stdin != null && stdout != null) return true
        return runCatching {
            val np = ProcessBuilder(suPath).start()
            proc = np
            stdin = np.outputStream
            stdout = np.inputStream
            Thread {
                runCatching {
                    val buf = ByteArray(8192)
                    while (np.errorStream.read(buf) >= 0) { /* drain stderr */ }
                }
            }.apply { isDaemon = true; name = "phoneact-su-stderr" }.start()
            val alive = np.isAlive
            if (!alive) lastError = "su 进程立即退出"
            alive
        }.getOrElse {
            lastError = "启动 su 失败: ${it.message}"
            close()
            false
        }
    }

    fun close() {
        runCatching { stdin?.close() }
        runCatching { proc?.destroy() }
        proc = null; stdin = null; stdout = null
    }

    class RawResult(val exitCode: Int, val bytes: ByteArray, val stderr: String, val ms: Long)

    /** 执行命令，返回 UTF-8 文本结果。 */
    fun run(cmd: String, timeoutMs: Long = 20000): ShellResult {
        val r = exec(cmd, timeoutMs)
        return ShellResult(r.exitCode, String(r.bytes, Charsets.UTF_8), r.stderr, r.ms)
    }

    /** 执行命令，返回原始 stdout 字节。 */
    fun exec(cmd: String, timeoutMs: Long = 20000): RawResult = synchronized(lock) {
        val t0 = System.currentTimeMillis()
        if (!ensureSession()) return RawResult(-1, ByteArray(0), lastError, 0L)

        val token = "PHONEACT_%d_%d".format(System.nanoTime(), seq.incrementAndGet())
        // 哨兵行同时携带退出码，且解析时会连同行尾一起消费，
        // 否则残留的 "\n<exitCode>\n" 会污染下一条命令的 stdout。
        val marker = "\n__" + token + "__ "
        val markerBytes = marker.toByteArray(Charsets.UTF_8)
        val script = "export LC_ALL=C; " + cmd +
            "\nprintf '\\n__" + token + "__ %s\\n' \"\$?\"\n"

        return try {
            val out = stdin ?: return RawResult(-1, ByteArray(0), "no stdin", 0L)
            val inp = stdout ?: return RawResult(-1, ByteArray(0), "no stdout", 0L)
            out.write(script.toByteArray(Charsets.UTF_8))
            out.flush()

            val acc = ByteArrayOutputStream(1 shl 16)
            val buf = ByteArray(1 shl 16)
            val deadline = System.currentTimeMillis() + timeoutMs
            var scanFrom = 0

            while (System.currentTimeMillis() < deadline) {
                val avail = inp.available()
                if (avail <= 0) {
                    Thread.sleep(3)
                    if (proc?.isAlive != true) break
                    continue
                }
                val n = inp.read(buf, 0, minOf(buf.size, avail))
                if (n <= 0) break
                acc.write(buf, 0, n)
                val all = acc.toByteArray()
                val found = indexOf(all, markerBytes, scanFrom)
                if (found >= 0) {
                    var i = found + markerBytes.size
                    val sb = StringBuilder()
                    var complete = false
                    while (i < all.size && sb.length < 16) {
                        val c = all[i].toInt().toChar()
                        if (c == '\n') { complete = true; i++; break }
                        sb.append(c); i++
                    }
                    if (complete) {
                        // i 已越过哨兵行行尾，管道中不再有残留字节
                        return RawResult(sb.toString().trim().toIntOrNull() ?: 0,
                            all.copyOfRange(0, found), "", System.currentTimeMillis() - t0)
                    }
                    // 哨兵行尚未读全，保留重叠窗口继续读
                    scanFrom = (all.size - markerBytes.size - 16).coerceAtLeast(0)
                } else {
                    scanFrom = (all.size - markerBytes.size - 16).coerceAtLeast(0)
                }
            }
            RawResult(-2, acc.toByteArray(), "命令超时或会话中断: " + cmd, System.currentTimeMillis() - t0)
        } catch (t: Throwable) {
            lastError = "执行失败: ${t.message}"
            close()
            RawResult(-3, ByteArray(0), lastError, System.currentTimeMillis() - t0)
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || hay.size - from < needle.size) return -1
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ------------------------------------------------------------------
    // 便捷封装
    // ------------------------------------------------------------------

    /** screencap 到中转文件，返回 PNG 字节；失败返回 null。 */
    fun screencapPng(): ByteArray? {
        val path = "/data/local/tmp/phoneact_cap.png"
        val r = exec("screencap -p " + path + " && chmod 666 " + path, 20000)
        if (r.exitCode != 0) {
            lastError = "screencap 失败: " + String(r.bytes, Charsets.UTF_8).take(200)
            return null
        }
        return runCatching {
            val f = File(path)
            if (!f.exists() || f.length() == 0L) return null
            val bytes = f.readBytes()
            f.delete()
            bytes
        }.getOrNull()
    }

    fun tap(x: Int, y: Int): ShellResult = run("input tap " + x + " " + y, 12000)

    fun longPress(x: Int, y: Int, ms: Long): ShellResult =
        run("input swipe " + x + " " + y + " " + x + " " + y + " " + ms, 12000)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): ShellResult =
        run("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms, 15000)

    fun keyEvent(code: Int): ShellResult = run("input keyevent " + code, 12000)

    fun inputText(text: String): ShellResult {
        val escaped = text.replace(" ", "%s").replace("'", "'\\''")
        return run("input text '" + escaped + "'", 15000)
    }
}

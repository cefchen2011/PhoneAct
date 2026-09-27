package com.dsh.phoneact.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 被注入进程回传的 hook 轨迹（环形缓冲）。
 * 让"执行操作"和"看注入日志"在同一个 MCP 调用里闭环，不必再 adb 翻 LSPosed 日志。
 */
object Trace {

    private const val MAX = 600

    data class Entry(val ts: Long, val tag: String, val msg: String)

    private val ring = ArrayDeque<Entry>(MAX)
    private val _flow = MutableStateFlow<List<Entry>>(emptyList())
    val flow: StateFlow<List<Entry>> = _flow.asStateFlow()

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, msg: String) {
        synchronized(ring) {
            ring.addLast(Entry(System.currentTimeMillis(), tag, msg))
            while (ring.size > MAX) ring.removeFirst()
            _flow.value = ring.toList()
        }
    }

    fun tail(n: Int): List<Entry> = synchronized(ring) { ring.toList().takeLast(n.coerceIn(1, MAX)) }

    fun clear() = synchronized(ring) {
        ring.clear(); _flow.value = emptyList()
    }

    /** 自 since 之后新增的轨迹。 */
    fun since(sinceTs: Long): List<Entry> = synchronized(ring) { ring.filter { it.ts >= sinceTs } }

    fun format(entries: List<Entry>): String =
        if (entries.isEmpty()) "(无 hook 轨迹)" else entries.joinToString("\n") { "${fmt.format(Date(it.ts))} [${it.tag}] ${it.msg}" }

    val size: Int get() = synchronized(ring) { ring.size }
}

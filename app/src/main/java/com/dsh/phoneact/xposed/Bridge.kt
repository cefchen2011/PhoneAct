package com.dsh.phoneact.xposed

import android.content.Intent

/**
 * 被注入进程 -> 宿主 的回传通道。
 *
 * 之前排查全靠 adb 去 grep LSPosed 日志文件，链路长、还依赖 root。
 * 这里把 hook 日志同时发一条广播回宿主，宿主存环形缓冲并由 MCP 直接读，
 * 于是"操作 + 看日志"在同一个工具调用里闭环。
 */
object Bridge {

    const val HOST_PACKAGE = "com.dsh.phoneact"
    const val ACTION = "com.dsh.phoneact.XPOSED_EVENT"

    /**
     * 必须用**显式组件**投递。
     * 实测 MIUI 会把"非保护广播"的隐式 Intent 丢掉（logcat 里只有
     * "Sending non-protected broadcast ... from system" 警告，接收器收不到）；
     * 改成 setClassName 指名接收器后立即到达。
     * 这里只用类名字符串，不跨 ClassLoader 引用宿主类。
     */
    const val RECEIVER = "com.dsh.phoneact.xposed.XposedEventReceiver"

    @Volatile private var lastMsg = ""
    @Volatile private var lastAt = 0L

    fun log(msg: String, tag: String = "hook") {
        runCatching { de.robv.android.xposed.XposedBridge.log("[PhoneAct] $msg") }
        // 同一条消息 500ms 内不重复回传，避免高频 hook 把广播打爆
        val now = System.currentTimeMillis()
        if (msg == lastMsg && now - lastAt < 500) return
        lastMsg = msg
        lastAt = now
        runCatching {
            val ctx = HookContext.app() ?: return@runCatching
            val i = Intent(ACTION)
            i.setPackage(HOST_PACKAGE)
            i.setClassName(HOST_PACKAGE, RECEIVER)
            i.putExtra("pa_type", "log")
            i.putExtra("pa_tag", tag)
            i.putExtra("pa_msg", msg.take(500))
            i.putExtra("pa_ts", now)
            ctx.sendBroadcast(i)
        }
    }
}

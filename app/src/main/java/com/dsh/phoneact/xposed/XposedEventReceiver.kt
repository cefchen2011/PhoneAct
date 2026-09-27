package com.dsh.phoneact.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dsh.phoneact.core.FrameHub
import com.dsh.phoneact.core.Lg

/**
 * 接收被 hook 进程（任意 App / SystemUI / system_server）上报的事件。
 * 只认隐式私有 action，不承载敏感数据；无法解析的一律忽略。
 */
class XposedEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val i = intent ?: return
        if (i.action != ACTION) return
        when (i.getStringExtra("pa_type")) {
            "foreground" -> {
                val pkg = i.getStringExtra("pa_package") ?: return
                val act = i.getStringExtra("pa_activity").orEmpty()
                FrameHub.onXposedForeground(pkg, act)
            }
            "content" -> {
                FrameHub.onXposedContentChange(i.getStringExtra("pa_package").orEmpty())
            }
            "alive" -> {
                XposedStatus.markAlive(
                    pkg = i.getStringExtra("pa_package").orEmpty(),
                    process = i.getStringExtra("pa_process").orEmpty(),
                    hookCsv = i.getStringExtra("pa_hooks").orEmpty(),
                    bridge = i.getIntExtra("pa_bridge", 0),
                )
            }
            else -> Unit
        }
    }

    companion object {
        const val ACTION = "com.dsh.phoneact.XPOSED_EVENT"
    }
}

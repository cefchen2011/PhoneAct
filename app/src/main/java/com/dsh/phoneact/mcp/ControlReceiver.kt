package com.dsh.phoneact.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dsh.phoneact.core.ActCore
import com.dsh.phoneact.core.Lg
import com.dsh.phoneact.core.Prefs
import java.io.File

/**
 * 可脚本化的控制入口，便于 root/adb 侧无 GUI 驱动：
 *
 *   adb shell su -c 'am broadcast -a com.dsh.phoneact.START \
 *       --es token <token> -n com.dsh.phoneact/.mcp.ControlReceiver'
 *
 * 需要携带与设置页一致的令牌；令牌不符直接忽略，避免任意应用拉起服务。
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val i = intent ?: return
        val action = i.action ?: return
        if (action !in ACTIONS) return
        val token = i.getStringExtra("token").orEmpty()
        if (Prefs.current.mcpAuthToken.isNotEmpty() && token != Prefs.current.mcpAuthToken) {
            Lg.w("ControlReceiver 拒绝：令牌不匹配 ($action)")
            return
        }
        ActCore.init(ctx.applicationContext)
        when (action) {
            ACTION_START -> {
                val port = i.getIntExtra("port", 0)
                if (port in 1024..65535) Prefs.update { it.copy(mcpEnabled = true, mcpPort = port) }
                else Prefs.update { it.copy(mcpEnabled = true) }
                ActCore.startPipeline()
                ActCore.startMcp()
                Lg.i("ControlReceiver: START")
            }
            ACTION_STOP -> {
                Prefs.update { it.copy(mcpEnabled = false) }
                ActCore.stopMcp()
                ActCore.stopPipeline()
                Lg.i("ControlReceiver: STOP")
            }
            ACTION_STATUS -> Lg.i("ControlReceiver: STATUS")
        }
        writeStatus(ctx)
    }

    private fun writeStatus(ctx: Context) {
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "status")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "status.json").writeText(ActCore.statusJson().toString(2))
        }.onFailure { Lg.e("写出状态失败", it) }
    }

    companion object {
        const val ACTION_START = "com.dsh.phoneact.START"
        const val ACTION_STOP = "com.dsh.phoneact.STOP"
        const val ACTION_STATUS = "com.dsh.phoneact.STATUS"
        private val ACTIONS = setOf(ACTION_START, ACTION_STOP, ACTION_STATUS)
    }
}

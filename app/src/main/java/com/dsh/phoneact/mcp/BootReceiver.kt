package com.dsh.phoneact.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dsh.phoneact.core.FrameHub
import com.dsh.phoneact.core.Lg
import com.dsh.phoneact.core.Prefs
import com.dsh.phoneact.service.CaptureService

/** 开机/升级后按用户既有配置自动恢复服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        Lg.i("BootReceiver 收到 $action")
        com.dsh.phoneact.core.ActCore.init(ctx.applicationContext)
        if (Prefs.current.mcpEnabled || Prefs.current.recognizeOnChange) {
            CaptureService.start(ctx)
            FrameHub.start()
            if (Prefs.current.mcpEnabled) {
                McpServer.start(Prefs.current.mcpPort, Prefs.current.mcpBindAll)
            }
        }
    }
}

package com.dsh.phoneact.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.dsh.phoneact.R
import com.dsh.phoneact.core.FrameHub
import com.dsh.phoneact.core.Lg
import com.dsh.phoneact.core.Prefs
import com.dsh.phoneact.mcp.McpServer
import com.dsh.phoneact.ui.MainActivity

/**
 * 前台服务：让识别循环 + MCP 服务在后台常驻（Android 8+ 后台进程会被冻结）。
 * 使用 specialUse 类型，避免未持有 MediaProjection 令牌时启动失败。
 */
class CaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure { Lg.e("前台服务启动失败", it) }

        if (!FrameHub.running) FrameHub.start()
        if (Prefs.current.mcpEnabled && !McpServer.running) {
            McpServer.start(Prefs.current.mcpPort, Prefs.current.mcpBindAll)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        Lg.i("CaptureService 停止")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "PhoneAct 运行状态", NotificationManager.IMPORTANCE_LOW)
        ch.description = "屏幕识别与 MCP 服务常驻通知"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val port = Prefs.current.mcpPort
        val text = if (McpServer.running) "MCP 已监听 :$port · 识别循环运行中" else "识别循环运行中"
        return builder
            .setContentTitle("PhoneAct 正在运行")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_phoneact)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "phoneact_running"
        private const val NOTIF_ID = 4101

        @Volatile var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, CaptureService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { Lg.e("启动 CaptureService 失败", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, CaptureService::class.java)) }
        }
    }
}

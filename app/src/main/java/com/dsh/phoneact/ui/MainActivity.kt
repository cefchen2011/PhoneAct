package com.dsh.phoneact.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.dsh.phoneact.core.ActCore
import com.dsh.phoneact.core.Lg
import com.dsh.phoneact.core.MediaProjectionCapture
import com.dsh.phoneact.core.Prefs
import com.dsh.phoneact.core.RootShell
import com.dsh.phoneact.service.ActAccessibilityService
import com.dsh.phoneact.service.CaptureService
import com.dsh.phoneact.ui.theme.PhoneActTheme

class MainActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        MediaProjectionCapture.onActivityResult(result.resultCode, result.data, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ActCore.init(applicationContext)
        requestNotificationPermission()
        maybeRequestProjection()
        if (Prefs.current.mcpEnabled || Prefs.current.recognizeOnChange) {
            CaptureService.start(this)
        }
        setContent {
            PhoneActTheme {
                PhoneActRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ActCore.refresh()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            runCatching {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun maybeRequestProjection() {
        if (Prefs.current.captureBackend != com.dsh.phoneact.core.CaptureBackend.MEDIA_PROJECTION) return
        if (MediaProjectionCapture.ready) return
        runCatching {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }.onFailure { Lg.e("请求录屏权限失败", it) }
    }
}

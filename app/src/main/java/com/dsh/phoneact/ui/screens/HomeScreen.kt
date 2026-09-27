package com.dsh.phoneact.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.phoneact.core.ActCore
import com.dsh.phoneact.core.FrameHub
import com.dsh.phoneact.core.Prefs
import com.dsh.phoneact.core.RootShell
import com.dsh.phoneact.service.ActAccessibilityService
import com.dsh.phoneact.ui.SectionCard
import com.dsh.phoneact.ui.StatusRow
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ActCore.state.collectAsState()
    val settings by Prefs.flow.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("自动化能力", "三条通道自动择优：无障碍 → Magisk su → Xposed 增强") {
            StatusRow("Root", if (state.rootAvailable) "已授权" else "未授权", state.rootAvailable, extra = state.rootMessage)
            StatusRow(
                "无障碍",
                if (state.a11yConnected) "已连接" else "未开启",
                state.a11yConnected,
                extra = if (state.a11yConnected) "可读控件树 / 手势注入 / takeScreenshot" else "点击下方按钮前往系统设置开启",
            )
            StatusRow(
                "Xposed",
                if (state.xposedActive) "模块已生效" else "未激活",
                state.xposedActive,
                warn = !state.xposedActive,
                extra = if (state.xposedActive) "前台追踪 / 内容更新信号 / 授权弹窗自动确认已启用" else "未安装 LSPosed 时功能自动降级，不影响使用",
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) {
                    Icon(Icons.Filled.Accessibility, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("无障碍设置", maxLines = 1)
                }
                OutlinedButton(onClick = { ActCore.probeRoot() }) {
                    Icon(Icons.Filled.Lock, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("检测 Root", maxLines = 1)
                }
            }
        }

        SectionCard("运行状态", "识别循环 + MCP 服务") {
            StatusRow("识别循环", if (state.pipelineRunning) "运行中" else "已停止", state.pipelineRunning)
            StatusRow("MCP 服务", if (state.mcpRunning) "监听中" else "已停止", state.mcpRunning, extra = state.mcpUrl.ifBlank { null })
            StatusRow("OCR", if (state.ocrAvailable) "可用（ML Kit 中文）" else "不可用", state.ocrAvailable)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { ActCore.startPipeline() } },
                    enabled = !state.pipelineRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("启动")
                }
                OutlinedButton(
                    onClick = { ActCore.stopPipeline() },
                    enabled = state.pipelineRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Stop, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("停止")
                }
            }
        }

        SectionCard("实时概览", "每次屏幕内容更新都会重新识别一次") {
            StatusRow("已观测帧", state.frames.toString(), true)
            StatusRow("识别序号", state.seq.toString(), true)
            StatusRow("元素数量", state.elementCount.toString(), state.elementCount > 0, warn = state.elementCount == 0)
            StatusRow("上次耗时", "${state.lastRecognizeMs} ms", state.lastRecognizeMs in 1..4000, warn = state.lastRecognizeMs > 4000)
            StatusRow("前台应用", state.foregroundPackage.ifBlank { "—" }, state.foregroundPackage.isNotBlank(), warn = state.foregroundPackage.isBlank())
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(onClick = {
                scope.launch {
                    if (!FrameHub.running) FrameHub.start()
                    FrameHub.recognizeNow(true)
                    ActCore.refresh()
                }
            }) {
                Icon(Icons.Filled.Bolt, null)
                Spacer(Modifier.padding(4.dp))
                Text("立即识别一次")
            }
        }

        SectionCard("当前配置摘要", null) {
            Text(
                "分块：" + buildString {
                    append(if (settings.splitStatusBar) "状态栏 " else "")
                    append(if (settings.splitNavBar) "+ 导航栏 " else "")
                    append("+ 主体")
                }.trim(),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("颜色不一致阈值：${settings.colorDeltaThreshold} / 255", style = MaterialTheme.typography.bodySmall)
            Text("OCR：" + if (settings.ocrEnabled) "开启" else "关闭", style = MaterialTheme.typography.bodySmall)
            Text("操作通道：" + settings.actionBackend.name, style = MaterialTheme.typography.bodySmall)
            Text("截屏通道：" + settings.captureBackend.name, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }
}

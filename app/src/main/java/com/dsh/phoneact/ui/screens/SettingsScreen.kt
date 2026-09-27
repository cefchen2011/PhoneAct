package com.dsh.phoneact.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.phoneact.core.ActionBackend
import com.dsh.phoneact.core.CaptureBackend
import com.dsh.phoneact.core.Prefs
import com.dsh.phoneact.ui.SectionCard

@Composable
fun SettingsScreen() {
    val s by Prefs.flow.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("识别", "分块识别的判定参数，改动立即生效") {
            SwitchRow("屏幕更新即识别", "每次画面变化自动完整识别一次（帧差分 + 无障碍事件）", s.recognizeOnChange) { v ->
                Prefs.update { it.copy(recognizeOnChange = v) }
            }
            SwitchRow("分块：状态栏", "把状态栏单独作为一个识别块", s.splitStatusBar) { v ->
                Prefs.update { it.copy(splitStatusBar = v) }
            }
            SwitchRow("分块：导航栏", "把底部导航栏单独作为一个识别块", s.splitNavBar) { v ->
                Prefs.update { it.copy(splitNavBar = v) }
            }
            SwitchRow("启用 OCR", "使用 ML Kit 中文模型识别文字（关闭后仅做颜色不一致检测）", s.ocrEnabled) { v ->
                Prefs.update { it.copy(ocrEnabled = v) }
            }
            SwitchRow("只保留含文字的元素", "严格按识别依据过滤：颜色不一致 + 有文字", s.keepOnlyTextElements) { v ->
                Prefs.update { it.copy(keepOnlyTextElements = v) }
            }
            Spacer(Modifier.height(8.dp))
            SliderRow("颜色不一致阈值", "${s.colorDeltaThreshold}", s.colorDeltaThreshold.toFloat(), 4f..160f) { v ->
                Prefs.update { it.copy(colorDeltaThreshold = v.toInt()) }
            }
            SliderRow("最小元素面积(px²)", "${s.minElementArea}", s.minElementArea.toFloat(), 20f..5000f) { v ->
                Prefs.update { it.copy(minElementArea = v.toInt()) }
            }
            SliderRow("分析降采样倍数", "${s.analyzeScale}x", s.analyzeScale.toFloat(), 1f..6f) { v ->
                Prefs.update { it.copy(analyzeScale = v.toInt()) }
            }
            SliderRow("识别去抖", "${s.debounceMs} ms", s.debounceMs.toFloat(), 50f..2000f) { v ->
                Prefs.update { it.copy(debounceMs = v.toLong()) }
            }
            SliderRow("最大元素数", "${s.maxElements}", s.maxElements.toFloat(), 20f..400f) { v ->
                Prefs.update { it.copy(maxElements = v.toInt()) }
            }
        }

        SectionCard("分块边界覆盖", "默认自动读取系统 status_bar_height / navigation_bar_height") {
            SliderRow("状态栏高度", if (s.statusBarHeightOverride > 0) "${s.statusBarHeightOverride} px" else "自动", (if (s.statusBarHeightOverride > 0) s.statusBarHeightOverride else 0).toFloat(), 0f..240f) { v ->
                Prefs.update { it.copy(statusBarHeightOverride = if (v < 4f) -1 else v.toInt()) }
            }
            SliderRow("导航栏高度", if (s.navBarHeightOverride > 0) "${s.navBarHeightOverride} px" else "自动", (if (s.navBarHeightOverride > 0) s.navBarHeightOverride else 0).toFloat(), 0f..240f) { v ->
                Prefs.update { it.copy(navBarHeightOverride = if (v < 4f) -1 else v.toInt()) }
            }
        }

        SectionCard("操作通道", null) {
            Text("执行点击/滑动/输入时优先使用的后端", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionBackend.entries.forEach { b ->
                    FilterChip(
                        selected = s.actionBackend == b,
                        onClick = { Prefs.update { it.copy(actionBackend = b) } },
                        label = {
                            Text(
                                when (b) {
                                    ActionBackend.AUTO -> "自动择优"
                                    ActionBackend.ACCESSIBILITY -> "无障碍手势"
                                    ActionBackend.ROOT -> "Root input"
                                }
                            )
                        },
                    )
                }
            }
        }

        SectionCard("截屏通道", null) {
            Text("识别前的取帧方式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureBackend.entries.forEach { b ->
                    FilterChip(
                        selected = s.captureBackend == b,
                        onClick = { Prefs.update { it.copy(captureBackend = b) } },
                        label = {
                            Text(
                                when (b) {
                                    CaptureBackend.AUTO -> "自动"
                                    CaptureBackend.ACCESSIBILITY -> "无障碍"
                                    CaptureBackend.ROOT -> "Root"
                                    CaptureBackend.MEDIA_PROJECTION -> "录屏"
                                }
                            )
                        },
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                Prefs.update {
                    it.copy(
                        colorDeltaThreshold = 42,
                        minElementArea = 120,
                        analyzeScale = 2,
                        ocrEnabled = true,
                        splitStatusBar = true,
                        splitNavBar = true,
                        keepOnlyTextElements = false,
                        statusBarHeightOverride = -1,
                        navBarHeightOverride = -1,
                        debounceMs = 350,
                        maxElements = 120,
                        recognizeOnChange = true,
                        actionBackend = ActionBackend.AUTO,
                        captureBackend = CaptureBackend.AUTO,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("恢复默认参数") }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(valueText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

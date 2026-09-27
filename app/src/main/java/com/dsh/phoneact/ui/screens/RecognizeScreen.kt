package com.dsh.phoneact.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dsh.phoneact.core.ElementSource
import com.dsh.phoneact.core.FrameHub
import com.dsh.phoneact.core.Recognizer
import com.dsh.phoneact.core.ScreenElement
import com.dsh.phoneact.core.ScreenModel
import com.dsh.phoneact.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecognizeScreen() {
    val scope = rememberCoroutineScope()
    val preview by FrameHub.preview.collectAsState()
    val model by FrameHub.model.collectAsState()
    var annotate by remember { mutableStateOf(true) }
    var region by remember { mutableStateOf("all") }
    var annotated by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val elements = remember(model, region) {
        val m = model ?: return@remember emptyList()
        if (region == "all") m.elements else m.blocks.firstOrNull { it.id == region }?.elements ?: emptyList()
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(
                "实时画面",
                model?.let { "第 ${it.seq} 次识别 · ${it.width}x${it.height} · ${it.totalMs}ms · 截屏 ${it.captureBackend}" }
                    ?: "尚无识别结果，点击下方按钮触发一次",
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.5f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val shown = if (annotate && annotated != null) annotated else preview
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "屏幕预览",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("等待首帧…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = annotate, onCheckedChange = { annotate = it })
                    Spacer(Modifier.padding(4.dp))
                    Text("显示识别标注框", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val m = withContext(Dispatchers.Default) { FrameHub.recognizeNow(true) }
                            if (m != null) {
                                val src = FrameHub.preview.value
                                annotated = if (src != null) withContext(Dispatchers.Default) { Recognizer.overlay(src, m) } else null
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("重新识别")
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部", "status_bar" to "状态栏", "main" to "主体", "nav_bar" to "导航栏").forEach { (id, label) ->
                    AssistChip(
                        onClick = { region = id },
                        label = { Text(label) },
                        colors = if (region == id) {
                            AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }

        item {
            Text(
                "共 ${elements.size} 个元素 · 判定依据：与背景颜色不一致（Δ≥阈值）且含有文字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(elements, key = { it.id }) { e ->
            ElementCard(e)
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ElementCard(e: ScreenElement) {
    val accent = when (e.source) {
        ElementSource.OCR -> Color(0xFF34D399)
        ElementSource.ACCESSIBILITY -> Color(0xFF60A5FA)
        ElementSource.COLOR -> Color(0xFFFBBF24)
    }
    SectionCard(
        e.text.ifBlank { "(无文字组件 / 图标)" },
        subtitle = "${e.id} · ${e.blockId} · 来源 " + when (e.source) {
            ElementSource.OCR -> "OCR 文字"
            ElementSource.ACCESSIBILITY -> "无障碍节点"
            ElementSource.COLOR -> "纯颜色块"
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "中心 (${e.bounds.centerX}, ${e.bounds.centerY})",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "[${e.bounds.left},${e.bounds.top},${e.bounds.right},${e.bounds.bottom}]",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("颜色不一致度 ${e.colorDelta}", style = MaterialTheme.typography.labelSmall)
            Text("位置 ${e.position}", style = MaterialTheme.typography.labelSmall)
            Text(if (e.clickable) "可点击" else "静态", style = MaterialTheme.typography.labelSmall)
        }
    }
}

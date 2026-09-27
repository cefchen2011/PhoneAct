package com.dsh.phoneact.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dsh.phoneact.core.ActCore
import com.dsh.phoneact.core.Lg
import com.dsh.phoneact.core.Prefs
import com.dsh.phoneact.mcp.McpServer
import com.dsh.phoneact.mcp.McpTools
import com.dsh.phoneact.ui.KeyValueRow
import com.dsh.phoneact.ui.SectionCard
import com.dsh.phoneact.ui.StatusRow
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ServerScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val state by ActCore.state.collectAsState()
    val settings by Prefs.flow.collectAsState()
    val logs by Lg.lines.collectAsState()
    var portText by remember { mutableStateOf(settings.mcpPort.toString()) }

    val tools = remember { McpTools.list() }
    val urls = remember(state.mcpRunning) { if (state.mcpRunning) McpServer.localAddresses() else emptyList() }
    val primary = when {
        !state.mcpRunning -> "—"
        settings.mcpBindAll && urls.isNotEmpty() -> "http://${urls.first()}:${settings.mcpPort}/mcp"
        else -> "http://127.0.0.1:${settings.mcpPort}/mcp"
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("MCP 服务", "Streamable HTTP: POST /mcp · 旧版 SSE: GET /sse + POST /messages") {
                StatusRow("状态", if (state.mcpRunning) "监听中" else "已停止", state.mcpRunning)
                StatusRow("绑定", if (settings.mcpBindAll) "0.0.0.0（局域网可访问）" else "127.0.0.1（仅本机）", true)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        primary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(primary)) }) {
                        Icon(Icons.Filled.ContentCopy, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("复制")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            Prefs.update { it.copy(mcpEnabled = true) }
                            scope.launch { ActCore.startMcp() }
                        },
                        enabled = !state.mcpRunning,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("启动服务")
                    }
                    OutlinedButton(
                        onClick = {
                            Prefs.update { it.copy(mcpEnabled = false) }
                            ActCore.stopMcp()
                        },
                        enabled = state.mcpRunning,
                    ) {
                        Icon(Icons.Filled.Stop, null)
                        Spacer(Modifier.padding(4.dp))
                        Text("停止")
                    }
                }
            }
        }

        item {
            SectionCard("连接参数", null) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(
                        checked = settings.mcpBindAll,
                        onCheckedChange = { v -> Prefs.update { it.copy(mcpBindAll = v) } },
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text("允许局域网访问", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = {
                    val p = portText.toIntOrNull()?.coerceIn(1024, 65535) ?: 8517
                    Prefs.update { it.copy(mcpPort = p) }
                    if (state.mcpRunning) {
                        ActCore.stopMcp()
                        scope.launch { ActCore.startMcp() }
                    }
                }) { Text("应用并重启服务") }
                Spacer(Modifier.height(10.dp))
                Text("访问令牌（局域网连接需带 Authorization: Bearer …）", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        settings.mcpAuthToken,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(settings.mcpAuthToken)) }) {
                        Text("复制")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { Prefs.regenerateToken() }) {
                        Text("重置")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "客户端配置：\n{ \"mcpServers\": { \"phoneact\": { \"url\": \"$primary\" } } }",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard("工具清单（${tools.length()} 个）", "模型可调用的全部 MCP 工具") {
                Column {
                    for (i in 0 until tools.length()) {
                        val t: JSONObject = tools.optJSONObject(i) ?: continue
                        KeyValueRow(t.optString("name"), "", accent = true)
                        Text(
                            t.optString("description").take(76),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        item {
            SectionCard("运行日志（最近 ${logs.size} 行）", if (McpServer.running) "最近请求：${McpServer.statusJson().optLong("requests")} 次" else null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(8.dp)
                ) {
                    Text(
                        logs.takeLast(60).joinToString("\n").ifBlank { "暂无日志" },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { Lg.clear() }) { Text("清空日志") }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

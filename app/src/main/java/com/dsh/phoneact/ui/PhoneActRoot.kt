package com.dsh.phoneact.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.dsh.phoneact.ui.screens.HomeScreen
import com.dsh.phoneact.ui.screens.RecognizeScreen
import com.dsh.phoneact.ui.screens.ServerScreen
import com.dsh.phoneact.ui.screens.SettingsScreen

private data class Dest(
    val label: String,
    val icon: ImageVector,
    val iconOff: ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneActRoot() {
    val dests = remember {
        listOf(
            Dest("总览", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
            Dest("识别", Icons.Filled.CenterFocusStrong, Icons.Outlined.CenterFocusStrong),
            Dest("MCP", Icons.Filled.Hub, Icons.Outlined.Hub),
            Dest("设置", Icons.Filled.Tune, Icons.Outlined.Tune),
        )
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 状态轮询：GUI 在前台时保持 Root/无障碍/Xposed/MCP 指示实时准确
    LaunchedEffect(Unit) {
        while (true) {
            com.dsh.phoneact.core.ActCore.refresh()
            kotlinx.coroutines.delay(1500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "PhoneAct · " + dests[tab].label,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                dests.forEachIndexed { i, d ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(if (tab == i) d.icon else d.iconOff, contentDescription = d.label) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> HomeScreen()
                1 -> RecognizeScreen()
                2 -> ServerScreen()
                else -> SettingsScreen()
            }
        }
    }
}

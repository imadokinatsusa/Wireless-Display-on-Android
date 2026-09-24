package com.whalecast.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whalecast.app.DemoUiState
import com.whalecast.app.LoopbackDemoViewModel
import com.whalecast.session.StatsSnapshot

private enum class AppScreen { Home, Sender, Receiver, LoopbackDemo }

/**
 * 应用入口：单 APK、双角色（发送端 / 接收端），外加一个不联网的协议环回 Demo。
 */
@Composable
fun WhaleCastApp() {
    var screen by remember { mutableStateOf(AppScreen.Home) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        when (screen) {
            AppScreen.Home -> HomeScreen(
                onSender = { screen = AppScreen.Sender },
                onReceiver = { screen = AppScreen.Receiver },
                onLoopbackDemo = { screen = AppScreen.LoopbackDemo },
            )

            AppScreen.Sender -> SenderScreen(onBack = { screen = AppScreen.Home })
            AppScreen.Receiver -> ReceiverScreen(onBack = { screen = AppScreen.Home })
            AppScreen.LoopbackDemo -> LoopbackDemoScreen(onBack = { screen = AppScreen.Home })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    onSender: () -> Unit,
    onReceiver: () -> Unit,
    onLoopbackDemo: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("WhaleCast") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Android ↔ Android 局域网投屏", style = MaterialTheme.typography.titleMedium)

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("我是发送端", fontWeight = FontWeight.Bold)
                    Text("把本机屏幕投给另一台设备。典型场景：手机投到平板或电视。")
                    Button(onClick = onSender) { Text("进入发送端") }
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("我是接收端", fontWeight = FontWeight.Bold)
                    Text("显示另一台设备投过来的屏幕。典型场景：平板 / 电视当显示器。")
                    Button(onClick = onReceiver) { Text("进入接收端") }
                }
            }

            Text(
                "两台设备需连同一个 Wi-Fi。发送端首次投屏会弹出系统授权框，必须允许才能采集屏幕。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(onClick = onLoopbackDemo) {
                Text("协议环回 Demo（不联网，本地验证切包/重组）")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoopbackDemoScreen(
    onBack: () -> Unit,
    viewModel: LoopbackDemoViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("协议环回 Demo") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenPanel(
                title = "发送端 Sender",
                subtitle = "合成视频源产出 · 第 ${state.senderFrameSeq} 帧",
                color = state.senderColor,
            )
            ScreenPanel(
                title = "接收端 Receiver",
                subtitle = "经切包 → 环回 → 重组后渲染 · 第 ${state.receiverFrameSeq} 帧",
                color = state.receiverColor,
            )
            ControlsPanel(state = state, viewModel = viewModel)
            StatsPanel(state = state)
            Text(
                text = state.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScreenPanel(title: String, subtitle: String, color: Int) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color(color)),
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ControlsPanel(state: DemoUiState, viewModel: LoopbackDemoViewModel) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { if (state.running) viewModel.stop() else viewModel.start() },
                ) {
                    Text(if (state.running) "停止" else "开始")
                }
                Text(if (state.running) "运行中" else "已停止")
            }
            Text("丢包率：${(state.dropRate * 100).toInt()}%（观察接收端丢帧计数上升）")
            Slider(
                value = state.dropRate,
                onValueChange = { viewModel.setDropRate(it) },
                valueRange = 0f..0.5f,
            )
            Text("单程延迟：${state.latencyMillis} ms（观察画面滞后但不丢帧）")
            Slider(
                value = state.latencyMillis.toFloat(),
                onValueChange = { viewModel.setLatency(it.toLong()) },
                valueRange = 0f..300f,
            )
        }
    }
}

@Composable
private fun StatsPanel(state: DemoUiState) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("会话统计（SessionStats）", fontWeight = FontWeight.Bold)
            StatsRow("发送端", state.senderStats)
            StatsRow("接收端", state.receiverStats)
            Text(
                text = "丢帧率：${"%.1f".format(state.receiverStats.frameDropRate * 100)}%",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatsRow(label: String, stats: StatsSnapshot) {
    Text(
        text = "$label　发出帧 ${stats.framesSent}｜收到帧 ${stats.framesReceived}｜丢帧 ${stats.framesDropped}" +
            "｜包 ${stats.packetsSent}→${stats.packetsReceived}｜畸形包 ${stats.malformedPackets}｜发送失败 ${stats.sendFailures}",
        style = MaterialTheme.typography.bodySmall,
    )
}

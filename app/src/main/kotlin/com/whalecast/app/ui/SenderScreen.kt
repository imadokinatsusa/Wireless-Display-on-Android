package com.whalecast.app.ui

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whalecast.app.CaptureSpec
import com.whalecast.app.CastSenderEngine
import com.whalecast.session.StatsSnapshot
import com.whalecast.transport.DEFAULT_CAST_PORT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 发送端界面：填接收端地址 → 授权屏幕录制 → 开始投屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(DEFAULT_CAST_PORT.toString()) }
    var status by remember { mutableStateOf("填写接收端显示的 IP 与端口，然后点「开始投屏」。") }
    var running by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf<CastSenderEngine?>(null) }
    var stats by remember { mutableStateOf(StatsSnapshot()) }

    val spec = remember { CaptureSpec.from(context.resources.displayMetrics) }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(result.resultCode, data)
            val port = portText.toIntOrNull() ?: DEFAULT_CAST_PORT
            val newEngine = CastSenderEngine(scope, projection, spec)
            engine = newEngine
            running = true
            scope.launch {
                runCatching {
                    newEngine.start(host.trim(), port) { status = it }
                }.onFailure { error ->
                    status = "启动失败：${error.message ?: error::class.java.simpleName}"
                    running = false
                    engine = null
                }
            }
        } else {
            status = "已取消屏幕录制授权，无法投屏。"
        }
    }

    LaunchedEffect(running) {
        while (running) {
            engine?.let { stats = it.sessionStats.snapshot.value }
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发送端") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("接收端地址", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("IP，例如 192.168.1.23") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { value -> portText = value.filter { it.isDigit() } },
                        label = { Text("端口") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "采集规格：${spec.width}×${spec.height} @30fps（长边上限 1280，16 对齐）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = {
                    if (running) {
                        scope.launch {
                            engine?.stop()
                            engine = null
                            running = false
                            status = "已停止投屏。"
                        }
                    } else if (host.isBlank()) {
                        status = "请先填写接收端的 IP 地址。"
                    } else {
                        val manager = context.getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(manager.createScreenCaptureIntent())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "停止投屏" else "开始投屏")
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("状态", fontWeight = FontWeight.Bold)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    val capture = engine?.captureStats()
                    Text(
                        "发出帧 ${stats.framesSent}｜包 ${stats.packetsSent}｜已发 ${stats.bytesSent / 1024} KB" +
                            "｜编码丢帧 ${capture?.second ?: 0}｜目标码率 ${(capture?.third ?: 0) / 1000} kbps",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (stats.sendFailures > 0) {
                        Text("发送失败 ${stats.sendFailures} 次", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text(
                "提示：接收端界面上会显示它自己的 IP 与端口；两台设备必须在同一 Wi-Fi 下。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

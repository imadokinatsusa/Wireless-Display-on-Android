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
import androidx.compose.runtime.DisposableEffect
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
import com.whalecast.discovery.BeaconScanner
import com.whalecast.discovery.ConnectCode
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

    var codeInput by remember { mutableStateOf("") }
    var scanner by remember { mutableStateOf<BeaconScanner?>(null) }
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
                // 优先用连接码在局域网里找接收端；找不到再退回手动 IP
                val targetHost = if (codeInput.isNotBlank() && host.isBlank()) {
                    status = "正在按连接码 ${ConnectCode.pretty(codeInput)} 搜索接收端…"
                    val found = scanner?.resolve(codeInput)
                    if (found == null) {
                        status = "没找到这台接收端：确认两台设备在同一 Wi-Fi，或改用手动 IP。"
                        running = false
                        engine = null
                        return@launch
                    }
                    status = "已找到 ${found.beacon.deviceName}（${found.endpoint}），正在连接…"
                    found.host
                } else {
                    host.trim()
                }
                runCatching {
                    newEngine.start(targetHost, port) { status = it }
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

    // 离开界面停掉扫描，避免后台一直占着 UDP 端口
    DisposableEffect(Unit) {
        onDispose { scanner?.stop() }
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
                    Text("连接码（接收端屏幕上的 6 位码）", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = ConnectCode.normalize(it) },
                        label = { Text("例如 ABC-234") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("自动发现不可用时，可在下面手动填 IP", style = MaterialTheme.typography.bodySmall)
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
                    } else if (codeInput.isBlank() && host.isBlank()) {
                        status = "请输入接收端屏幕上的连接码（或手动填 IP）。"
                    } else {
                        // 扫描器常驻：连接码每秒都在广播，需要时直接 resolve
                        if (scanner == null) {
                            scanner = BeaconScanner(scope).also { it.start() }
                        }
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
                "提示：接收端会显示 6 位连接码并广播到局域网；两台设备必须在同一 Wi-Fi 下" +
                    "（若路由器开了 AP 隔离，请改用手动 IP）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
import com.whalecast.app.CastForegroundService
import com.whalecast.app.CastSenderEngine
import com.whalecast.app.acquireMulticastLock
import com.whalecast.discovery.BeaconScanner
import com.whalecast.discovery.ConnectCode
import com.whalecast.discovery.DiscoveredDevice
import com.whalecast.session.StatsSnapshot
import com.whalecast.transport.DEFAULT_CAST_PORT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 发送端：**进来就自动扫描**，把发现的接收端直接列出来，点一下就投屏。
 *
 * 搜不到时才退回"输入连接码"，而且不再需要用户填 IP、端口、采集参数 ——
 * 那些都由 [CaptureSpec] 与默认端口决定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf<Map<String, DiscoveredDevice>>(emptyMap()) }
    var status by remember { mutableStateOf("正在搜索同一 Wi-Fi 下的接收端…") }
    var running by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf<CastSenderEngine?>(null) }
    var stats by remember { mutableStateOf(StatsSnapshot()) }
    var manualCode by remember { mutableStateOf("") }
    var pendingHost by remember { mutableStateOf<String?>(null) }

    val spec = remember { CaptureSpec.from(context.resources.displayMetrics) }
    val scanner = remember { BeaconScanner(scope) }

    // 不持这个锁，Wi-Fi 芯片会把广播包全部丢掉（真机上"搜不到接收端"的主因）
    val multicastLock = remember { acquireMulticastLock(context) }

    LaunchedEffect(Unit) {
        scanner.start()
        scanner.devices.collect { map ->
            devices = map
            if (!running) {
                status = if (map.isEmpty()) {
                    "正在搜索同一 Wi-Fi 下的接收端…"
                } else {
                    "发现 ${map.size} 台，点一下就开始投屏"
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scanner.stop()
            runCatching { multicastLock?.release() }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val host = pendingHost
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            status = "已取消屏幕录制授权"
            pendingHost = null
            return@rememberLauncherForActivityResult
        }
        if (host.isNullOrBlank()) {
            status = "没有目标设备"
            return@rememberLauncherForActivityResult
        }
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            status = "系统没有提供投屏服务"
            return@rememberLauncherForActivityResult
        }
        val projection = runCatching { manager.getMediaProjection(result.resultCode, data) }
            .getOrElse { error ->
                status = "获取投屏授权失败：${error.message ?: error::class.java.simpleName}"
                return@rememberLauncherForActivityResult
            }
        // Android 14+：必须在建虚拟屏之前跑起 mediaProjection 类型的前台服务
        CastForegroundService.start(context)
        val newEngine = CastSenderEngine(scope, projection, spec)
        engine = newEngine
        running = true
        scope.launch {
            // 前台服务是异步启动的：必须等它真正进入前台，否则 createVirtualDisplay 会被系统拒绝
            if (!CastForegroundService.awaitForeground()) {
                status = "前台服务未能进入前台，无法采集屏幕" +
                    (CastForegroundService.foregroundError?.let { "：$it" }
                        ?: "（系统未报异常，可能被后台启动限制）")
                running = false
                engine = null
                CastForegroundService.stop(context)
                return@launch
            }
            runCatching { newEngine.start(host, DEFAULT_CAST_PORT) { status = it } }
                .onFailure { error ->
                    status = "投屏失败：${error.message ?: error::class.java.simpleName}"
                    running = false
                    engine = null
                    CastForegroundService.stop(context)
                }
        }
    }

    fun castTo(host: String) {
        pendingHost = host
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            status = "系统没有提供投屏服务"
            return
        }
        runCatching { launcher.launch(manager.createScreenCaptureIntent()) }
            .onFailure { status = "无法打开授权界面：${it.message ?: it::class.java.simpleName}" }
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
            if (running) {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("正在投屏", fontWeight = FontWeight.Bold)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "发出帧 ${stats.framesSent}｜已发 ${stats.bytesSent / 1024} KB｜${spec.label}" +
                                "｜前台服务 ${if (CastForegroundService.isInForeground) "已就绪" else "未就绪"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    engine?.stop()
                                    engine = null
                                    running = false
                                    CastForegroundService.stop(context)
                                    status = "已停止"
                                }
                            },
                        ) {
                            Text("停止投屏")
                        }
                    }
                }
            } else {
                devices.values.forEach { device ->
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(device.beacon.deviceName, fontWeight = FontWeight.Bold)
                            Text(device.endpoint, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { castTo(device.host) }) { Text("投到这台上") }
                        }
                    }
                }

                if (devices.isEmpty()) {
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("没搜到接收端？", fontWeight = FontWeight.Bold)
                            Text(
                                "确认两台设备连的是同一个 Wi-Fi。也可以直接输入接收端屏幕上的连接码：",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = manualCode,
                                onValueChange = { manualCode = ConnectCode.normalize(it) },
                                label = { Text("例如 ABC-234") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                enabled = ConnectCode.isValid(manualCode),
                                onClick = {
                                    scope.launch {
                                        status = "正在按连接码搜索…"
                                        val found = runCatching { scanner.resolve(manualCode, 6_000) }
                                            .getOrNull()
                                        if (found == null) {
                                            status = "没找到这个码对应的接收端"
                                        } else {
                                            castTo(found.host)
                                        }
                                    }
                                },
                            ) {
                                Text("按码连接")
                            }
                        }
                    }
                }

                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "诊断：扫描端口 ${scanner.localPort}｜发现 ${devices.size} 台" +
                        (scanner.failureReason?.let { "｜扫描异常：$it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

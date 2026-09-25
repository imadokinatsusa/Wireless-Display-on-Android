package com.mirror.cast.ui

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mirror.cast.CaptureSpec
import com.mirror.cast.Discovery
import com.mirror.cast.FailedSession
import com.mirror.cast.HotspotController
import com.mirror.cast.MirrorService
import com.mirror.cast.QualityAdjustable
import com.mirror.cast.SessionRegistry
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.discovery.DiscoveredDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 发送端：选画质 → 选连法 → 点一台接收端 → 系统授权 → 交给前台服务。
 *
 * 两种连法，随时可切（这就是「智能切换」）：
 * - 同一 Wi-Fi（默认）：最省事，但路由器开了 AP/客户端隔离时会搜不到设备；
 * - 发送端热点：由本机开一个"仅本地热点"，接收端连上来，链路必然互通；
 *   代价是两端都会失去外网。搜不到设备时界面会主动提示切过去。
 *
 * 画质档位调的是编码输出（分辨率 + 码率联动），采集始终是屏幕真实尺寸。
 */
@Composable
fun SenderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember(context) { Discovery(context, scope) }
    val devices by discovery.devices.collectAsState()
    val active by SessionRegistry.active.collectAsState()
    var pending: DiscoveredDevice? by remember { mutableStateOf(null) }
    var quality by remember { mutableStateOf(CaptureSpec.DEFAULT_QUALITY) }
    var autoQuality by remember { mutableStateOf(true) }
    val hotspot = remember(context) { HotspotController(context) }
    var hotspotInfo by remember { mutableStateOf<HotspotController.HotspotInfo?>(null) }
    var hotspotError by remember { mutableStateOf<String?>(null) }
    var waitSeconds by remember { mutableIntStateOf(0) }

    val adjustable = active as? QualityAdjustable

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            waitSeconds += 1
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val device = pending
        pending = null
        if (device == null) return@rememberLauncherForActivityResult
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            SessionRegistry.set(FailedSession("你拒绝了屏幕授权"))
            return@rememberLauncherForActivityResult
        }
        val spec = CaptureSpec.from(context.resources.displayMetrics)
        MirrorService.start(
            context = context,
            resultCode = result.resultCode,
            projectionData = data,
            host = device.host,
            signalingPort = device.beacon.tcpPort,
            code = device.beacon.code,
            spec = spec,
            quality = quality,
        )
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    DisposableEffect(Unit) {
        discovery.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onDispose {
            discovery.stop()
            hotspot.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(title = "发送屏幕", onBack = onBack)

        Text(text = "画质（分辨率 + 码率联动）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaptureSpec.Quality.entries.forEach { item ->
                Button(
                    onClick = {
                        quality = item
                        adjustable?.let { target -> scope.launch { target.setQuality(item) } }
                    },
                ) {
                    Text(if (item == quality) "● ${item.label}" else item.label)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = autoQuality,
                onCheckedChange = { enabled ->
                    autoQuality = enabled
                    adjustable?.let { target -> scope.launch { target.setAutoQuality(enabled) } }
                },
            )
            Text(
                text = "按网络状况自动切换画质（丢包/延迟变差就降档，网络好转再升回来）",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(text = "连接方式", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (hotspot.running) {
                        hotspot.stop()
                        hotspotInfo = null
                        hotspotError = null
                    } else {
                        hotspot.start { info, error ->
                            hotspotInfo = info
                            hotspotError = error
                        }
                    }
                },
            ) {
                Text(if (hotspot.running) "关闭热点，回到同一 Wi-Fi" else "开热点（没有共同 Wi-Fi 时用）")
            }
        }
        hotspotInfo?.let { info ->
            Text(
                text = "热点：${info.displayName}",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "密码：${info.password}",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "让接收端在系统设置里连上这个热点，再回到这里点设备。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        hotspotError?.let { error ->
            Text(text = "热点启动失败：$error", style = MaterialTheme.typography.bodySmall)
        }

        Text(text = "接收端设备", style = MaterialTheme.typography.titleSmall)
        val found = devices.values.sortedBy { it.beacon.deviceName }
        if (found.isEmpty()) {
            val hint = when {
                discovery.failureReason != null -> "没搜到设备（${discovery.failureReason}）"
                waitSeconds > 8 && !hotspot.running ->
                    "还没搜到设备 —— 如果两台设备不在同一个 Wi-Fi，试试上面的「开热点」"
                else -> "正在搜索…（已等 ${waitSeconds}s）"
            }
            Text(text = hint, style = MaterialTheme.typography.bodySmall)
        }

        found.forEach { device ->
            Button(
                onClick = {
                    pending = device
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("投给 ${device.beacon.deviceName}（${ConnectCode.pretty(device.beacon.code)}）")
            }
        }

        active?.let { session ->
            val diagnostics by session.diagnostics.collectAsState()
            DiagnosticsBar(text = diagnostics.line())
            Button(
                onClick = { MirrorService.stop(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("停止投屏")
            }
        }
    }
}
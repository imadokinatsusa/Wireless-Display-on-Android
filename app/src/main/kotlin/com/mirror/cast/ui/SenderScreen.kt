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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mirror.cast.CaptureSpec
import com.mirror.cast.Discovery
import com.mirror.cast.FailedSession
import com.mirror.cast.MirrorService
import com.mirror.cast.ResolutionAdjustable
import com.mirror.cast.SessionRegistry
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.discovery.DiscoveredDevice
import kotlinx.coroutines.launch

/**
 * 发送端：扫出同一个 Wi-Fi 下的接收端 → 选画质 → 点一台 → 系统授权 → 交给前台服务。
 *
 * 授权结果**不在这里建虚拟屏**：只把 resultCode + data 交给 [MirrorService]，
 * 由服务先 startForeground 再取 MediaProjection（顺序错了系统直接拒）。
 *
 * 画质档位调的是**编码分辨率**，采集始终是屏幕真实尺寸（Android 14 的要求）。
 * 投屏过程中也能切，卡了就降一档 —— 这是现场最快的救急手段。
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
            maxLongEdge = quality.maxLongEdge,
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
        onDispose { discovery.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(title = "发送屏幕", onBack = onBack)

        Text(text = "画质（只影响编码分辨率，采集始终是屏幕真实尺寸）：", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureSpec.Quality.entries.forEach { item ->
                Button(
                    onClick = {
                        quality = item
                        // 投屏中切换：会话自己会把新档位应用到编码器上
                        (SessionRegistry.active.value as? ResolutionAdjustable)?.let { adjustable ->
                            scope.launch { adjustable.setQuality(item) }
                        }
                    },
                ) {
                    Text(if (item == quality) "● ${item.label}" else item.label)
                }
            }
        }
        Text(
            text = "投屏中也能随时切档，卡了就往左降一档。",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = "同一个 Wi-Fi 下的接收端会出现在下面（接收端要先打开\"我要接收显示\"）。",
            style = MaterialTheme.typography.bodySmall,
        )

        val found = devices.values.sortedBy { it.beacon.deviceName }
        if (found.isEmpty()) {
            Text(
                text = discovery.failureReason?.let { "没搜到设备（$it）" } ?: "正在搜索…",
                style = MaterialTheme.typography.bodySmall,
            )
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

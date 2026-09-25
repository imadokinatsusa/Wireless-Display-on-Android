package com.mirror.cast.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * 发送端。视觉规则：**能图标就图标，文字只在必要处出现**。
 *
 * - 画质 / 帧率 / 码率上限各自一张卡片，用紧凑的 chip 选择；
 * - 四行参数收进「详情」，点一下才展开 —— 常态不占版面；
 * - 连接方式用图标按钮，热点失败时给出路（系统设置）。
 *
 * 带宽分工：**发送端定码率上限**，画质与帧率在其之内调（接收端也能调这两项）。
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
    var frameTier by remember { mutableStateOf(CaptureSpec.DEFAULT_FRAME_RATE_TIER) }
    var bitrateTier by remember { mutableStateOf(CaptureSpec.DEFAULT_BITRATE_TIER) }
    var autoQuality by remember { mutableStateOf(true) }
    var showDetails by remember { mutableStateOf(false) }
    val hotspot = remember(context) { HotspotController(context) }
    var hotspotInfo by remember { mutableStateOf<HotspotController.HotspotInfo?>(null) }
    var hotspotError by remember { mutableStateOf<String?>(null) }
    var waitSeconds by remember { mutableIntStateOf(0) }

    val adjustable = active as? QualityAdjustable
    val displayHz = remember { context.displayRefreshRate() }
    val spec = remember { CaptureSpec.from(context.resources.displayMetrics) }
    val fps = CaptureSpec.resolveFps(frameTier, displayHz)
    val (encodeWidth, encodeHeight) = CaptureSpec.encodeSize(spec.width, spec.height, quality.maxLongEdge)
    val budget = if (bitrateTier.kbps > 0) bitrateTier.kbps * 1000 else Int.MAX_VALUE
    val totalBitRate = minOf(quality.maxBitrate, CaptureSpec.bitRateFor(encodeWidth, encodeHeight, fps), budget)

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
        MirrorService.start(
            context = context,
            resultCode = result.resultCode,
            projectionData = data,
            host = device.host,
            signalingPort = device.beacon.tcpPort,
            code = device.beacon.code,
            spec = spec,
            quality = quality,
            frameRate = fps,
            bitrateKbps = bitrateTier.kbps,
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
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(title = "发送屏幕", onBack = onBack)

        SettingCard(icon = Icons.Filled.Bolt, title = "码率上限") {
            OptionChips(
                labels = CaptureSpec.BitrateTier.entries.map { it.label },
                selectedIndex = CaptureSpec.BitrateTier.entries.indexOf(bitrateTier),
                onSelect = { index ->
                    val tier = CaptureSpec.BitrateTier.entries[index]
                    bitrateTier = tier
                    adjustable?.let { target -> scope.launch { target.setBitrateLimit(tier.kbps) } }
                },
            )
        }

        SettingCard(icon = Icons.Filled.Settings, title = "画质") {
            OptionChips(
                labels = CaptureSpec.Quality.entries.map { it.label },
                selectedIndex = CaptureSpec.Quality.entries.indexOf(quality),
                onSelect = { index ->
                    val item = CaptureSpec.Quality.entries[index]
                    quality = item
                    adjustable?.let { target -> scope.launch { target.setQuality(item) } }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autoQuality,
                    onCheckedChange = { enabled ->
                        autoQuality = enabled
                        adjustable?.let { target -> scope.launch { target.setAutoQuality(enabled) } }
                    },
                )
                Text(
                    text = "按网络自动切换",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingCard(icon = Icons.Filled.PlayArrow, title = "帧率") {
            OptionChips(
                labels = CaptureSpec.FrameRateTier.entries.map { tier ->
                    if (tier == CaptureSpec.FrameRateTier.FollowDisplay) "${displayHz.toInt()}Hz" else tier.label
                },
                selectedIndex = CaptureSpec.FrameRateTier.entries.indexOf(frameTier),
                onSelect = { index ->
                    val tier = CaptureSpec.FrameRateTier.entries[index]
                    frameTier = tier
                    val target = CaptureSpec.resolveFps(tier, displayHz)
                    adjustable?.let { session -> scope.launch { session.setFrameRate(target) } }
                },
            )
        }

        // 参数详情：默认收起，点图标才展开
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showDetails = !showDetails }) {
                Icon(imageVector = Icons.Filled.Info, contentDescription = "参数详情")
            }
            Text(
                text = "${encodeWidth}×${encodeHeight} @${fps}fps · 约 ${"%.1f".format(totalBitRate / 1_000_000.0)}Mbps",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDetails) {
            Text(
                text = buildString {
                    appendLine("采集 ${spec.width}×${spec.height}（屏幕真实尺寸，不可缩放）")
                    appendLine("编码 ${encodeWidth}×${encodeHeight} @${fps}fps")
                    appendLine("画质 ${quality.label}（档位上限 ${quality.maxBitrate / 1_000_000}Mbps）")
                    appendLine("码率上限 ${if (bitrateTier.kbps > 0) "${bitrateTier.kbps / 1000}Mbps" else "自动"}")
                    append("总码率 约 ${"%.1f".format(totalBitRate / 1_000_000.0)}Mbps")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingCard(icon = Icons.Filled.WifiTethering, title = "连接方式") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = hotspot.running,
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
                        // 开/关热点会切换网络接口：发现必须重新绑定，否则收不到广播
                        discovery.stop()
                        discovery.start()
                    },
                    label = { Text(if (hotspot.running) "热点已开" else "开热点") },
                )
                FilterChip(
                    selected = false,
                    onClick = { openWirelessSettings(context) },
                    label = { Text("系统设置") },
                )
            }
            hotspotInfo?.let { info ->
                Text(
                    text = "${info.displayName} / ${info.password}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            hotspotError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // 设备列表：一行一台，尾巴是播放图标
        val found = devices.values.sortedBy { it.beacon.deviceName }
        if (found.isEmpty()) {
            Text(
                text = when {
                    discovery.failureReason != null -> "没搜到设备（${discovery.failureReason}）"
                    waitSeconds > 8 && !hotspot.running -> "没搜到设备 —— 两台设备不在同一 Wi-Fi 时可开热点"
                    else -> "搜索中…（${waitSeconds}s）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        found.forEach { device ->
            Card(
                onClick = {
                    pending = device
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = device.beacon.deviceName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = ConnectCode.pretty(device.beacon.code),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "投给这台设备")
                }
            }
        }

        // 当前会话：诊断行 + 停止图标
        active?.let { session ->
            val diagnostics by session.diagnostics.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = diagnostics.line(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { MirrorService.stop(context) }) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止投屏",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 一张设置卡片：图标 + 标题 + 内容。 */
@Composable
private fun SettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title,
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            content()
        }
    }
}

/** 一行紧凑的选项 chip。 */
@Composable
private fun OptionChips(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
    }
}

/** 跳到系统的无线设置（热点开不起来时的出路）。 */
private fun openWirelessSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 屏幕刷新率 —— 采集帧率的物理上限。 */
@Suppress("DEPRECATION")
private fun Context.displayRefreshRate(): Float =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.refreshRate ?: DEFAULT_HZ
    } else {
        val manager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        manager?.defaultDisplay?.refreshRate ?: DEFAULT_HZ
    }

private const val DEFAULT_HZ = 60f

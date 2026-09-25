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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiTethering
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
 * 发送端内容（底部页签之一）。
 *
 * **分工**：发送端只管**码率上限** —— 这条链路能花多少带宽是它说了算；
 * 画质与帧率交给**看画面的人**（接收端）在上限之内调。所以这里没有画质/帧率选择。
 *
 * 其余就是必要的东西：网络（热点）、可用设备、当前会话。参数详情默认收起。
 */
@Composable
fun SenderContent(lastCrash: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember(context) { Discovery(context, scope) }
    val devices by discovery.devices.collectAsState()
    val active by SessionRegistry.active.collectAsState()
    var pending: DiscoveredDevice? by remember { mutableStateOf(null) }
    var bitrateTier by remember { mutableStateOf(CaptureSpec.DEFAULT_BITRATE_TIER) }
    var autoQuality by remember { mutableStateOf(true) }
    var showBitrate by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val hotspot = remember(context) { HotspotController(context) }
    var hotspotInfo by remember { mutableStateOf<HotspotController.HotspotInfo?>(null) }
    var hotspotError by remember { mutableStateOf<String?>(null) }
    var waitSeconds by remember { mutableIntStateOf(0) }

    val adjustable = active as? QualityAdjustable
    val spec = remember { CaptureSpec.from(context.resources.displayMetrics) }
    val quality = adjustable?.quality ?: CaptureSpec.DEFAULT_QUALITY
    val fps = adjustable?.frameRate ?: CaptureSpec.DEFAULT_FRAME_RATE
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
            .padding(horizontal = 16.dp),
    ) {
        MirrorTopBar(title = "发送屏幕")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("带宽") {
                SettingsRow(
                    icon = Icons.Filled.Bolt,
                    iconTint = IconTints.red,
                    title = "码率上限",
                    subtitle = "画质与帧率由接收端在此预算内调整",
                    value = if (bitrateTier.kbps > 0) "${bitrateTier.kbps / 1000}Mbps" else "自动",
                    showDivider = showBitrate,
                    onClick = { showBitrate = !showBitrate },
                )
                if (showBitrate) {
                    CaptureSpec.BitrateTier.entries.forEachIndexed { index, tier ->
                        CheckRow(
                            title = tier.label,
                            checked = tier == bitrateTier,
                            showDivider = index < CaptureSpec.BitrateTier.entries.lastIndex,
                        ) {
                            bitrateTier = tier
                            showBitrate = false
                            adjustable?.let { target -> scope.launch { target.setBitrateLimit(tier.kbps) } }
                        }
                    }
                }
            }
            GroupSpacer()

            SettingsGroup("网络") {
                SettingsRow(
                    icon = Icons.Filled.Speed,
                    iconTint = IconTints.green,
                    title = "自动画质",
                    subtitle = "丢包或延迟变差时自动降档",
                    trailing = {
                        Switch(
                            checked = autoQuality,
                            onCheckedChange = { enabled ->
                                autoQuality = enabled
                                adjustable?.let { target -> scope.launch { target.setAutoQuality(enabled) } }
                            },
                        )
                    },
                )
                SettingsRow(
                    icon = Icons.Filled.WifiTethering,
                    iconTint = IconTints.blue,
                    title = "发送端热点",
                    subtitle = hotspotError
                        ?: hotspotInfo?.let { "${it.displayName} / ${it.password}" }
                        ?: "两台设备不在同一 Wi-Fi 时用",
                    value = if (hotspot.running) "已开" else null,
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
                )
                SettingsRow(
                    icon = Icons.Filled.Settings,
                    iconTint = ColorGray,
                    title = "系统无线设置",
                    subtitle = "热点被系统拒绝时去这里手动开",
                    showDivider = false,
                    onClick = { openWirelessSettings(context) },
                )
            }
            GroupSpacer()

            SettingsGroup("可用设备") {
                val found = devices.values.sortedBy { it.beacon.deviceName }
                if (found.isEmpty()) {
                    SettingsRow(
                        icon = Icons.Filled.PhoneAndroid,
                        iconTint = ColorGray,
                        title = "搜索中…",
                        subtitle = when {
                            discovery.failureReason != null -> "没搜到设备（${discovery.failureReason}）"
                            waitSeconds > 8 && !hotspot.running -> "没搜到？两台设备不在同一 Wi-Fi 时可开热点"
                            else -> "已等 ${waitSeconds}s"
                        },
                        showDivider = false,
                    )
                } else {
                    found.forEachIndexed { index, device ->
                        SettingsRow(
                            icon = Icons.Filled.PhoneAndroid,
                            iconTint = IconTints.green,
                            title = device.beacon.deviceName,
                            subtitle = ConnectCode.pretty(device.beacon.code),
                            showDivider = index < found.lastIndex,
                            onClick = {
                                pending = device
                                val manager = context.getSystemService(MediaProjectionManager::class.java)
                                projectionLauncher.launch(manager.createScreenCaptureIntent())
                            },
                        )
                    }
                }
            }

            active?.let { session ->
                val diagnostics by session.diagnostics.collectAsState()
                GroupSpacer()
                SettingsGroup("当前投屏") {
                    SettingsRow(
                        icon = Icons.Filled.Stop,
                        iconTint = IconTints.red,
                        title = "停止投屏",
                        subtitle = diagnostics.line(),
                        showDivider = false,
                        onClick = { MirrorService.stop(context) },
                    )
                }
            }

            if (lastCrash != null) {
                GroupSpacer()
                SettingsGroup("上次崩溃") {
                    Text(
                        text = lastCrash.take(1000),
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 参数详情：一行摘要，点开才展开
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showDetails = !showDetails }) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "参数详情",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        appendLine("画质 ${quality.label}（由接收端选择）")
                        appendLine("码率上限 ${if (bitrateTier.kbps > 0) "${bitrateTier.kbps / 1000}Mbps" else "自动"}")
                        append("总码率 约 ${"%.1f".format(totalBitRate / 1_000_000.0)}Mbps")
                    },
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private val ColorGray = androidx.compose.ui.graphics.Color(0xFF8E8E93)

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

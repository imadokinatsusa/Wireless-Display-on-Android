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
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
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
 * 发送端：Inset Grouped 卡片列表。
 *
 * - 每组一行：左侧彩色圆角图标块、中间标题、右侧当前值与箭头；
 * - 点行展开可勾选项（iOS 的选择方式），选完自动收起；
 * - 四行参数收进「详细信息」，默认不占版面；
 * - 热点开不起来时给出路（系统设置）。
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
    var expanded by remember { mutableStateOf(Section.None) }
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
            .padding(horizontal = 16.dp),
    ) {
        MirrorTopBar(title = "发送屏幕", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("画质") {
                SettingsRow(
                    icon = Icons.Filled.PhotoSizeSelectLarge,
                    iconTint = IconTints.purple,
                    title = "分辨率与码率",
                    value = quality.label,
                    showDivider = expanded == Section.Quality,
                    onClick = { expanded = if (expanded == Section.Quality) Section.None else Section.Quality },
                )
                if (expanded == Section.Quality) {
                    CaptureSpec.Quality.entries.forEachIndexed { index, item ->
                        CheckRow(
                            title = "${item.label} · 上限 ${item.maxBitrate / 1_000_000}Mbps",
                            checked = item == quality,
                            showDivider = index < CaptureSpec.Quality.entries.lastIndex,
                        ) {
                            quality = item
                            expanded = Section.None
                            adjustable?.let { target -> scope.launch { target.setQuality(item) } }
                        }
                    }
                }
            }
            GroupSpacer()

            SettingsGroup("帧率") {
                SettingsRow(
                    icon = Icons.Filled.Speed,
                    iconTint = IconTints.orange,
                    title = "目标帧率",
                    subtitle = "上限为本机屏幕 ${displayHz.toInt()}Hz",
                    value = "${fps}fps",
                    showDivider = expanded == Section.FrameRate,
                    onClick = { expanded = if (expanded == Section.FrameRate) Section.None else Section.FrameRate },
                )
                if (expanded == Section.FrameRate) {
                    CaptureSpec.FrameRateTier.entries.forEachIndexed { index, tier ->
                        val target = CaptureSpec.resolveFps(tier, displayHz)
                        CheckRow(
                            title = if (tier == CaptureSpec.FrameRateTier.FollowDisplay) {
                                "跟随屏幕（${displayHz.toInt()}Hz）"
                            } else {
                                tier.label
                            },
                            checked = tier == frameTier,
                            showDivider = index < CaptureSpec.FrameRateTier.entries.lastIndex,
                        ) {
                            frameTier = tier
                            expanded = Section.None
                            adjustable?.let { session -> scope.launch { session.setFrameRate(target) } }
                        }
                    }
                }
            }
            GroupSpacer()

            SettingsGroup("码率上限") {
                SettingsRow(
                    icon = Icons.Filled.Bolt,
                    iconTint = IconTints.red,
                    title = "带宽预算",
                    subtitle = "由发送端决定，接收端在此之内调画质",
                    value = if (bitrateTier.kbps > 0) "${bitrateTier.kbps / 1000}Mbps" else "自动",
                    showDivider = expanded == Section.Bitrate,
                    onClick = { expanded = if (expanded == Section.Bitrate) Section.None else Section.Bitrate },
                )
                if (expanded == Section.Bitrate) {
                    CaptureSpec.BitrateTier.entries.forEachIndexed { index, tier ->
                        CheckRow(
                            title = tier.label,
                            checked = tier == bitrateTier,
                            showDivider = index < CaptureSpec.BitrateTier.entries.lastIndex,
                        ) {
                            bitrateTier = tier
                            expanded = Section.None
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
                    iconTint = Color_Gray,
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
                        iconTint = Color_Gray,
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

            // 参数详情：默认收起
            Spacer(modifier = Modifier.height(14.dp))
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
                        appendLine("画质 ${quality.label}（档位上限 ${quality.maxBitrate / 1_000_000}Mbps）")
                        appendLine("码率上限 ${if (bitrateTier.kbps > 0) "${bitrateTier.kbps / 1000}Mbps" else "自动"}")
                        append("总码率 约 ${"%.1f".format(totalBitRate / 1_000_000.0)}Mbps")
                    },
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 当前会话
            active?.let { session ->
                val diagnostics by session.diagnostics.collectAsState()
                Spacer(modifier = Modifier.height(16.dp))
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

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

private enum class Section { None, Quality, FrameRate, Bitrate }

private val Color_Gray = androidx.compose.ui.graphics.Color(0xFF8E8E93)

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

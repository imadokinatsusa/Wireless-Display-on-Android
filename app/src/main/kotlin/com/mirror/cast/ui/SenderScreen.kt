package com.mirror.cast.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
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
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mirror.cast.CaptureSpec
import com.mirror.cast.Discovery
import com.mirror.cast.FailedSession
import com.mirror.cast.LocalAddress
import com.mirror.cast.NetworkWatcher
import com.mirror.cast.MirrorService
import com.mirror.cast.p2p.WifiP2pLink
import com.mirror.cast.QualityAdjustable
import com.mirror.cast.SessionRegistry
import com.mirror.cast.discovery.CastLink
import com.mirror.cast.discovery.ConnectCode
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
    // 扫码与广播搜索最终都归结为"往哪儿投"，所以共用同一个待投目标
    var pending: CastRequest? by remember { mutableStateOf(null) }
    var scanHint by remember { mutableStateOf<String?>(null) }
    var wantScan by remember { mutableStateOf(false) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    /** 一次待发起的投屏：弹录屏授权这件事统一在这里做，免得各处重复同一段流程。 */
    var pendingDirect by remember { mutableStateOf<CastRequest?>(null) }

    /**
     * 离线场景用的 Wi-Fi Direct 链路。
     *
     * 扫到带 `p2p=1` 的二维码时，先让系统在两端之间拉一条链路，
     * 拿到群主地址（`192.168.49.1`）之后再照常投屏 —— 上层流程一步都不用改。
     */
    val p2p = remember(context) { WifiP2pLink(context) }
    val p2pStatus by p2p.status.collectAsState()
    var pendingP2p by remember { mutableStateOf<CastRequest?>(null) }
    var bitrateTier by remember { mutableStateOf(CaptureSpec.DEFAULT_BITRATE_TIER) }
    var autoQuality by remember { mutableStateOf(true) }
    var showBitrate by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    // 网络接口一变（切 Wi-Fi、连上接收端开的热点）就重启发现：否则 UDP socket 还绑在旧接口上
    val networkWatcher = remember(context) {
        NetworkWatcher(context) {
            discovery.stop()
            discovery.start()
        }
    }
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
        val target = pending
        pending = null
        if (target == null) return@rememberLauncherForActivityResult
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            SessionRegistry.set(FailedSession("你拒绝了屏幕授权"))
            return@rememberLauncherForActivityResult
        }
        MirrorService.start(
            context = context,
            resultCode = result.resultCode,
            projectionData = data,
            host = target.host,
            signalingPort = target.port,
            code = target.code,
            spec = spec,
            quality = quality,
            frameRate = fps,
            bitrateKbps = bitrateTier.kbps,
        )
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (granted) {
            wantScan = true
        } else {
            scanHint = "没有相机权限，扫不了码"
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) return@rememberLauncherForActivityResult // 用户取消了扫码
        val target = CastLink.decode(contents)
        if (target == null) {
            scanHint = "这不是本应用的投屏二维码"
            return@rememberLauncherForActivityResult
        }
        scanHint = null
        val request = CastRequest(
            host = target.host,
            port = target.port,
            code = target.code,
            deviceName = target.deviceName,
            viaWifiDirect = target.viaWifiDirect,
        )
        if (request.viaWifiDirect) {
            // 离线：先建链路，地址等链路好了再定
            pendingP2p = request
            p2p.start()
            p2p.discover()
        } else {
            pendingDirect = request
        }
    }

    /**
     * 统一弹录屏授权。
     *
     * 走 LaunchedEffect 而不是直接调用：要让"扫码"和"点设备"两条路径共用同一入口，
     * 而它们谁都无法在定义顺序上先于 launcher。
     */
    LaunchedEffect(pendingDirect) {
        val target = pendingDirect ?: return@LaunchedEffect
        pending = target
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
        pendingDirect = null
    }

    // 搜到设备就自动加入对方的组（二维码里带着对方名字）
    LaunchedEffect(p2pStatus.peers, pendingP2p) {
        val target = pendingP2p ?: return@LaunchedEffect
        if (p2pStatus.groupOwnerAddress == null && p2pStatus.peers.isNotEmpty()) {
            p2p.connect(target.deviceName)
        }
    }

    // 链路建好 → 换成群主地址，照常走投屏流程
    LaunchedEffect(p2pStatus.groupOwnerAddress, pendingP2p) {
        val target = pendingP2p ?: return@LaunchedEffect
        val address = p2pStatus.groupOwnerAddress ?: return@LaunchedEffect
        pendingP2p = null
        pendingDirect = target.copy(host = address, viaWifiDirect = false)
    }

    // 相机权限是异步的：拿到之后才由这里真正拉起扫码界面
    LaunchedEffect(wantScan, cameraGranted) {
        if (wantScan && cameraGranted) {
            wantScan = false
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("对准接收端的二维码")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
    }

    DisposableEffect(Unit) {
        discovery.start()
        networkWatcher.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onDispose {
            networkWatcher.stop()
            discovery.stop()
            p2p.stop()
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
                    showDivider = false,
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
            }
            GroupSpacer()

            SettingsGroup("可用设备") {
                SettingsRow(
                    icon = Icons.Filled.QrCodeScanner,
                    iconTint = IconTints.blue,
                    title = "扫码直连",
                    subtitle = p2pStatus.message
                        ?: scanHint
                        ?: "扫接收端屏幕上的二维码，不必等搜索",
                    onClick = {
                        scanHint = null
                        if (cameraGranted) {
                            wantScan = true
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
                val found = devices.values.sortedBy { it.beacon.deviceName }
                if (found.isEmpty()) {
                    SettingsRow(
                        icon = Icons.Filled.PhoneAndroid,
                        iconTint = ColorGray,
                        title = "搜索中…",
                        subtitle = when {
                            discovery.failureReason != null -> "没搜到设备（${discovery.failureReason}）"
                            waitSeconds > 8 -> "没搜到？让接收端点「开热点」，这台连上去就行"
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
                                pendingDirect = CastRequest(
                                    host = device.host,
                                    port = device.beacon.tcpPort,
                                    code = device.beacon.code,
                                    deviceName = device.beacon.deviceName,
                                )
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
                        appendLine("本机地址 ${LocalAddress.summary()}")
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

/**
 * 一次投屏请求。
 *
 * 扫码与广播搜索最终都归结成这四个字段，后面的授权与启动服务完全共用一条路径 ——
 * 这样"扫码直连"不是一个特例分支，而只是另一个来源。
 */
private data class CastRequest(
    val host: String,
    val port: Int,
    val code: String,
    val deviceName: String,
    /** 这个地址要靠 Wi-Fi Direct 才通（离线场景）。 */
    val viaWifiDirect: Boolean = false,
)

private val ColorGray = androidx.compose.ui.graphics.Color(0xFF8E8E93)

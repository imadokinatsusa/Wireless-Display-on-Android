package com.mirror.cast.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import com.mirror.cast.qr.ScanActivity
import com.mirror.cast.CaptureSpec
import com.mirror.cast.FailedSession
import com.mirror.cast.LocalAddress
import com.mirror.cast.MirrorService
import com.mirror.cast.p2p.WifiP2pLink
import com.mirror.cast.QualityAdjustable
import com.mirror.cast.SessionRegistry
import com.mirror.cast.discovery.CastLink
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
    val active by SessionRegistry.active.collectAsState()
    // 扫码拿到的目标 —— 这是**唯一**的连接入口（不再自动搜索设备）
    var pending: CastRequest? by remember { mutableStateOf(null) }
    var scanHint by remember { mutableStateOf<String?>(null) }
    var wantScan by remember { mutableStateOf(false) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    /** 一次待发起的投屏：弹出录屏授权前先把目标存下。 */

    /**
     * 离线场景用的 Wi-Fi Direct 链路。
     *
     * 扫到带 `p2p=1` 的二维码时，先让系统在两端之间拉一条链路，
     * 拿到群主地址（`192.168.49.1`）之后再照常投屏 —— 上层流程一步都不用改。
     */
    val p2p = remember(context) { WifiP2pLink(context) }
    val p2pStatus by p2p.status.collectAsState()
    var pendingP2p by remember { mutableStateOf<CastRequest?>(null) }

    /** Wi-Fi Direct 的运行时权限：Android 13+ 是「附近的设备」，更早的版本是位置。 */
    val p2pPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }
    var p2pGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, p2pPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    // 权限拿到之后再开始搜索 —— 没权限时 discover 只会静默失败
    val p2pPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        p2pGranted = granted
        if (granted && pendingP2p != null) {
            p2p.start()
            p2p.discover()
        }
    }
    var bitrateTier by remember { mutableStateOf(CaptureSpec.DEFAULT_BITRATE_TIER) }
    var autoQuality by remember { mutableStateOf(true) }
    var showBitrate by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    val adjustable = active as? QualityAdjustable
    val spec = remember { CaptureSpec.from(context.resources.displayMetrics) }
    val quality = adjustable?.quality ?: CaptureSpec.DEFAULT_QUALITY
    val fps = adjustable?.frameRate ?: CaptureSpec.DEFAULT_FRAME_RATE
    val (encodeWidth, encodeHeight) = CaptureSpec.encodeSize(spec.width, spec.height, quality.maxLongEdge)
    val budget = if (bitrateTier.kbps > 0) bitrateTier.kbps * 1000 else Int.MAX_VALUE
    val totalBitRate = minOf(quality.maxBitrate, CaptureSpec.bitRateFor(encodeWidth, encodeHeight, fps), budget)

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

    /**
     * 统一弹录屏授权。
     *
     * ⚠️ **必须在用户操作的同步路径里启动**，绝不能绕 coroutine（`LaunchedEffect`）。
     * MIUI 这类 ROM 会检查这个弹窗是不是"由用户操作直接触发"的 ——
     * 从协程里启动会被判定成后台启动、**整条直接拦掉**，
     * 表现就是"点了没反应、什么都不弹"（踩过）。
     *
     * 录屏授权本身是系统的硬性要求，去不掉：没有用户点"立即开始"，任何 App 都拿不到画面。
     */
    val startCast: (CastRequest) -> Unit = { target ->
        pending = target
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
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

    val scanner = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val contents = result.data?.getStringExtra(ScanActivity.EXTRA_RESULT)
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
            if (p2pGranted) {
                p2p.start()
                p2p.discover()
            } else {
                p2pPermissionLauncher.launch(p2pPermission)
            }
        } else {
            startCast(request)
        }
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
        startCast(target.copy(host = address, viaWifiDirect = false))
    }

    // 相机权限是异步的：拿到之后才由这里真正拉起扫码界面
    LaunchedEffect(wantScan, cameraGranted) {
        if (wantScan && cameraGranted) {
            wantScan = false
            scanner.launch(Intent(context, ScanActivity::class.java))
        }
    }

    DisposableEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onDispose {
            p2p.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        MirrorTopBar(title = "投屏")
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

            SettingsGroup("连接") {
                SettingsRow(
                    icon = Icons.Filled.QrCodeScanner,
                    iconTint = IconTints.blue,
                    title = "扫码直连",
                    subtitle = p2pStatus.message
                        ?: scanHint
                        ?: "扫接收端屏幕上的二维码即可连接",
                    showDivider = false,
                    onClick = {
                        scanHint = null
                        if (cameraGranted) {
                            wantScan = true
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
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
 * 现在**只有扫码一个来源**，自动搜索设备那条路已经整个去掉了 ——
 * 它带来的麻烦（广播被环境吃掉、多台设备互相干扰）远大于便利。
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

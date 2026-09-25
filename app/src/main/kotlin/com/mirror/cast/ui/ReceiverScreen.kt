package com.mirror.cast.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mirror.cast.HotspotController
import com.mirror.cast.CaptureSpec
import com.mirror.cast.LocalAddress
import com.mirror.cast.MirrorApplication
import com.mirror.cast.NetworkWatcher
import com.mirror.cast.SessionState
import com.mirror.cast.discovery.CastLink
import com.mirror.cast.discovery.CastTarget
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.p2p.WifiP2pLink
import com.mirror.cast.qr.QrCode
import com.mirror.cast.web.ReceiverSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 接收端内容（底部页签之一）。
 *
 * **风格与发送端统一**：小屏下就是同一套 iOS 卡片语法 ——
 * 顶部标题、中间画面卡片、底部一张功能卡片（图标行 + 展开的勾选项）。
 *
 * 形态：
 * - **默认小屏**（所有操作都在这里做）：全屏 / 画质 / 帧率 / 比例 / 复位；
 * - **全屏 = 视频播放器**：画面铺满、不显示任何功能按钮，只在右下角留「退出全屏」。
 *
 * 实现要点：两种形态用**同一份画面槽**（同一个 VideoSurface 调用点），
 * 只改外层容器与 padding —— 切换全屏时 `SurfaceViewRenderer` 不会被重建，
 * 画面不会重新起播。
 */
@Composable
fun ReceiverContent(onFullscreenChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val application = context.applicationContext as MirrorApplication
    val code = remember { ConnectCode.random() }
    val session = remember(code) { ReceiverSession(runtime = application.runtime, code = code) }
    val deviceName = remember { Build.MODEL ?: "Android" }

    /**
     * 热点由**接收端**开 —— 这条分工是刻意的，和 AirDroid 的规则一致：
     * **开热点的那台必须是接收端，不能是投屏端。**
     *
     * 道理在角色：接收端本来就只需要"守在那里"，开热点对它只是多绑一个接口；
     * 而发送端是最需要往外发数据的一方，让它同时扮演网关，路由与网络候选最容易出岔子。
     * 接收端开热点后地址固定是 `192.168.43.1` 这类 softap 地址，而 [LocalAddress]
     * 当初特意绕过 ConnectivityManager 去枚举 NetworkInterface，正是为了拿到它 ——
     * 二维码会自动跟着变成这个地址。
     */
    val hotspot = remember(context) { HotspotController(context) }
    var hotspotInfo by remember { mutableStateOf<HotspotController.HotspotInfo?>(null) }
    var hotspotError by remember { mutableStateOf<String?>(null) }

    /**
     * Wi-Fi Direct 链路 —— **离线直连的首选**。
     *
     * 建组成功后本机就是群主，地址固定 `192.168.49.1`。这时二维码会改用它，
     * 并带上 `p2p=1` 标记，让发送端知道"先建链路、再连地址"。
     */
    val p2p = remember(context) { WifiP2pLink(context) }
    val p2pStatus by p2p.status.collectAsState()

    /**
     * Wi-Fi Direct 的**运行时**权限。
     *
     * Android 13 起叫「附近的设备」（`NEARBY_WIFI_DEVICES`），更早的版本用位置权限。
     * **只在 Manifest 里声明是不够的** —— 不申请就直接建组，系统只会回一句
     * "系统内部错误"，根本不提示是权限问题（踩过）。
     */
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
    var wantP2pGroup by remember { mutableStateOf(false) }

    val p2pPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        p2pGranted = granted
        if (granted) wantP2pGroup = true
    }

    // 权限是异步给的：拿到之后才真正建组
    LaunchedEffect(wantP2pGroup, p2pGranted) {
        if (wantP2pGroup && p2pGranted) {
            wantP2pGroup = false
            p2p.start()
            p2p.createGroup()
        }
    }

    // 二维码里的地址必须跟着网络走：换 Wi-Fi / 连上热点后 IP 就变了，
    // 不刷新的话对方扫到的是一个连不上的旧地址
    var localIp by remember { mutableStateOf(LocalAddress.ipv4()) }

    // 网络接口一变就刷新本机地址：二维码里写的就是它 ——
    // 换 Wi-Fi 之后不刷新，对方扫到的就是一个连不上的旧地址
    val networkWatcher = remember(context) {
        NetworkWatcher(context) {
            localIp = LocalAddress.ipv4()
        }
    }

    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val zoomState = remember { mutableStateOf(1f) }
    val offsetXState = remember { mutableStateOf(0f) }
    val offsetYState = remember { mutableStateOf(0f) }

    // 默认小屏：一开始不要全屏
    var fullscreen by remember { mutableStateOf(false) }
    var expandedRow by remember { mutableStateOf(ExpandedRow.None) }

    var qualityName by remember { mutableStateOf(CaptureSpec.DEFAULT_QUALITY.name) }
    var fpsValue by remember { mutableIntStateOf(CaptureSpec.DEFAULT_FRAME_RATE) }

    val state by session.state.collectAsState()
    val configuration = LocalConfiguration.current

    // 扫码直连用的链接：地址 + 端口 + 连接码全塞进二维码，
    // 对方扫一下就能直连，完全不依赖广播能否穿过路由器
    val density = LocalDensity.current
    val qrPixels = remember(density) { with(density) { QR_SIZE_DP.roundToPx() } }
    // 注意：remember 最多 4 个 key，所以这里不能把 state 也塞进去 ——
    // 端口就绪本身会通过 diagnostics 触发重组，不需要它当 key
    val castLink = remember(
        localIp,
        code,
        session.signalingPort,
        p2pStatus.groupOwnerAddress,
    ) {
        val port = session.signalingPort
        if (port <= 0) {
            null
        } else {
            // 建了 Wi-Fi Direct 组就优先用它：那个地址不依赖任何已有网络
            val groupOwner = p2pStatus.groupOwnerAddress
            val host = groupOwner ?: localIp
            if (host == null) {
                null
            } else {
                CastLink.encode(
                    CastTarget(host, port, code, deviceName, viaWifiDirect = groupOwner != null),
                )
            }
        }
    }
    val qrImage = remember(castLink, qrPixels) { castLink?.let { QrCode.bitmap(it, qrPixels) } }

    LaunchedEffect(session) {
        networkWatcher.start()
        p2p.start()
        session.prepare()
        session.start(scope)
    }

    /**
     * 按网络状况决定走哪条路 —— 两条是**互补**的：
     *
     * - **有局域网地址**（连着 Wi-Fi 或热点）：直接用那个地址，**不建组** ——
     *   更快，也不占射频；
     * - **一个地址都没有**（两台设备什么都没连）：**自动建 Wi-Fi Direct 组**。
     *   这是"不连 Wi-Fi 也能投屏"的**唯一**办法 —— 系统会在两端之间拉一条专属链路，
     *   本机成为群主、地址固定 `192.168.49.1`，二维码会自动换成它并带上 `p2p=1` 标记。
     *
     * 之所以跟着 `localIp` 变：主人可能先开着 Wi-Fi 扫了码，中途 Wi-Fi 断了，
     * 这时得能自动切到 Wi-Fi Direct 上去。
     */
    LaunchedEffect(localIp, p2pGranted) {
        if (localIp == null) {
            if (p2pGranted) {
                p2p.start()
                p2p.createGroup()
            } else {
                p2pPermissionLauncher.launch(p2pPermission)
            }
        } else if (p2pStatus.groupOwnerAddress != null) {
            // 又有局域网了就不需要 P2P 组，把它还回去（别占着「一加互传」要用的射频）
            p2p.stop()
        }
    }

    /**
     * 建组之后如果**一直没人连上**，就到点自动拆掉。
     *
     * 这是"建了组忘了退"的兜底：P2P 组会一直占着 Wi-Fi 射频，挡住别的用 P2P 的功能
     * （实测是「一加互传」），有时只有重启手机才恢复。所以给闲置的组设个上限 ——
     * 到点还没人用，就还回去。
     */
    LaunchedEffect(p2pStatus.groupOwnerAddress, state) {
        if (p2pStatus.groupOwnerAddress != null && state !is SessionState.Streaming) {
            delay(P2P_IDLE_TIMEOUT_MILLIS)
            // 能走到这里，说明这段时间里既没人连上、也没有离开这个页面
            p2p.stop()
        }
    }

    // 断开之后：清掉渲染器里的最后一帧，并**立刻拆掉 Wi-Fi Direct 组**。
    //
    // 拆组要抢在"App 退出"之前 —— 它占着 Wi-Fi 射频，还会挡住别的用 P2P 的功能
    // （实测是「一加互传」）。所以只要不在投屏状态，就马上把它还回去。
    LaunchedEffect(state, p2pStatus.groupOwnerAddress) {
        if (state !is SessionState.Streaming) {
            renderer?.clearImage()
            if (p2pStatus.groupOwnerAddress != null) {
                p2p.stop()
            }
        }
    }

    // 全屏状态同步给外壳（它会隐藏底部切换栏）
    LaunchedEffect(fullscreen) { onFullscreenChange(fullscreen) }

    // 旋转 / 尺寸变化：复位缩放平移并重新布局，比例才会真的适应
    LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        zoomState.value = 1f
        offsetXState.value = 0f
        offsetYState.value = 0f
        renderer?.let { view ->
            view.setScalingType(
                RendererCommon.ScalingType.SCALE_ASPECT_FIT,
            )
            view.requestLayout()
        }
    }

    SystemBarsEffect(hidden = fullscreen)

    DisposableEffect(session) {
        onDispose {
            networkWatcher.stop()
            p2p.stop()
            hotspot.stop()
            session.detachRenderer()
            renderer?.let { view -> runCatching { view.release() } }
            renderer = null
            session.shutdown()
        }
    }

    val diagnostics by session.diagnostics.collectAsState()
    val reset = {
        zoomState.value = 1f
        offsetXState.value = 0f
        offsetYState.value = 0f
    }

    // 全屏切换时让内边距与圆角平滑过渡 —— 直接突变的话，画面会先"跳"一下再铺满
    val screenPaddingSide by animateDpAsState(if (fullscreen) 0.dp else 16.dp, label = "side")
    val screenPaddingTop by animateDpAsState(if (fullscreen) 0.dp else 66.dp, label = "top")
    val screenPaddingBottom by animateDpAsState(if (fullscreen) 0.dp else 150.dp, label = "bottom")
    val screenCorner by animateDpAsState(if (fullscreen) 0.dp else 14.dp, label = "corner")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background),
    ) {
        // ★ 同一个画面槽：全屏与小屏只改外层 padding 与圆角
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = screenPaddingSide,
                    end = screenPaddingSide,
                    top = screenPaddingTop,
                    bottom = screenPaddingBottom,
                )
                .clip(RoundedCornerShape(screenCorner))
                // 等待态的内容直接压在这块板上，所以底色必须是确定的黑 ——
                // 不能指望 SurfaceView 未出帧时的底色（浅色主题下会是白的，白字就没了）
                .background(Color.Black),
        ) {
            VideoSurface(
                application = application,
                session = session,
                zoomState = zoomState,
                offsetXState = offsetXState,
                offsetYState = offsetYState,
                onRenderer = { renderer = it },
                onTap = { reset() },
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            )

            if (state !is SessionState.Streaming && !fullscreen) {
                ConnectionCard(
                    code = code,
                    deviceName = deviceName,
                    qr = qrImage,
                    statusLine = diagnostics.line(),
                    hotspotActive = hotspot.running,
                    hotspotDetail = hotspotError
                        ?: hotspotInfo?.let { "${it.displayName} / 密码 ${it.password}" },
                    p2pActive = p2pStatus.groupOwnerAddress != null,
                    p2pDetail = p2pStatus.message,
                    onHotspot = {
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
                        // 开/关热点会换掉网络接口：广播必须重新绑定，否则发送端收不到
                    },
                    onWifiSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        if (fullscreen) {
            // 视频播放器形态：只有右下角一个退出按钮
            IconButton(
                onClick = { fullscreen = false },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0x801C1C1E)),
            ) {
                Icon(
                    imageVector = Icons.Filled.FullscreenExit,
                    contentDescription = "退出全屏",
                    tint = Color.White,
                )
            }
        } else {
            // 小屏：顶部标题、底部功能卡片 —— 与发送端同一套卡片语法
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
            ) {
                MirrorTopBar(title = "接收")

                if (state !is SessionState.Streaming) {
                    Text(
                        text = "连接码 ${ConnectCode.pretty(code)} · 在发送端点「$deviceName」",
                        modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                SettingsGroup("画面") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        SmallIconButton(icon = Icons.Filled.Fullscreen, description = "全屏") {
                            fullscreen = true
                            expandedRow = ExpandedRow.None
                        }
                        SmallIconButton(
                            icon = Icons.Filled.HighQuality,
                            description = "画质",
                            active = expandedRow == ExpandedRow.Quality,
                        ) {
                            expandedRow =
                                if (expandedRow == ExpandedRow.Quality) ExpandedRow.None else ExpandedRow.Quality
                        }
                        SmallIconButton(
                            icon = Icons.Filled.Speed,
                            description = "帧率",
                            active = expandedRow == ExpandedRow.FrameRate,
                        ) {
                            expandedRow =
                                if (expandedRow == ExpandedRow.FrameRate) ExpandedRow.None else ExpandedRow.FrameRate
                        }
                    }

                    when (expandedRow) {
                        ExpandedRow.Quality -> CaptureSpec.Quality.entries.forEachIndexed { index, tier ->
                            CheckRow(
                                title = "${tier.label} · 上限 ${tier.maxBitrate / 1_000_000}Mbps",
                                checked = tier.name == qualityName,
                                showDivider = index < CaptureSpec.Quality.entries.lastIndex,
                            ) {
                                qualityName = tier.name
                                expandedRow = ExpandedRow.None
                                if (state is SessionState.Streaming) {
                                    scope.launch { session.requestQuality(qualityName, fpsValue) }
                                }
                            }
                        }

                        ExpandedRow.FrameRate -> CaptureSpec.FrameRateTier.entries.forEachIndexed { index, tier ->
                            val target = CaptureSpec.resolveFps(tier, context.displayRefreshRateCompat())
                            CheckRow(
                                title = if (tier == CaptureSpec.FrameRateTier.FollowDisplay) {
                                    "跟随屏幕（${context.displayRefreshRateCompat().toInt()}Hz）"
                                } else {
                                    tier.label
                                },
                                checked = target == fpsValue,
                                showDivider = index < CaptureSpec.FrameRateTier.entries.lastIndex,
                            ) {
                                fpsValue = target
                                expandedRow = ExpandedRow.None
                                if (state is SessionState.Streaming) {
                                    scope.launch { session.requestQuality(qualityName, target) }
                                }
                            }
                        }

                        ExpandedRow.None -> Unit
                    }
                }

                Text(
                    text = "上限 " +
                        (if (session.remoteBitrateLimitKbps > 0) "${session.remoteBitrateLimitKbps / 1000}Mbps" else "自动") +
                        (if (state is SessionState.Streaming) " · " + diagnostics.line() else ""),
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 二维码边长：够对方一眼扫到，又不至于把小屏里的等待卡撑爆。 */
private val QR_SIZE_DP = 148.dp

/** 闲置的 Wi-Fi Direct 组最多留多久 —— 到点没人连就自动拆，别让它占着射频。 */
private const val P2P_IDLE_TIMEOUT_MILLIS = 3 * 60 * 1000L

private enum class ExpandedRow { None, Quality, FrameRate }

/** 功能行用的小图标按钮：底色走主题，与发送端的图标块同一语言。 */
@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                },
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 等待连接时浮在画面区中央的提示：连接码 + 二维码 + 下一步 + Wi-Fi 设置。
 *
 * **刻意不给自己加背景**：画面区本身就是一块深色底板，再套一层深灰圆角卡
 * 就是"框里套框"。内容直接压在这块板上反而更干净 —— 卡片不再嵌卡片。
 */
@Composable
private fun ConnectionCard(
    code: String,
    deviceName: String,
    qr: ImageBitmap?,
    statusLine: String,
    hotspotActive: Boolean,
    hotspotDetail: String?,
    p2pActive: Boolean,
    p2pDetail: String?,
    onHotspot: () -> Unit,
    onWifiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Cast,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = Color(0xFF0A84FF),
        )
        Text(
            text = "连接码",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8E8E93),
        )
        ConnectCodeDisplay(code = ConnectCode.pretty(code), color = Color.White)
        if (qr != null) {
            Text(
                text = "用发送端「扫码」直连",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0B0B0),
            )
            Image(
                bitmap = qr,
                contentDescription = "投屏二维码",
                modifier = Modifier
                    .size(QR_SIZE_DP)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White),
            )
        }
        Text(
            text = "或在发送端点「$deviceName」",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB0B0B0),
        )
        Text(
            text = statusLine,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF8A8A8A),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(
                onClick = onWifiSettings,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x22FFFFFF)),
            ) {
                Icon(imageVector = Icons.Filled.Wifi, contentDescription = "Wi-Fi 设置", tint = Color.White)
            }
            // Wi-Fi Direct 是**默认连法**，进来就自动建组了，所以这里没有它的按钮
            IconButton(
                onClick = onHotspot,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (hotspotActive) Color(0x5530D158) else Color(0x22FFFFFF)),
            ) {
                Icon(
                    imageVector = Icons.Filled.WifiTethering,
                    contentDescription = "开热点给发送端连",
                    tint = if (hotspotActive) Color(0xFF30D158) else Color.White,
                )
            }
        }
        // 状态行：就绪了是绿的，其余用灰 —— 内容本身已经说明发生了什么
        val detail = p2pDetail ?: hotspotDetail
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (p2pActive || hotspotActive) Color(0xFF30D158) else Color(0xFF8A8A8A),
            )
        }
    }
}

/** 全屏时隐藏系统栏（沉浸），退出时恢复。 */
@Composable
private fun SystemBarsEffect(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        // 接收端要一直守着 TCP 监听等人来连。屏幕一熄，MIUI 这类系统就可能把它的
        // 网络掐掉、甚至回收进程 —— 之后对方扫码收到的就是"连接被拒绝"。
        // 所以这一页刻意保持常亮。
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (hidden) {
            // 只隐藏**状态栏**，刻意保留导航栏：两台设备屏幕比例不同时，
            // 画面等比缩放后底下必然空出一块 —— 留着导航栏，那块就不是死黑，
            // 而且系统返回手势还能正常用
            controller?.hide(WindowInsetsCompat.Type.statusBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** 屏幕刷新率（帧率档位"跟随屏幕"要用它）。 */
@Suppress("DEPRECATION")
private fun android.content.Context.displayRefreshRateCompat(): Float =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.refreshRate ?: 60f
    } else {
        val manager = getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
        manager?.defaultDisplay?.refreshRate ?: 60f
    }

/**
 * 画面本体：渲染器 + 图层变换 + 手势。
 *
 * 变换走 `graphicsLayer` 的 lambda（绘制阶段读 State），捏合缩放不触发重组。
 */
@Composable
private fun VideoSurface(
    application: MirrorApplication,
    session: ReceiverSession,
    zoomState: MutableState<Float>,
    offsetXState: MutableState<Float>,
    offsetYState: MutableState<Float>,
    onRenderer: (SurfaceViewRenderer) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { viewContext ->
            SurfaceViewRenderer(viewContext).apply {
                init(application.runtime.eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                session.attachRenderer(this)
                onRenderer(this)
            }
        },
        modifier = modifier
            .graphicsLayer {
                scaleX = zoomState.value
                scaleY = zoomState.value
                translationX = offsetXState.value
                translationY = offsetYState.value
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoomState.value = (zoomState.value * gestureZoom).coerceIn(1f, 6f)
                    if (zoomState.value > 1f) {
                        offsetXState.value += pan.x
                        offsetYState.value += pan.y
                    } else {
                        offsetXState.value = 0f
                        offsetYState.value = 0f
                    }
                }
            },
    )
}

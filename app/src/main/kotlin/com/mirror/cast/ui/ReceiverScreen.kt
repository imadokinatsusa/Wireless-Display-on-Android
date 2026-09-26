package com.mirror.cast.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mirror.cast.CaptureSpec
import com.mirror.cast.HotspotController
import com.mirror.cast.LocalAddress
import com.mirror.cast.MirrorApplication
import com.mirror.cast.NetworkWatcher
import com.mirror.cast.ProbeResponder
import com.mirror.cast.SessionState
import com.mirror.cast.discovery.CastLink
import com.mirror.cast.discovery.CastTarget
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.discovery.ProbeReply
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
     * 静默应答对方的探测：对方扫这个端口来找人，比广播可靠得多
     * （不怕 AP 隔离、也不需要 Wi-Fi 扫描权限），而界面上完全看不到这件事。
     */
    val responder = remember(session) {
        ProbeResponder(scope) {
            ProbeReply(
                deviceName = deviceName,
                signalingPort = session.signalingPort,
                code = code,
            )
        }
    }

    /**
     * Wi-Fi Direct 链路 —— **离线直连的首选**。
     *
     * 建组成功后本机就是群主，地址固定 `192.168.49.1`。这时二维码会改用它，
     * 并带上 `p2p=1` 标记，让发送端知道"先建链路、再连地址"。
     */
    val p2p = remember(context) { WifiP2pLink(context) }
    val p2pStatus by p2p.status.collectAsState()

    /**
     * 本地热点 —— **一个共同网络都没有时的唯一链路**。
     *
     * Wi-Fi Direct 那条撞墙了（组能建、信令能通，但媒体栈拿不到那条 P2P 网络）；
     * 热点不一样：**连上来的那一端是普通 Wi-Fi 客户端**，地址在 `ConnectivityManager`
     * 里，媒体栈看得见。名字和信道都由系统给（改不了，也不需要），
     * 但凭证会随二维码传过去，对方一扫就自动连 —— 没人需要认出那个名字。
     */
    val hotspot = remember(context) { HotspotController(context) }
    var hotspotInfo by remember { mutableStateOf<HotspotController.HotspotInfo?>(null) }

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

    /**
     * 能不能走局域网直连。
     *
     * **必须用 [LocalAddress.hasLan]，不能只看"有没有 IP"**：关掉 Wi-Fi 之后设备往往还挂着
     * 流量，蜂窝内网的地址照样在，只看 IP 会被误判成"有网络"，于是死活不建 Wi-Fi Direct 组。
     */
    var hasLan by remember { mutableStateOf(LocalAddress.hasLan()) }

    // 网络接口一变就刷新：二维码里的地址、以及"该不该建组"都取决于它
    val networkWatcher = remember(context) {
        NetworkWatcher(context) {
            localIp = LocalAddress.ipv4()
            hasLan = LocalAddress.hasLan()
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
        hasLan,
        localIp,
        session.signalingPort,
        // remember 具名重载最多 4 个 key，所以第 4 个把"热点状态 + P2P 群主地址"合成一个 Pair：
        // Pair 是结构相等，key 比较照样准。
        hotspotInfo to p2pStatus.groupOwnerAddress,
    ) {
        val port = session.signalingPort
        val p2pAddress = p2pStatus.groupOwnerAddress
        // 地址取"当前真正可用的那个"：连了 Wi-Fi、或者自己开的热点已就绪，都算有。
        // 都没有时给不出有效地址 —— 那时二维码内容没有意义，宁可先不出码。
        val host = localIp.takeIf { hasLan }
        if (port <= 0 || host == null) {
            null
        } else {
            CastLink.encode(
                CastTarget(
                    host = host,
                    port = port,
                    code = code,
                    deviceName = deviceName,
                    // ★ 装进二维码的是 **Wi-Fi Direct 群主地址** 时，必须打上 `p2p=1`。
                    //
                    // 这个标记决定发送端走哪条路：有它才会先去 `discoverPeers` + `connect`
                    // 加入这个组，再拿群主地址当 host；没有它就直接拿 `192.168.49.1` 去连 ——
                    // 而那条地址在发送端**加入组之前根本不可达**。
                    // 表现正是「扫码成功、一直连接中、然后失败」（踩过）。
                    viaWifiDirect = p2pAddress != null && host == p2pAddress,
                    // 热点凭证随码走：对方一扫自动连，不用认名字、不用输密码
                    hotspotSsid = hotspotInfo?.ssid,
                    hotspotPassword = hotspotInfo?.password,
                ),
            )
        }
    }
    val qrImage = remember(castLink, qrPixels) { castLink?.let { QrCode.bitmap(it, qrPixels) } }

    LaunchedEffect(session) {
        networkWatcher.start()
        p2p.start()
        session.prepare()
        responder.start()
        session.start(scope)
    }

    /**
     * 进这一页先把网络准备好，但**不打扰主人、也不留任何痕迹**。
     *
     * 只做一件事：**Wi-Fi 开关关着的时候，把系统的 Wi-Fi 面板推出来**，
     * 主人点一下就能开，然后回到应用继续。
     *
     * 为什么不能直接替主人打开：Android 10 起**禁止普通 App 开关 Wi-Fi**
     * （`setWifiEnabled` 只对系统应用有效）。能做到的极限就是把开关递到主人面前。
     */
    // 建组成功后接口地址会变，但 **`ConnectivityManager` 未必通知我们** ——
    // Wi-Fi Direct 那条网络通常不在它的列表里，所以 `NetworkWatcher` 一声不吭。
    // 不主动刷新的话，`localIp` 会一直停在建组前的旧值（null 或蜂窝地址），
    // 二维码就死活不出来（踩过）。所以这里盯着建组结果补一次。
    LaunchedEffect(p2pStatus.groupOwnerAddress) {
        if (p2pStatus.groupOwnerAddress != null) {
            localIp = LocalAddress.ipv4()
            hasLan = LocalAddress.hasLan()
        }
    }

    LaunchedEffect(hasLan) {
        if (LocalAddress.hasExternalLan()) {
            // 本来就有真网络：不需要额外造链路，把造出来的还回去。
            // 注意用的是 hasExternalLan 而不是 hasLan —— 后者把 P2P 自己也算"有网络"，
            // 拿它做拆组判据会让刚建好的组立刻被自己拆掉（踩过）。
            if (hotspot.running) hotspot.stop()
            if (p2pStatus.groupOwnerAddress != null) p2p.stop()
            return@LaunchedEffect
        }
        // 一个共同网络都没有 —— 用 **Wi-Fi Direct** 造一条（我们的通信方式是它，不是热点）。
        // 它要靠 Wi-Fi 射频，所以先确认开关是开的；关着就把系统面板推出来。
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled != true) {
            launchWifiPanel(context)
        } else if (p2pGranted) {
            p2p.start()
            // ⚠️ 只有"还没有组"时才建：建组成功会让 `hasLan` 从 false 翻成 true
            // （`192.168.49.x` 也算可用的局域网地址），于是**这个 effect 会自己再进来一次**。
            // 无脑再建一遍的话，系统只会回 BUSY，状态行被写成「建组失败：系统正忙」——
            // 组明明好好地建着，主人却以为失败了（踩过）。
            if (p2pStatus.groupOwnerAddress == null) p2p.createGroup()
        } else {
            p2pPermissionLauncher.launch(p2pPermission)
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

    // 断开之后：清掉渲染器里的最后一帧；**如果这一轮确实投过屏，顺手把组拆掉**。
    //
    // ⚠️ 判据必须是"**投过屏之后又断开**"，绝不能写成"只要不在投屏就拆" ——
    // 那样刚建好的组会在生成二维码的同一瞬间被自己拆掉，
    // 表现就是**二维码闪一下就不见了**（踩过）。"从来没投过"不等于"断开"。
    var wasStreaming by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is SessionState.Streaming) {
            wasStreaming = true
        } else {
            renderer?.clearImage()
            if (wasStreaming) {
                wasStreaming = false
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
            responder.stop()
            hotspot.stop()
            p2p.stop()
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
                    preparingHint = null,
                    // 状态行要带上**本机地址、看得见的网络、端口**：真机没有 adb，
                    // 出问题时主人能念出来的就只有这行字。
                    // "看得见的网络"尤其关键 —— P2P 链路在不在里面，直接决定媒体能不能起来。
                    statusLine = "本机 ${LocalAddress.summary()} · " +
                        "端口 ${session.signalingPort} · " +
                        // ★ P2P 的**实际频段**必须露出来。建组时请求的 5GHz 只是偏好，
                        // 系统可能默不作声地建在 2.4GHz —— 而丢包、RTT、延迟一起恶化、
                        // 画面既糊又卡的根因往往就是它。看不到频段就只能靠猜。
                        "P2P ${p2pStatus.bandLabel ?: p2pStatus.message ?: "未建组"} · " +
                        "网络 ${LocalAddress.describeNetworks(context)} · " + diagnostics.line(),
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
    /** 没有二维码时显示的原因 —— 建组中、建组失败、还是别的，都要让主人看见。 */
    preparingHint: String?,
    statusLine: String,
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
            Text(
                text = "或在发送端点「$deviceName」",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0B0B0),
            )
        } else {
            // 还没有可用的地址（就是这一台还没连上网络）。
            //
            // 措辞要准：二维码里装的是**本机地址**，所以它只取决于**这一台**有没有网络，
            // 跟对方连不连毫无关系 —— 写成"两台都要连"会让人白折腾。
            Text(
                text = preparingHint ?: "连上 Wi-Fi 后\n这里会出现二维码",
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFF9F0A),
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = statusLine,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF8A8A8A),
        )
        // 这里**刻意不放任何网络按钮**，也不出现"信道/热点/直连"这类字眼 ——
        // 开 Wi-Fi、建链路都由页面在后台自己完成。
        // 主人要做的只有三件事：出码 → 对方扫 → 连上。
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

/**
 * 拉起系统的 Wi-Fi 面板。
 *
 * 用它而不是跳设置页：它是个浮层，主人点一下开关就能直接回来，不用离开应用。
 */
private fun launchWifiPanel(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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

package com.mirror.cast.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mirror.cast.Broadcaster
import com.mirror.cast.CaptureSpec
import com.mirror.cast.MirrorApplication
import com.mirror.cast.SessionState
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.web.ReceiverSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 接收端内容（底部页签之一）。
 *
 * 两种形态共用同一块画面（渲染器始终在，切换不会重建）：
 * - **等待中**：屏幕中央一张深色卡片 —— 连接码、下一步提示、Wi-Fi 设置按钮；
 * - **已连接**：自动进全屏，控制层只有图标（画质/帧率/比例/复位），点画面显隐、3 秒淡出。
 *
 * 分工：**画质与帧率在这里调**（看画面的人最清楚卡不卡、糊不糊），
 * 通过信令发回发送端；码率上限由发送端设定，这里只显示预算。
 */
@Composable
fun ReceiverContent(onFullscreenChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val application = context.applicationContext as MirrorApplication
    val code = remember { Broadcaster.newCode() }
    val session = remember(code) { ReceiverSession(runtime = application.runtime, code = code) }
    val deviceName = remember { Build.MODEL ?: "Android" }
    val broadcaster = remember(session) {
        Broadcaster(
            scope = scope,
            code = code,
            portProvider = { session.signalingPort },
            deviceName = deviceName,
        )
    }

    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    val zoomState = remember { mutableStateOf(1f) }
    val offsetXState = remember { mutableStateOf(0f) }
    val offsetYState = remember { mutableStateOf(0f) }

    var fullscreen by remember { mutableStateOf(false) }
    var fillScreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var expandedRow by remember { mutableStateOf(ExpandedRow.None) }

    var qualityName by remember { mutableStateOf(CaptureSpec.DEFAULT_QUALITY.name) }
    var fpsValue by remember { mutableIntStateOf(CaptureSpec.DEFAULT_FRAME_RATE) }

    val state by session.state.collectAsState()
    val configuration = LocalConfiguration.current

    LaunchedEffect(session) {
        session.prepare()
        broadcaster.start()
        session.start(scope)
    }

    // 一开始投屏：自动全屏、收起按钮
    LaunchedEffect(state) {
        if (state is SessionState.Streaming) {
            fullscreen = true
            controlsVisible = false
            expandedRow = ExpandedRow.None
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
                if (fillScreen) {
                    RendererCommon.ScalingType.SCALE_ASPECT_FILL
                } else {
                    RendererCommon.ScalingType.SCALE_ASPECT_FIT
                },
            )
            view.requestLayout()
        }
    }

    LaunchedEffect(fillScreen) {
        renderer?.setScalingType(
            if (fillScreen) {
                RendererCommon.ScalingType.SCALE_ASPECT_FILL
            } else {
                RendererCommon.ScalingType.SCALE_ASPECT_FIT
            },
        )
    }

    LaunchedEffect(controlsVisible, fullscreen, expandedRow) {
        if (fullscreen && controlsVisible && expandedRow == ExpandedRow.None) {
            delay(3_000)
            controlsVisible = false
        }
    }

    SystemBarsEffect(hidden = fullscreen)

    DisposableEffect(session) {
        onDispose {
            broadcaster.stop()
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(
            application = application,
            session = session,
            zoomState = zoomState,
            offsetXState = offsetXState,
            offsetYState = offsetYState,
            onRenderer = { renderer = it },
            onTap = { controlsVisible = !controlsVisible },
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
        )

        // 等待中：中央深色卡片
        if (state !is SessionState.Streaming) {
            ConnectionCard(
                code = code,
                deviceName = deviceName,
                statusLine = diagnostics.line(),
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

        if (controlsVisible && state is SessionState.Streaming) {
            IconButton(
                onClick = { fullscreen = false },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x661C1C1E)),
            ) {
                Icon(
                    imageVector = Icons.Filled.FullscreenExit,
                    contentDescription = "退出全屏",
                    tint = Color.White,
                )
            }

            GlassPanel(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ControlIcon(
                        icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        description = if (fullscreen) "小窗" else "全屏",
                    ) {
                        fullscreen = !fullscreen
                        expandedRow = ExpandedRow.None
                    }
                    ControlIcon(
                        icon = Icons.Filled.HighQuality,
                        description = "画质",
                        active = expandedRow == ExpandedRow.Quality,
                    ) {
                        expandedRow =
                            if (expandedRow == ExpandedRow.Quality) ExpandedRow.None else ExpandedRow.Quality
                    }
                    ControlIcon(
                        icon = Icons.Filled.Speed,
                        description = "帧率",
                        active = expandedRow == ExpandedRow.FrameRate,
                    ) {
                        expandedRow =
                            if (expandedRow == ExpandedRow.FrameRate) ExpandedRow.None else ExpandedRow.FrameRate
                    }
                    ControlIcon(
                        icon = Icons.Filled.AspectRatio,
                        description = if (fillScreen) "填充" else "适应",
                        active = fillScreen,
                    ) {
                        fillScreen = !fillScreen
                    }
                    ControlIcon(icon = Icons.Filled.CenterFocusStrong, description = "复位") { reset() }
                    Spacer(modifier = Modifier.weight(1f))
                    ControlIcon(icon = Icons.Filled.Close, description = "关闭画面") {
                        controlsVisible = false
                    }
                }

                if (expandedRow == ExpandedRow.Quality) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CaptureSpec.Quality.entries.forEach { tier ->
                            FilterChip(
                                selected = tier.name == qualityName,
                                onClick = {
                                    qualityName = tier.name
                                    scope.launch { session.requestQuality(qualityName, fpsValue) }
                                },
                                label = { Text(tier.label) },
                            )
                        }
                    }
                }

                if (expandedRow == ExpandedRow.FrameRate) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CaptureSpec.FrameRateTier.entries.forEach { tier ->
                            val target = CaptureSpec.resolveFps(tier, context.displayRefreshRateCompat())
                            FilterChip(
                                selected = target == fpsValue,
                                onClick = {
                                    fpsValue = target
                                    scope.launch { session.requestQuality(qualityName, target) }
                                },
                                label = { Text(tier.label) },
                            )
                        }
                    }
                }

                Text(
                    text = "上限 " +
                        (if (session.remoteBitrateLimitKbps > 0) "${session.remoteBitrateLimitKbps / 1000}Mbps" else "自动") +
                        " · " + diagnostics.line(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8A8A8A),
                )
            }
        }
    }
}

private enum class ExpandedRow { None, Quality, FrameRate }

/** 等待中的中央卡片：连接码 + 下一步 + Wi-Fi 设置。 */
@Composable
private fun ConnectionCard(
    code: String,
    deviceName: String,
    statusLine: String,
    onWifiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(26.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1C1C1E))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(22.dp))
            .padding(horizontal = 26.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Cast,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = Color(0xFF0A84FF),
        )
        Text(
            text = "连接码",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8E8E93),
        )
        ConnectCodeDisplay(code = ConnectCode.pretty(code), color = Color.White)
        Text(
            text = "在发送端点「$deviceName」",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB0B0B0),
        )
        Text(
            text = statusLine,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF8A8A8A),
        )
        IconButton(
            onClick = onWifiSettings,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0x22FFFFFF)),
        ) {
            Icon(imageVector = Icons.Filled.Wifi, contentDescription = "Wi-Fi 设置", tint = Color.White)
        }
    }
}

/** 控制层图标按钮：圆形、半透明，选中时高亮。 */
@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Color(0x44FFFFFF) else Color(0x22FFFFFF)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) Color.White else Color(0xFFDDDDDD),
        )
    }
}

/**
 * 磨砂玻璃面板：半透明深色 + 圆角 + 细描边。
 *
 * 真正的背景模糊需要 API 31+ 且对 SurfaceView（独立图层）无效，
 * 所以这里用"半透明 + 圆角 + 亮边"做出同样的观感。
 */
@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .clip(shape)
            .background(Color(0xB0121212))
            .border(1.dp, Color(0x22FFFFFF), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** 全屏时隐藏系统栏（沉浸），退出时恢复。 */
@Composable
private fun SystemBarsEffect(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        if (hidden) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
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
                setEnableHardwareScaler(true)
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

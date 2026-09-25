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
import androidx.compose.material.icons.filled.CenterFocusStrong
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
import com.mirror.cast.LocalAddress
import com.mirror.cast.MirrorApplication
import com.mirror.cast.NetworkWatcher
import com.mirror.cast.SessionState
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.web.ReceiverSession
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

    // 网络接口一变就重启广播：接收端换了接口（连上热点）后必须重新广播，否则发送端搜不到
    val networkWatcher = remember(context) {
        NetworkWatcher(context) {
            broadcaster.stop()
            broadcaster.start()
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

    LaunchedEffect(session) {
        networkWatcher.start()
        session.prepare()
        broadcaster.start()
        session.start(scope)
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
                    if (fullscreen) {
                        androidx.compose.foundation.layout.PaddingValues(0.dp)
                    } else {
                        androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 66.dp,
                            bottom = 150.dp,
                        )
                    },
                )
                .clip(RoundedCornerShape(if (fullscreen) 0.dp else 14.dp)),
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
                MirrorTopBar(title = "接收显示")

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
                        SmallIconButton(
                            icon = Icons.Filled.CenterFocusStrong,
                            description = "复位",
                        ) { reset() }
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

/** 等待中的提示卡：连接码 + 下一步 + Wi-Fi 设置。 */
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
            .padding(20.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1C1C1E))
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(22.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
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

package com.mirror.cast.ui

import android.app.Activity
import android.os.Build
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mirror.cast.Broadcaster
import com.mirror.cast.MirrorApplication
import com.mirror.cast.SessionState
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.web.ReceiverSession
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 接收端：亮出连接码 → 等发送端来连 → 像看视频一样看画面。
 *
 * 交互照播放器来：
 * - **一开始投屏就自动进全屏、并且不显示任何按钮**（最沉浸的状态）；
 * - **点画面**唤出/收起控制层，全屏时 3 秒无操作自动淡出；
 * - 控制层是**磨砂玻璃**质感（半透明深色 + 圆角 + 细描边）；
 * - 双指缩放（1×–6×）+ 单指拖动看细节；
 * - 退出用 [ReceiverSession.shutdown]（会话自带 scope），
 *   不用 `rememberCoroutineScope` —— 它随 composable 销毁被取消，
 *   那样 Bye 发不出去，就成了"接收端已退出、发送端还显示投屏中"。
 *
 * 旋转屏幕不会断开：Activity 声明了 configChanges（见 AndroidManifest），
 * 旋转不重建、会话与画面都留在原地。
 */
@Composable
fun ReceiverScreen(onBack: () -> Unit) {
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

    val state by session.state.collectAsState()

    LaunchedEffect(session) {
        // 先把监听端口准备好，再开始广播连接码 —— 否则对端拿到的是无效端口
        session.prepare()
        broadcaster.start()
        session.start(scope)
    }

    // ★ 一开始投屏：自动全屏、收起按钮
    LaunchedEffect(state) {
        if (state is SessionState.Streaming) {
            fullscreen = true
            controlsVisible = false
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

    // 播放器手感：全屏且控制层可见时，3 秒后自动淡出
    LaunchedEffect(controlsVisible, fullscreen) {
        if (fullscreen && controlsVisible) {
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

        // 还没开始投屏时才显示连接码，开始投屏后一切让位给画面
        if (state !is SessionState.Streaming) {
            GlassPanel(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = "连接码",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB0B0B0),
                )
                Text(
                    text = ConnectCode.pretty(code),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                )
                Text(
                    text = "在发送端选择「$deviceName」；本机 IP 可在系统设置里查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0B0B0),
                )
            }
        }

        if (controlsVisible) {
            GlassPanel(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp),
            ) {
                TextButton(
                    onClick = { if (fullscreen) fullscreen = false else onBack() },
                ) {
                    Text(text = if (fullscreen) "↙ 退出全屏" else "← 返回", color = Color.White)
                }
            }

            GlassPanel(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { fullscreen = !fullscreen }) {
                        Text(text = if (fullscreen) "▣ 窗口" else "⛶ 全屏", color = Color.White)
                    }
                    TextButton(onClick = { fillScreen = !fillScreen }) {
                        Text(text = if (fillScreen) "◱ 填充" else "◱ 适应", color = Color.White)
                    }
                    TextButton(onClick = reset) {
                        Text(text = "⟲ 复位", color = Color.White)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "%.1f×".format(zoomState.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0B0B0),
                    )
                }
                Text(
                    text = diagnostics.line(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFB0B0B0),
                )
            }
        }
    }
}

/**
 * 磨砂玻璃面板：半透明深色 + 圆角 + 细描边。
 *
 * 真正的背景模糊需要 API 31+ 且对 SurfaceView（独立图层）无效，
 * 所以这里用"半透明 + 圆角 + 亮边"来做出同样的观感。
 */
@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(shape)
            .background(Color(0xB3121212))
            .border(1.dp, Color(0x22FFFFFF), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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

/**
 * 画面本体：渲染器 + 图层变换 + 手势。
 *
 * 变换走 `graphicsLayer` 的 lambda（绘制阶段读 State），捏合缩放不触发重组。
 * 点按与缩放分在两个 `pointerInput`：单指点是"显隐控制层"，多指才是缩放。
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

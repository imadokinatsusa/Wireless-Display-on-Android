package com.mirror.cast.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mirror.cast.Broadcaster
import com.mirror.cast.MirrorApplication
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.web.ReceiverSession
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 接收端：亮出连接码 → 等发送端来连 → 像看视频一样看画面。
 *
 * 交互照播放器来：
 * - **窗口 / 全屏**切换（全屏时只剩画面 + 一个退出按钮）；
 * - **适应 / 填充**切换（留黑边 vs 裁剪填满）；
 * - **双指缩放 + 单指拖动**看细节，一键复位。
 *
 * 缩放用的是 View 图层变换：`SurfaceView` 从 API 24 起跟随宿主窗口变换，
 * 所以不必换渲染器（m150 里也没有 `TextureViewRenderer`，javap 实证）。
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

    // 用 State 对象而不是解包的值：手势闭包只创建一次，必须读到最新值
    val zoomState = remember { mutableStateOf(1f) }
    val offsetXState = remember { mutableStateOf(0f) }
    val offsetYState = remember { mutableStateOf(0f) }

    var fullscreen by remember { mutableStateOf(false) }
    var fillScreen by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        // 先把监听端口准备好，再开始广播连接码 —— 否则对端拿到的是无效端口
        session.prepare()
        broadcaster.start()
        session.start(scope)
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

    DisposableEffect(session) {
        onDispose {
            broadcaster.stop()
            session.detachRenderer()
            renderer?.let { view -> runCatching { view.release() } }
            renderer = null
            scope.launch { session.stop() }
        }
    }

    val diagnostics by session.diagnostics.collectAsState()
    val zoomLabel = remember(zoomState.value) { "%.1f×".format(zoomState.value) }

    val reset = {
        zoomState.value = 1f
        offsetXState.value = 0f
        offsetYState.value = 0f
    }

    if (fullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            VideoSurface(
                application = application,
                session = session,
                zoomState = zoomState,
                offsetXState = offsetXState,
                offsetYState = offsetYState,
                onRenderer = { renderer = it },
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { fullscreen = false }) { Text("窗口") }
                Button(onClick = { fillScreen = !fillScreen }) { Text(if (fillScreen) "填充" else "适应") }
                Button(onClick = reset) { Text("复位") }
            }
            DiagnosticsBar(
                text = diagnostics.line(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(title = "接收显示", onBack = onBack)
        ConnectCodeDisplay(code = ConnectCode.pretty(code))
        Text(
            text = "在发送端选择「$deviceName」即可开始投屏。双指可缩放画面。",
            style = MaterialTheme.typography.bodySmall,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            VideoSurface(
                application = application,
                session = session,
                zoomState = zoomState,
                offsetXState = offsetXState,
                offsetYState = offsetYState,
                onRenderer = { renderer = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { fullscreen = true }) { Text("全屏") }
            Button(onClick = { fillScreen = !fillScreen }) { Text(if (fillScreen) "填充" else "适应") }
            Button(onClick = reset) { Text("复位") }
            Text(text = zoomLabel, style = MaterialTheme.typography.bodySmall)
        }

        DiagnosticsBar(text = diagnostics.line())
    }
}

/**
 * 画面本体：媒体栈的渲染器 + 图层变换 + 手势。
 *
 * 变换走 `graphicsLayer` 的 lambda（在绘制阶段读取 State），因此捏合缩放**不会**触发重组。
 */
@Composable
private fun VideoSurface(
    application: MirrorApplication,
    session: ReceiverSession,
    zoomState: androidx.compose.runtime.MutableState<Float>,
    offsetXState: androidx.compose.runtime.MutableState<Float>,
    offsetYState: androidx.compose.runtime.MutableState<Float>,
    onRenderer: (SurfaceViewRenderer) -> Unit,
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
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoomState.value = (zoomState.value * gestureZoom).coerceIn(1f, 6f)
                    if (zoomState.value > 1f) {
                        offsetXState.value += pan.x
                        offsetYState.value += pan.y
                    } else {
                        // 回到 1× 时把平移也归零，避免"画面跑到屏幕外"
                        offsetXState.value = 0f
                        offsetYState.value = 0f
                    }
                }
            },
    )
}

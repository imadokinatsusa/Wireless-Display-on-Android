package com.mirror.cast.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mirror.cast.Broadcaster
import com.mirror.cast.MirrorApplication
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.web.ReceiverSession
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 接收端：亮出连接码 → 等发送端来连 → 像看视频一样看画面。
 *
 * 交互照播放器来（控制层可显隐、底部控制条、沉浸全屏）：
 * - **点画面**切换控制层；全屏时 3 秒无操作自动淡出；
 * - 底部控制条：全屏/窗口、适应/填充、复位、当前缩放倍率；
 * - **双指缩放 + 单指拖动**看细节；
 * - 全屏时隐藏系统栏，退出时恢复。
 *
 * 退出时用 [ReceiverSession.shutdown]（会话自带的独立 scope）收尾，
 * 不用 `rememberCoroutineScope` —— 那个 scope 会随 composable 销毁被取消，
 * 结果就是"接收端已经退出、发送端还显示投屏中"（这正是上一个 bug）。
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

    // 播放器手感：全屏时控制层自动淡出，点一下再出来
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
            // ★ 用会话自己的收尾 scope：界面 scope 此刻正在被取消，用它发不出 Bye
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

        if (!fullscreen) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ConnectCodeDisplay(code = ConnectCode.pretty(code))
                Text(
                    text = "在发送端选择「$deviceName」开始投屏；点画面可显隐控制条。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }

        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Button(onClick = { if (fullscreen) fullscreen = false else onBack() }) {
                    Text(if (fullscreen) "退出全屏" else "← 返回")
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { fullscreen = !fullscreen }) {
                        Text(if (fullscreen) "窗口" else "全屏")
                    }
                    Button(onClick = { fillScreen = !fillScreen }) {
                        Text(if (fillScreen) "填充" else "适应")
                    }
                    Button(onClick = reset) { Text("复位") }
                    Text(
                        text = "%.1f×".format(zoomState.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                }
                Text(
                    text = diagnostics.line(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                )
            }
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

/**
 * 画面本体：渲染器 + 图层变换 + 手势。
 *
 * 变换走 `graphicsLayer` 的 lambda（绘制阶段读取 State），捏合缩放不会触发重组。
 * 点按与缩放手势放在两个 `pointerInput` 里：单指点是"显隐控制条"，多指才是缩放。
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

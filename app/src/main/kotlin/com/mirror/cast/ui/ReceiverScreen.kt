package com.mirror.cast.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mirror.cast.Broadcaster
import com.mirror.cast.MirrorApplication
import com.mirror.cast.discovery.ConnectCode
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * 接收端：亮出连接码 → 等发送端来连 → 全屏看画面。
 *
 * 画面用媒体栈自带的 [SurfaceViewRenderer]（等比完整显示 + 黑边），
 * 声音由媒体栈直接播放，这里不写任何解码/播放代码。
 */
@Composable
fun ReceiverScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val application = context.applicationContext as MirrorApplication
    val code = remember { Broadcaster.newCode() }
    val session = remember(code) {
        com.mirror.cast.web.ReceiverSession(runtime = application.runtime, code = code)
    }
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

    LaunchedEffect(session) {
        // 先把监听端口准备好，再开始广播连接码 —— 否则对端拿到的是无效端口
        session.prepare()
        broadcaster.start()
        session.start(scope)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(title = "接收显示", onBack = onBack)
        ConnectCodeDisplay(code = ConnectCode.pretty(code))
        Text(
            text = "在发送端选择「$deviceName」即可开始投屏。",
            style = MaterialTheme.typography.bodySmall,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { viewContext ->
                    SurfaceViewRenderer(viewContext).apply {
                        init(application.runtime.eglContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setEnableHardwareScaler(true)
                        session.attachRenderer(this)
                        renderer = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        val diagnostics by session.diagnostics.collectAsState()
        DiagnosticsBar(text = diagnostics.line())
    }
}

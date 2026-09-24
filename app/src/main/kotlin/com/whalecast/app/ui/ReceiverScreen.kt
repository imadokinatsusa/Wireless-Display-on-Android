package com.whalecast.app.ui

import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whalecast.app.CastReceiverEngine
import com.whalecast.discovery.Beacon
import com.whalecast.discovery.BeaconBroadcaster
import com.whalecast.discovery.ConnectCode
import com.whalecast.session.StatsSnapshot
import com.whalecast.transport.DEFAULT_CAST_PORT
import kotlinx.coroutines.delay

/**
 * 接收端：**进来就自动待命**（开始监听 + 广播连接码），用户不需要点任何按钮。
 *
 * 界面上只留三样东西：连接码、状态、画面 —— 把内部实现（端口、广播、解码器）
 * 全部收在下面，不打扰用户。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf<CastReceiverEngine?>(null) }
    var broadcaster by remember { mutableStateOf<BeaconBroadcaster?>(null) }
    var status by remember { mutableStateOf("正在准备接收…") }
    var stats by remember { mutableStateOf(StatsSnapshot()) }
    var surface by remember { mutableStateOf<Surface?>(null) }

    val sessionCode = remember { ConnectCode.random() }
    val token = remember { randomHexToken() }
    val deviceName = remember { "${Build.MODEL} 的接收端" }

    // 一次装配：监听端口 + 把连接码广播出去，之后无需用户操心
    LaunchedEffect(Unit) {
        val newEngine = CastReceiverEngine(scope, DEFAULT_CAST_PORT)
        engine = newEngine
        newEngine.startListening { status = it }
        val port = newEngine.listeningPort()
        val newBroadcaster = BeaconBroadcaster(
            scope = scope,
            beaconProvider = { Beacon(sessionCode, port, deviceName, token) },
        )
        broadcaster = newBroadcaster
        newBroadcaster.start()
    }

    LaunchedEffect(engine, surface) { engine?.attachSurface(surface) }

    LaunchedEffect(Unit) {
        while (true) {
            engine?.let { stats = it.sessionStats.snapshot.value }
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            broadcaster?.stop()
            engine?.closeBlocking()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("接收端 · 等待投屏") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = ConnectCode.pretty(sessionCode),
                    style = MaterialTheme.typography.displayMedium,
                )
                Text(
                    text = "在发送端点一下这台设备，或输入上面这个码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SurfaceViewBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color.Black),
                onSurfaceChanged = { surface = it },
            )

            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "收到帧 ${stats.framesReceived}｜丢帧 ${stats.framesDropped}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SurfaceViewBox(modifier: Modifier, onSurfaceChanged: (Surface?) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onSurfaceChanged(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            onSurfaceChanged(null)
                        }
                    },
                )
            }
        },
    )
}

/** 一次性令牌：既是连接凭证，也是后续加密握手的材料。 */
private fun randomHexToken(bytes: Int = 8): String =
    (1..bytes).joinToString("") { "%02x".format(kotlin.random.Random.nextInt(256)) }

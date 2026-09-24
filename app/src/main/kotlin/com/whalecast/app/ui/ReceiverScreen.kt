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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.whalecast.app.CastReceiverEngine
import com.whalecast.app.LocalAddress
import com.whalecast.discovery.Beacon
import com.whalecast.discovery.BeaconBroadcaster
import com.whalecast.discovery.ConnectCode
import com.whalecast.session.StatsSnapshot
import com.whalecast.transport.DEFAULT_CAST_PORT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 接收端界面：显示自己的地址 → 开始监听 → 全屏显示投过来的画面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf<CastReceiverEngine?>(null) }
    var status by remember { mutableStateOf("点「开始监听」，把下面这个地址填到手机的发送端。") }
    var stats by remember { mutableStateOf(StatsSnapshot()) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var listening by remember { mutableStateOf(false) }

    // 连接码 + 一次性令牌：发送端念码/输码即可连接，不必手敲 IP
    val sessionCode = remember { ConnectCode.random() }
    val token = remember { randomHexToken() }
    var broadcaster by remember { mutableStateOf<BeaconBroadcaster?>(null) }

    val localIp = remember { LocalAddress.ipv4() }
    val port = remember { DEFAULT_CAST_PORT }
    val deviceName = remember { "${Build.MODEL} 的接收端" }

    // Surface 与配置的到达顺序不确定，两者任一变化都重新尝试装配解码器
    LaunchedEffect(engine, surface) {
        engine?.attachSurface(surface)
    }

    LaunchedEffect(listening) {
        while (listening) {
            engine?.let { stats = it.sessionStats.snapshot.value }
            delay(500)
        }
    }

    // 离开界面必须停掉广播，否则退回首页后还在持续发 UDP
    DisposableEffect(Unit) {
        onDispose { broadcaster?.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("接收端") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("在发送端输入这个连接码", fontWeight = FontWeight.Bold)
                    Text(
                        text = ConnectCode.pretty(sessionCode),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        text = "或手动填地址：${localIp ?: "未检测到局域网 IP（请先连 Wi-Fi）"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("端口：$port", style = MaterialTheme.typography.bodyMedium)
                }
            }

            SurfaceViewBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Color.Black),
                onSurfaceChanged = { surface = it },
            )

            Button(
                onClick = {
                    if (listening) {
                        scope.launch {
                            engine?.stop()
                            engine = null
                            broadcaster?.stop()
                            broadcaster = null
                            listening = false
                            status = "已停止监听。"
                        }
                    } else {
                        val newEngine = CastReceiverEngine(scope, port)
                        engine = newEngine
                        // 把连接码广播到局域网，发送端输入码即可找到本机
                        val newBroadcaster = BeaconBroadcaster(
                            scope = scope,
                            beaconProvider = {
                                Beacon(
                                    code = sessionCode,
                                    tcpPort = port,
                                    deviceName = deviceName,
                                    token = token,
                                )
                            },
                        )
                        broadcaster = newBroadcaster
                        newBroadcaster.start()
                        listening = true
                        newEngine.startListening { status = it }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (listening) "停止监听" else "开始监听")
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("状态", fontWeight = FontWeight.Bold)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "收到帧 ${stats.framesReceived}｜丢帧 ${stats.framesDropped}" +
                            "｜包 ${stats.packetsReceived}｜已收 ${stats.bytesReceived / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** 一次性令牌：既是连接凭证，也是后续加密握手的材料。 */
private fun randomHexToken(bytes: Int = 8): String =
    (1..bytes).joinToString("") { "%02x".format(kotlin.random.Random.nextInt(256)) }

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

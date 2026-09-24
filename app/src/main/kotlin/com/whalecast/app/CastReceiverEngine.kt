package com.whalecast.app

import android.view.Surface
import com.whalecast.media.android.H264Decoder
import com.whalecast.protocol.ConfigPacketizer
import com.whalecast.protocol.FramePacketParser
import com.whalecast.protocol.FrameReassembler
import com.whalecast.protocol.MessageType
import com.whalecast.protocol.PacketParseResult
import com.whalecast.protocol.PacketPeek
import com.whalecast.protocol.ReassemblyEvent
import com.whalecast.protocol.VideoConfig
import com.whalecast.session.SessionStats
import com.whalecast.transport.DEFAULT_CAST_PORT
import com.whalecast.transport.TcpServer
import com.whalecast.transport.TcpTransport
import com.whalecast.transport.TcpTransports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 接收端引擎：监听端口 → 等发送端连入 → 收配置 → 建硬解 → 渲染到 Surface。
 *
 * Surface（由 SurfaceView 异步给出）与配置（由网络异步到达）的先后顺序不确定，
 * 因此两者都就绪时才创建解码器 —— 这是接收端最容易出错的时序点。
 */
class CastReceiverEngine(
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_CAST_PORT,
    private val stats: SessionStats = SessionStats(),
) {

    private var server: TcpServer? = null
    private var transport: TcpTransport? = null
    private var decoder: H264Decoder? = null
    private val reassembler = FrameReassembler()

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var pendingConfig: VideoConfig? = null

    val sessionStats: SessionStats = stats

    var isListening: Boolean = false
        private set

    var isStreaming: Boolean = false
        private set

    var status: String = "尚未开始"
        private set

    val connectedPeer: String? get() = transport?.remoteAddress

    fun listeningPort(): Int = server?.localPort ?: port

    fun attachSurface(newSurface: Surface?) {
        surface = newSurface
        if (newSurface == null) {
            decoder?.release()
            decoder = null
        } else {
            tryConfigureDecoder()
        }
    }

    fun startListening(onStatus: (String) -> Unit) {
        if (server != null) return
        val server = TcpTransports.listen(port, scope)
        this.server = server
        isListening = true
        update(onStatus, "正在监听端口 ${server.localPort}，等待发送端连入…")

        scope.launch {
            val transport = runCatching { server.awaitClient() }.getOrNull() ?: return@launch
            transport.start()
            this@CastReceiverEngine.transport = transport
            isStreaming = true
            update(onStatus, "已连接 ${transport.remoteAddress}，等待配置…")
            receiveLoop(transport, onStatus)
        }
    }

    private suspend fun receiveLoop(transport: TcpTransport, onStatus: (String) -> Unit) {
        transport.incoming.collect { bytes ->
            when (PacketPeek.type(bytes)) {
                MessageType.VIDEO_CONFIG -> {
                    ConfigPacketizer.parse(bytes)?.let { config ->
                        pendingConfig = config
                        tryConfigureDecoder()
                        update(onStatus, "收到配置 ${config.width}×${config.height}，开始解码")
                    }
                }

                MessageType.VIDEO_FRAME -> {
                    val packet = (FramePacketParser.parse(bytes) as? PacketParseResult.Success)?.packet
                        ?: return@collect
                    stats.onPacketReceived(bytes.size)
                    reassembler.onPacket(packet).forEach { event ->
                        when (event) {
                            is ReassemblyEvent.FrameComplete -> {
                                stats.onFrameReceived(event.frame.size)
                                decoder?.decode(event.frame)
                            }

                            is ReassemblyEvent.FrameDropped -> stats.onFrameDropped()
                            ReassemblyEvent.Ignored -> Unit
                        }
                    }
                }

                else -> Unit
            }
        }
        isStreaming = false
        update(onStatus, transport.failureReason ?: "发送端已断开")
    }

    private fun tryConfigureDecoder() {
        val config = pendingConfig ?: return
        val surface = surface ?: return
        if (decoder != null) return
        decoder = H264Decoder(surface).also { it.configure(config) }
    }

    private fun update(onStatus: (String) -> Unit, message: String) {
        status = message
        onStatus(message)
    }

    suspend fun stop() {
        transport?.close()
        transport = null
        server?.close()
        server = null
        decoder?.release()
        decoder = null
        reassembler.reset()
        pendingConfig = null
        isListening = false
        isStreaming = false
        status = "已停止"
    }
}

package com.whalecast.app

import android.media.projection.MediaProjection
import com.whalecast.media.android.ScreenCaptureSource
import com.whalecast.protocol.ConfigPacketizer
import com.whalecast.protocol.EncodedFrame
import com.whalecast.protocol.FramePacketizer
import com.whalecast.session.SessionStats
import com.whalecast.transport.TcpTransport
import com.whalecast.transport.TcpTransports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 发送端引擎：连接接收端 → 抓屏硬编 → 打包装箱发出。
 *
 * 只做编排：采集在 `:core:media-android`，协议在 `:core:protocol`，传输在 `:core:transport`。
 * 因此它既能在真机上跑，也能被替换成合成源做端到端测试。
 */
class CastSenderEngine(
    private val scope: CoroutineScope,
    private val projection: MediaProjection,
    private val spec: CaptureSpec.Spec,
    private val stats: SessionStats = SessionStats(),
) {

    private var transport: TcpTransport? = null
    private var capture: ScreenCaptureSource? = null
    private var job: Job? = null

    val sessionStats: SessionStats = stats

    val isRunning: Boolean get() = job != null

    val connectedPeer: String? get() = transport?.remoteAddress

    suspend fun start(host: String, port: Int, onStatus: (String) -> Unit) {
        if (job != null) return

        onStatus("正在连接 $host:$port …")
        val transport = TcpTransports.connect(host, port, scope)
        transport.start()
        this.transport = transport
        onStatus("已连接 ${transport.remoteAddress}，开始采集 ${spec.width}×${spec.height}")

        val capture = ScreenCaptureSource(
            projection = projection,
            scope = scope,
            width = spec.width,
            height = spec.height,
            densityDpi = spec.densityDpi,
            bitRate = spec.bitRate,
        )
        this.capture = capture
        capture.start()

        job = scope.launch {
            var configSent = false
            capture.encodedFrames.collect { frame ->
                // 配置必须先于首个关键帧抵达接收端
                if (!configSent) {
                    capture.latestConfig?.let { config ->
                        transport.send(ConfigPacketizer.packetize(config, SESSION_ID))
                        configSent = true
                    }
                }
                sendFrame(transport, frame)
            }
        }
    }

    private suspend fun sendFrame(transport: TcpTransport, frame: EncodedFrame) {
        FramePacketizer.packetize(frame).forEach { packet ->
            transport.send(packet)
                .onSuccess { stats.onPacketSent(packet.size) }
                .onFailure { stats.onSendFailure() }
        }
        stats.onFrameSent(frame.size)
    }

    /** 编码器状态，供 UI 展示"采集是否健康"。 */
    fun captureStats(): Triple<Long, Long, Int>? {
        val capture = capture ?: return null
        return Triple(capture.encodedFrameCount, capture.droppedFrameCount, capture.configuredBitRate)
    }

    suspend fun stop() {
        job?.cancel()
        job = null
        capture?.stop()
        capture = null
        transport?.close()
        transport = null
    }

    private companion object {
        const val SESSION_ID = 1
    }
}

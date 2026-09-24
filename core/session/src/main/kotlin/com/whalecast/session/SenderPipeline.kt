package com.whalecast.session

import com.whalecast.media.VideoEncoder
import com.whalecast.media.VideoSource
import com.whalecast.protocol.FramePacketizer
import com.whalecast.protocol.Protocol
import com.whalecast.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 发送端管线：`VideoSource → VideoEncoder → 切包 → Transport`。
 *
 * 纯接线，不含任何 Android 依赖 —— 因此它既能在真机上跑 MediaProjection，
 * 也能在 JVM 单测里跑合成画面。这条性质就是切片 01 的价值所在。
 */
class SenderPipeline(
    private val scope: CoroutineScope,
    private val source: VideoSource,
    private val encoder: VideoEncoder,
    private val transport: Transport,
    private val stats: SessionStats = SessionStats(),
    private val maxPayloadSize: Int = Protocol.DEFAULT_MAX_PAYLOAD,
) {

    private var job: Job? = null

    val statsSnapshot = stats.snapshot

    suspend fun start() {
        if (job != null) return
        job = scope.launch {
            source.frames.collect { frame ->
                val encoded = encoder.encode(frame)
                val packets = FramePacketizer.packetize(encoded, maxPayloadSize)
                packets.forEach { packet ->
                    transport.send(packet)
                        .onSuccess { stats.onPacketSent(packet.size) }
                        .onFailure { stats.onSendFailure() }
                }
                stats.onFrameSent(encoded.size)
            }
        }
        source.start()
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        source.stop()
    }

    /** 会话统计（含 UI 需要的实时快照）。 */
    fun stats(): SessionStats = stats
}

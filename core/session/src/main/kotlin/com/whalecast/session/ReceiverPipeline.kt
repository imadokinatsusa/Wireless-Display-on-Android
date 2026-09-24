package com.whalecast.session

import com.whalecast.media.VideoDecoder
import com.whalecast.media.VideoSink
import com.whalecast.protocol.FramePacketParser
import com.whalecast.protocol.FrameReassembler
import com.whalecast.protocol.PacketParseResult
import com.whalecast.protocol.ReassemblyEvent
import com.whalecast.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 接收端管线：`Transport → 解包 → 重组 → VideoDecoder → VideoSink`。
 *
 * 畸形包只计数不崩溃；缺包的帧被整帧丢弃（宁可丢帧也不把残缺数据喂给解码器）。
 */
class ReceiverPipeline(
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val decoder: VideoDecoder,
    private val sink: VideoSink,
    private val stats: SessionStats = SessionStats(),
) {

    private val reassembler = FrameReassembler()

    private var job: Job? = null

    val statsSnapshot = stats.snapshot

    fun start() {
        if (job != null) return
        job = scope.launch {
            transport.incoming.collect { bytes ->
                when (val parsed = FramePacketParser.parse(bytes)) {
                    is PacketParseResult.Success -> {
                        stats.onPacketReceived(bytes.size)
                        reassembler.onPacket(parsed.packet).forEach { event ->
                            when (event) {
                                is ReassemblyEvent.FrameComplete -> {
                                    stats.onFrameReceived(event.frame.size)
                                    sink.onFrame(decoder.decode(event.frame))
                                }

                                is ReassemblyEvent.FrameDropped -> stats.onFrameDropped()
                                ReassemblyEvent.Ignored -> Unit
                            }
                        }
                    }

                    is PacketParseResult.Malformed -> stats.onMalformedPacket(parsed.reason)
                }
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        reassembler.reset()
    }

    fun stats(): SessionStats = stats
}

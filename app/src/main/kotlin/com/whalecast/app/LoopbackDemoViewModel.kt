package com.whalecast.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whalecast.media.PassthroughVideoDecoder
import com.whalecast.media.PassthroughVideoEncoder
import com.whalecast.media.RecordingVideoSink
import com.whalecast.media.SyntheticVideoSource
import com.whalecast.session.ReceiverPipeline
import com.whalecast.session.SenderPipeline
import com.whalecast.session.SessionStats
import com.whalecast.session.StatsSnapshot
import com.whalecast.transport.LoopbackHub
import com.whalecast.transport.Transport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DemoUiState(
    val running: Boolean = false,
    val senderColor: Int = 0xFF202124.toInt(),
    val receiverColor: Int = 0xFF202124.toInt(),
    val senderFrameSeq: Long = 0L,
    val receiverFrameSeq: Long = 0L,
    val senderStats: StatsSnapshot = StatsSnapshot(),
    val receiverStats: StatsSnapshot = StatsSnapshot(),
    val dropRate: Float = 0f,
    val latencyMillis: Long = 0L,
    val note: String =
        "点「开始投屏」：合成画面会先被切成带包头的包，经环回通道送达接收端，" +
            "再重组、解码、渲染。拖动丢包率与延迟滑块，可以观察协议层的真实反应。",
)

/**
 * 切片 01 的环回 demo：把发送端与接收端放在**同一个进程**里接线，
 * 于是一台设备上就能看到"画面经协议层流动"的全过程。
 *
 * 它刻意不依赖 MediaProjection / MediaCodec —— 那些是工单 04/05 的事，
 * 届时只需把 [SyntheticVideoSource] 换成真实采集实现，管线本身不动。
 */
class LoopbackDemoViewModel : ViewModel() {

    private val hub = LoopbackHub(viewModelScope, seed = 20_250_101L)

    private val _state = MutableStateFlow(DemoUiState())
    val state: StateFlow<DemoUiState> = _state.asStateFlow()

    private var senderPipeline: SenderPipeline? = null
    private var receiverPipeline: ReceiverPipeline? = null
    private var senderTransport: Transport? = null
    private var receiverTransport: Transport? = null
    private val collectors = mutableListOf<Job>()

    fun setDropRate(value: Float) {
        hub.dropRate = value.toDouble()
        _state.update { it.copy(dropRate = value) }
    }

    fun setLatency(millis: Long) {
        hub.latencyMillis = millis
        _state.update { it.copy(latencyMillis = millis) }
    }

    fun start() {
        if (_state.value.running) return

        val (senderSide, receiverSide) = hub.createPair()
        senderTransport = senderSide
        receiverTransport = receiverSide

        val source = SyntheticVideoSource(viewModelScope, fps = 30)
        val sink = RecordingVideoSink(capacity = 8)
        val senderStats = SessionStats()
        val receiverStats = SessionStats()

        val sender = SenderPipeline(
            scope = viewModelScope,
            source = source,
            encoder = PassthroughVideoEncoder(keyframeInterval = 30),
            transport = senderSide,
            stats = senderStats,
        )
        val receiver = ReceiverPipeline(
            scope = viewModelScope,
            transport = receiverSide,
            decoder = PassthroughVideoDecoder(),
            sink = sink,
            stats = receiverStats,
        )
        senderPipeline = sender
        receiverPipeline = receiver

        // viewModelScope 使用 Main.immediate，因此这里按顺序订阅、再启动，
        // 不会漏掉最初几帧。
        collectors += viewModelScope.launch {
            source.frames.collect { frame ->
                _state.update { it.copy(senderColor = frame.argb, senderFrameSeq = frame.frameSeq) }
            }
        }
        collectors += viewModelScope.launch {
            sink.recentFrames.collect { frames ->
                val latest = frames.lastOrNull() ?: return@collect
                _state.update { it.copy(receiverColor = latest.argb, receiverFrameSeq = latest.frameSeq) }
            }
        }
        collectors += viewModelScope.launch {
            senderStats.snapshot.collect { stats -> _state.update { it.copy(senderStats = stats) } }
        }
        collectors += viewModelScope.launch {
            receiverStats.snapshot.collect { stats -> _state.update { it.copy(receiverStats = stats) } }
        }
        collectors += viewModelScope.launch {
            senderSide.start()
            receiverSide.start()
            receiver.start()
            sender.start()
            _state.update {
                it.copy(running = true, note = "管线运行中：发送端产帧 → 切包 → 环回通道 → 重组 → 渲染。")
            }
        }
    }

    fun stop() {
        if (!_state.value.running) return
        viewModelScope.launch {
            senderPipeline?.stop()
            receiverPipeline?.stop()
            senderTransport?.close()
            receiverTransport?.close()
            collectors.forEach { it.cancel() }
            collectors.clear()
            senderPipeline = null
            receiverPipeline = null
            senderTransport = null
            receiverTransport = null
            _state.update { it.copy(running = false, note = "已停止。重新开始会建立一条全新的环回会话。") }
        }
    }
}

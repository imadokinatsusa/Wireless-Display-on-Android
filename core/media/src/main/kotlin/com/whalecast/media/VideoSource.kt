package com.whalecast.media

import com.whalecast.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * **视频源接缝**：产出待编码帧的边界。
 *
 * 生产实现 = MediaProjection 虚拟屏（工单 04）；测试与 demo 实现 = [SyntheticVideoSource]。
 * 接缝存在的意义：让整条管线在**没有屏幕、没有 Android** 的 JVM 上也能端到端跑通。
 */
interface VideoSource {

    /** 帧流。单消费者语义。 */
    val frames: Flow<VideoFrame>

    suspend fun start()

    suspend fun stop()
}

/**
 * 合成视频源：按固定帧率产出一串颜色渐变的 1×1 帧。
 *
 * 帧率用协程 `delay` 控制，因此在 `runTest` 的虚拟时间里可瞬时推进；
 * 时间戳由帧号推导（`seq * 90000 / fps`），不依赖墙上时钟，测试结果确定。
 */
class SyntheticVideoSource(
    private val scope: CoroutineScope,
    private val fps: Int = 30,
    private val startFrameSeq: Long = 1L,
) : VideoSource {

    init {
        require(fps in 1..120) { "fps 必须在 1..120，实际 $fps" }
    }

    private val _frames = MutableSharedFlow<VideoFrame>(extraBufferCapacity = 32)

    override val frames: Flow<VideoFrame> = _frames.asSharedFlow()

    private var job: Job? = null

    /** 已产出的帧数，供 UI 与测试观察。 */
    var producedCount: Long = 0L
        private set

    override suspend fun start() {
        if (job != null) return
        job = scope.launch {
            var seq = startFrameSeq
            while (isActive) {
                _frames.emit(frameFor(seq))
                producedCount = seq - startFrameSeq + 1
                seq++
                delay(frameIntervalMillis)
            }
        }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    private val frameIntervalMillis: Long
        get() = (1_000L / fps).coerceAtLeast(1L)

    private fun frameFor(seq: Long): VideoFrame = VideoFrame(
        width = 1,
        height = 1,
        frameSeq = seq,
        timestampTicks = seq * Protocol.TICKS_PER_SECOND / fps,
        pixels = SyntheticFrameCodec.colorFor(seq),
    )
}

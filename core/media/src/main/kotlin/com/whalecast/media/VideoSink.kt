package com.whalecast.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **视频汇接缝**：消费解码后画面的边界。
 *
 * 生产实现 = SurfaceView/TextureView 渲染（工单 05）；
 * 测试与 demo 实现 = [RecordingVideoSink]（记录帧元数据，供断言与 UI 观察）。
 */
interface VideoSink {

    suspend fun onFrame(frame: VideoFrame)
}

/**
 * 只记录不渲染的汇：保留最近 [capacity] 帧，并统计收到的帧数。
 *
 * 断言写在"收到了什么帧"上，而不是"某个内部变量变了"，符合规格的测试标准。
 */
class RecordingVideoSink(private val capacity: Int = 120) : VideoSink {

    init {
        require(capacity >= 1) { "capacity 至少为 1，实际 $capacity" }
    }

    private val _recentFrames = MutableStateFlow<List<VideoFrame>>(emptyList())

    /** 最近收到的帧（最新在末尾），UI 可以直接订阅。 */
    val recentFrames: StateFlow<List<VideoFrame>> = _recentFrames.asStateFlow()

    var receivedCount: Long = 0L
        private set

    /** 最近一帧，UI 画它即可看到"画面在动"。 */
    val latestFrame: VideoFrame?
        get() = _recentFrames.value.lastOrNull()

    override suspend fun onFrame(frame: VideoFrame) {
        receivedCount++
        _recentFrames.value = (_recentFrames.value + frame).takeLast(capacity)
    }

    fun clear() {
        receivedCount = 0
        _recentFrames.value = emptyList()
    }
}

package com.whalecast.media

import com.whalecast.protocol.EncodedFrame

/**
 * 编码接缝。生产实现 = MediaCodec 硬编 H.264（工单 04）。
 */
interface VideoEncoder {

    fun encode(frame: VideoFrame): EncodedFrame
}

/**
 * 解码接缝。生产实现 = MediaCodec 硬解 + Surface 输出（工单 05）。
 */
interface VideoDecoder {

    fun decode(frame: EncodedFrame): VideoFrame
}

/**
 * 切片 01 的**直通编码器**：不做真实压缩，但完整保留
 * 时间戳、帧序号与关键帧节奏这些协议语义 —— 因此重组、丢帧、
 * 关键帧请求等后续逻辑都能在它上面先验证。
 */
class PassthroughVideoEncoder(
    private val sessionId: Int = DEFAULT_SESSION_ID,
    private val keyframeInterval: Long = 30L,
) : VideoEncoder {

    init {
        require(sessionId >= 0) { "sessionId 不能为负" }
        require(keyframeInterval >= 1) { "keyframeInterval 至少为 1" }
    }

    override fun encode(frame: VideoFrame): EncodedFrame = EncodedFrame(
        sessionId = sessionId,
        frameSeq = frame.frameSeq,
        timestampTicks = frame.timestampTicks,
        isKeyframe = frame.frameSeq % keyframeInterval == 1L,
        data = frame.pixels,
    )

    companion object {
        const val DEFAULT_SESSION_ID: Int = 1
    }
}

/** 与 [PassthroughVideoEncoder] 配对的直通解码器。 */
class PassthroughVideoDecoder(
    private val width: Int = 1,
    private val height: Int = 1,
) : VideoDecoder {

    override fun decode(frame: EncodedFrame): VideoFrame = VideoFrame(
        width = width,
        height = height,
        frameSeq = frame.frameSeq,
        timestampTicks = frame.timestampTicks,
        pixels = frame.data,
    )
}

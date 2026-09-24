package com.whalecast.media

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntheticMediaTest {

    @Test
    fun `合成颜色由帧号决定且是纯函数`() {
        assertEquals(
            SyntheticFrameCodec.colorFor(7).toList(),
            SyntheticFrameCodec.colorFor(7).toList(),
            "同一帧号必须得到同一颜色",
        )
        val a = SyntheticFrameCodec.colorFor(9)
        val b = SyntheticFrameCodec.colorFor(10)
        assertTrue(a.toList() != b.toList(), "相邻帧号应产生不同颜色，画面才会动")
    }

    @Test
    fun `颜色通道始终在合法范围`() {
        (1L..400L).forEach { seq ->
            val pixels = SyntheticFrameCodec.colorFor(seq)
            assertEquals(SyntheticFrameCodec.PIXEL_BYTES, pixels.size)
            listOf(
                SyntheticFrameCodec.redOf(pixels),
                SyntheticFrameCodec.greenOf(pixels),
                SyntheticFrameCodec.blueOf(pixels),
            ).forEach { channel ->
                assertTrue(channel in 0..255, "帧 $seq 的通道值越界: $channel")
            }
            assertEquals(255, SyntheticFrameCodec.alphaOf(pixels))
        }
    }

    @Test
    fun `合成源按帧率产出帧且时间戳递增`() = runTest {
        val source = SyntheticVideoSource(backgroundScope, fps = 30)
        val received = mutableListOf<VideoFrame>()
        backgroundScope.launch { source.frames.collect { received += it } }
        runCurrent()

        source.start()
        advanceTimeBy(1_000)
        runCurrent()
        source.stop()

        // 30fps 跑 1 秒：首帧在 0ms，故约 31 帧（含边界）
        assertTrue(received.size >= 30, "1 秒 30fps 至少应产出 30 帧，实际 ${received.size}")
        val seqs = received.map { it.frameSeq }
        assertEquals(seqs.sorted(), seqs, "帧序号必须单调递增")
        val timestamps = received.map { it.timestampTicks }
        assertEquals(timestamps.sorted(), timestamps, "时间戳必须单调递增")
        assertEquals(
            3_000L,
            received[1].timestampTicks - received[0].timestampTicks,
            "30fps 下相邻帧相差 3000 ticks（90kHz）",
        )
    }

    @Test
    fun `直通编码解码后帧内容保持一致`() {
        val frame = VideoFrame(1, 1, 5, 15_000, SyntheticFrameCodec.colorFor(5))
        val encoder = PassthroughVideoEncoder(sessionId = 3, keyframeInterval = 5)
        val decoder = PassthroughVideoDecoder()

        val encoded = encoder.encode(frame)
        assertEquals(3, encoded.sessionId)
        assertEquals(frame.frameSeq, encoded.frameSeq)
        assertEquals(frame.timestampTicks, encoded.timestampTicks)

        val decoded = decoder.decode(encoded)
        assertEquals(frame.frameSeq, decoded.frameSeq)
        assertEquals(frame.argb, decoded.argb, "解码后颜色应与编码前一致")
    }

    @Test
    fun `编码器按间隔标记关键帧`() {
        val encoder = PassthroughVideoEncoder(keyframeInterval = 30)
        val keyframes = (1L..60L).filter { seq ->
            encoder.encode(VideoFrame(1, 1, seq, seq * 3_000, SyncPixels)).isKeyframe
        }
        assertEquals(listOf(1L, 31L), keyframes, "keyframeInterval=30 时 seq=1 与 seq=31 应为关键帧")
    }

    @Test
    fun `记录汇只保留最近若干帧并统计总数`() = runTest {
        val sink = RecordingVideoSink(capacity = 3)
        (1L..10L).forEach { seq ->
            sink.onFrame(VideoFrame(1, 1, seq, seq * 3_000, SyntheticFrameCodec.colorFor(seq)))
        }
        assertEquals(10L, sink.receivedCount)
        assertEquals(3, sink.recentFrames.value.size)
        assertEquals(10L, sink.latestFrame?.frameSeq, "最新帧应是最后写入的那一帧")

        sink.clear()
        assertEquals(0L, sink.receivedCount)
        assertTrue(sink.recentFrames.value.isEmpty())
    }

    private companion object {
        val SyncPixels = byteArrayOf(1, 2, 3, 4)
    }
}

package com.whalecast.session

import com.whalecast.media.PassthroughVideoDecoder
import com.whalecast.media.PassthroughVideoEncoder
import com.whalecast.media.RecordingVideoSink
import com.whalecast.media.SyntheticFrameCodec
import com.whalecast.media.SyntheticVideoSource
import com.whalecast.transport.LoopbackHub
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 切片 01 的端到端验收：合成画面经协议层切包、走环回通道、在接收端重组并渲染。
 *
 * 这是整个切片唯一一个"贯穿所有层"的测试 —— 它绿了，说明接缝的形状是对的。
 */
class LoopbackEndToEndTest {

    @Test
    fun `合成画面经环回通道在接收端完整重组并渲染`() = runTest(UnconfinedTestDispatcher()) {
        val hub = LoopbackHub(backgroundScope)
        val (senderSide, receiverSide) = hub.createPair()
        senderSide.start()
        receiverSide.start()

        val source = SyntheticVideoSource(backgroundScope, fps = 30)
        val sink = RecordingVideoSink(capacity = 30)
        val sender = SenderPipeline(
            scope = backgroundScope,
            source = source,
            encoder = PassthroughVideoEncoder(keyframeInterval = 30),
            transport = senderSide,
        )
        val receiver = ReceiverPipeline(
            scope = backgroundScope,
            transport = receiverSide,
            decoder = PassthroughVideoDecoder(),
            sink = sink,
        )

        receiver.start()
        sender.start()

        advanceTimeBy(1_000)
        runCurrent()
        sender.stop()
        advanceTimeBy(200) // 让在途消息投递完
        runCurrent()
        receiver.stop()

        val sentStats = sender.statsSnapshot.value
        assertTrue(sentStats.framesSent >= 30, "1 秒 30fps 至少应发出 30 帧，实际 ${sentStats.framesSent}")
        assertTrue(sink.receivedCount > 0, "接收端应收到帧")
        assertEquals(0L, receiver.statsSnapshot.value.malformedPackets, "无丢包无延迟时不应出现畸形包")
        assertEquals(0L, receiver.statsSnapshot.value.framesDropped, "链路干净时不应丢帧")

        val latest = assertNotNull(sink.latestFrame, "接收端应至少渲染一帧")
        val expected = SyntheticFrameCodec.colorFor(latest.frameSeq)
        assertEquals(
            expected.toList(),
            latest.pixels.toList(),
            "接收端画面颜色必须与发送端按同一帧号算出的颜色一致",
        )
    }

    @Test
    fun `注入丢包时接收帧数少于发送帧数且不产生脏帧`() = runTest(UnconfinedTestDispatcher()) {
        val hub = LoopbackHub(backgroundScope, seed = 11L).apply { dropRate = 0.2 }
        val (senderSide, receiverSide) = hub.createPair()
        senderSide.start()
        receiverSide.start()

        val source = SyntheticVideoSource(backgroundScope, fps = 30)
        val sink = RecordingVideoSink(capacity = 60)
        val sender = SenderPipeline(backgroundScope, source, PassthroughVideoEncoder(), senderSide)
        val receiver = ReceiverPipeline(backgroundScope, receiverSide, PassthroughVideoDecoder(), sink)

        receiver.start()
        sender.start()
        advanceTimeBy(1_000)
        runCurrent()
        sender.stop()
        advanceTimeBy(200)
        runCurrent()
        receiver.stop()

        val framesSent = sender.statsSnapshot.value.framesSent
        val receiverStats = receiver.statsSnapshot.value
        assertTrue(hub.droppedCount > 0, "seed=11 且丢包率 0.2 时应确实丢弃了消息")
        assertTrue(
            sink.receivedCount < framesSent,
            "丢包时接收帧数应少于发送帧数：收到 ${sink.receivedCount}，发出 $framesSent",
        )
        assertTrue(receiverStats.framesDropped > 0, "缺包的帧必须被整帧丢弃并计数")
        assertEquals(0L, receiverStats.malformedPackets, "丢包不等于畸形包，不应混为一谈")
    }

    @Test
    fun `畸形包被拒绝并计数，会话不中断`() = runTest(UnconfinedTestDispatcher()) {
        val hub = LoopbackHub(backgroundScope)
        val (senderSide, receiverSide) = hub.createPair()
        senderSide.start()
        receiverSide.start()

        val sink = RecordingVideoSink()
        val receiver = ReceiverPipeline(
            scope = backgroundScope,
            transport = receiverSide,
            decoder = PassthroughVideoDecoder(),
            sink = sink,
        )
        receiver.start()

        // 故意发一段太短的垃圾数据
        senderSide.send(byteArrayOf(1, 2, 3)).getOrThrow()
        advanceUntilIdle()

        val stats = receiver.statsSnapshot.value
        assertEquals(1L, stats.malformedPackets, "畸形包应被计数")
        assertNotNull(stats.lastMalformedReason, "应记录畸形原因供 UI 展示")
        assertEquals(0L, sink.receivedCount, "畸形包不得被当成帧渲染")
    }

    @Test
    fun `延迟注入不会导致丢帧只是延后到达`() = runTest(UnconfinedTestDispatcher()) {
        val hub = LoopbackHub(backgroundScope).apply { latencyMillis = 50 }
        val (senderSide, receiverSide) = hub.createPair()
        senderSide.start()
        receiverSide.start()

        val source = SyntheticVideoSource(backgroundScope, fps = 30)
        val sink = RecordingVideoSink(capacity = 120)
        val sender = SenderPipeline(backgroundScope, source, PassthroughVideoEncoder(), senderSide)
        val receiver = ReceiverPipeline(backgroundScope, receiverSide, PassthroughVideoDecoder(), sink)

        receiver.start()
        sender.start()
        advanceTimeBy(1_000)
        runCurrent()
        sender.stop()

        val receivedRightAfterStop = sink.receivedCount
        advanceTimeBy(200) // 让 50ms 延迟的包追上
        runCurrent()
        receiver.stop()

        assertTrue(
            sink.receivedCount > receivedRightAfterStop,
            "延迟的消息应在虚拟时间推进后陆续到达",
        )
        assertEquals(0L, receiver.statsSnapshot.value.framesDropped, "纯延迟不应造成丢帧")
    }
}

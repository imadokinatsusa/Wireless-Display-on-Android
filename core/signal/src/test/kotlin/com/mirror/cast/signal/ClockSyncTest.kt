package com.mirror.cast.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 时钟标定与时钟消息编解码。
 *
 * 这两件事都"错了也不会崩" —— 只会让端到端延迟读数悄悄偏掉，
 * 那是最难发现的一类错误，所以这里把边界钉死。
 */
class ClockSyncTest {

    @Test
    fun perfectClockAndInstantLink() {
        val result = ClockSync.estimate(t0 = 1000, t1 = 1000, t2 = 1000, t3 = 1000)
        assertEquals(0L, result.offsetMillis)
        assertEquals(0L, result.roundTripMillis)
    }

    @Test
    fun receiverAheadBy500With20msOneWay() {
        // 接收端比发送端快 500ms；单程 20ms
        val result = ClockSync.estimate(t0 = 0, t1 = 520, t2 = 520, t3 = 40)
        assertEquals(500L, result.offsetMillis)
        assertEquals(40L, result.roundTripMillis)
    }

    @Test
    fun receiverBehindBy300() {
        // 接收端比发送端慢 300ms；单程 20ms
        val result = ClockSync.estimate(t0 = 100_000, t1 = 99_720, t2 = 99_720, t3 = 100_040)
        assertEquals(-300L, result.offsetMillis)
        assertEquals(40L, result.roundTripMillis)
    }

    @Test
    fun receiverProcessingTimeIsExcludedFromRoundTrip() {
        // 接收端花 30ms 处理：往返时延里不该算进去，偏差也不该被它带歪
        val result = ClockSync.estimate(t0 = 0, t1 = 100, t2 = 130, t3 = 250)
        assertEquals(-10L, result.offsetMillis)
        assertEquals(220L, result.roundTripMillis)
    }

    @Test
    fun clockMessagesRoundTripThroughCodec() {
        val probe = SignalingMessage.ClockProbe(t0 = 1_726_000_000_123L)
        assertEquals(probe, decode(encode(probe)))

        val reply = SignalingMessage.ClockReply(t0 = 1_726_000_000_123L, t1 = 1_726_000_000_200L, t2 = 1_726_000_000_205L)
        assertEquals(reply, decode(encode(reply)))

        val base = SignalingMessage.ClockBase(offsetMillis = -1_234L)
        assertEquals(base, decode(encode(base)))
    }

    @Test
    fun malformedClockMessagesAreRejected() {
        assertNull(SignalingCodec.decode("CLOCK_PROBE\tabc".toByteArray()))
        assertNull(SignalingCodec.decode("CLOCK_PROBE\t".toByteArray()))
        // 应答少一个时戳
        assertNull(SignalingCodec.decode("CLOCK_REPLY\t1\t2".toByteArray()))
        assertNull(SignalingCodec.decode("CLOCK_REPLY\t1\tx\t3".toByteArray()))
        assertNull(SignalingCodec.decode("CLOCK_BASE\tnope".toByteArray()))
    }

    @Test
    fun clockPrefixesDoNotCollideWithOtherMessages() {
        // CLOCK_PROBE / CLOCK_REPLY / CLOCK_BASE 前缀互不吞并，也不会被别的类型误认
        val decoded = decode(encode(SignalingMessage.ClockReply(t0 = 7, t1 = 8, t2 = 9)))
        assertTrue(decoded is SignalingMessage.ClockReply)
    }

    private fun encode(message: SignalingMessage): ByteArray = SignalingCodec.encode(message)

    private fun decode(bytes: ByteArray): SignalingMessage? = SignalingCodec.decode(bytes)
}

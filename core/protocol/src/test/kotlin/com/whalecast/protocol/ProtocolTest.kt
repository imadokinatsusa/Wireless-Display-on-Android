package com.whalecast.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun `一秒纳秒换算为 90kHz 时钟的一秒`() {
        assertEquals(90_000L, Protocol.ticksFromNanos(1_000_000_000L))
        assertEquals(90L, Protocol.ticksFromNanos(1_000_000L))
    }

    @Test
    fun `纳秒换算在 nanoTime 量级不会溢出`() {
        val nanos = System.nanoTime() + 3_600_000_000_000L // 再加一小时，确保量级足够大
        val ticks = Protocol.ticksFromNanos(nanos)
        assertTrue(ticks > 0, "换算结果应为正数，实际 $ticks")
        val millisPart = nanos / 1_000_000L * 90L
        assertTrue(
            ticks >= millisPart && ticks - millisPart < 90,
            "换算应等于毫秒部分加亚毫秒补充：ticks=$ticks millisPart=$millisPart",
        )
    }

    @Test
    fun `时钟换算可以往返`() {
        val nanos = 12_345_678_900L
        val ticks = Protocol.ticksFromNanos(nanos)
        val back = Protocol.nanosFromTicks(ticks)
        assertTrue(kotlin.math.abs(back - nanos) < 12_000, "往返误差应小于一个 tick，实际差 ${back - nanos}ns")
    }

    @Test
    fun `消息类型按 code 往返且未知类型返回 null`() {
        MessageType.entries.forEach { type ->
            assertEquals(type, MessageType.fromCode(type.code))
        }
        assertNull(MessageType.fromCode(0x7F))
    }

    @Test
    fun `flags 掩码判断正确`() {
        val flags = FrameFlags.KEYFRAME or FrameFlags.FIRST_PACKET
        assertTrue(FrameFlags.has(flags, FrameFlags.KEYFRAME))
        assertTrue(FrameFlags.has(flags, FrameFlags.FIRST_PACKET))
        assertTrue(!FrameFlags.has(flags, FrameFlags.LAST_PACKET))
    }
}

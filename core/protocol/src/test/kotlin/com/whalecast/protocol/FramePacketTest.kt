package com.whalecast.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FramePacketTest {

    private fun frameOf(size: Int, seq: Long, isKeyframe: Boolean = false) = EncodedFrame(
        sessionId = 7,
        frameSeq = seq,
        timestampTicks = Protocol.TICKS_PER_SECOND * seq,
        isKeyframe = isKeyframe,
        data = ByteArray(size) { (it % 251).toByte() },
    )

    private fun parse(bytes: ByteArray): VideoPacket =
        assertIs<PacketParseResult.Success>(FramePacketParser.parse(bytes)).packet

    @Test
    fun `数据帧被切成多包且每包都能解析回原字段`() {
        val frame = frameOf(size = 5_000, seq = 3, isKeyframe = true)
        val packets = FramePacketizer.packetize(frame, maxPayloadSize = 1_200)

        assertEquals(5, packets.size, "5000 字节按 1200 切应得 5 包")
        val parsed = packets.map(::parse)

        parsed.forEachIndexed { index, packet ->
            assertEquals(frame.sessionId, packet.sessionId)
            assertEquals(frame.frameSeq, packet.frameSeq)
            assertEquals(frame.timestampTicks, packet.timestampTicks)
            assertTrue(packet.isKeyframe, "关键帧标志应逐包携带")
            assertEquals(index, packet.packetIndex)
            assertEquals(5, packet.packetCount)
        }
        assertTrue(parsed.first().isFirst)
        assertTrue(parsed.last().isLast)

        val rejoined = ByteArray(parsed.sumOf { it.payload.size })
        var offset = 0
        parsed.forEach { it.payload.copyInto(rejoined, offset); offset += it.payload.size }
        assertContentEquals(frame.data, rejoined, "分包负载按序拼接应还原原始数据")
    }

    @Test
    fun `空帧也会发出一个只带包头的包`() {
        val packets = FramePacketizer.packetize(frameOf(size = 0, seq = 1))
        assertEquals(1, packets.size)
        val packet = parse(packets.single())
        assertEquals(0, packet.payload.size)
        assertTrue(packet.isFirst && packet.isLast)
    }

    @Test
    fun `恰好整除时不产生空尾巴包`() {
        val packets = FramePacketizer.packetize(frameOf(size = 2_400, seq = 2), maxPayloadSize = 1_200)
        assertEquals(2, packets.size)
        assertEquals(1_200, parse(packets[0]).payload.size)
        assertEquals(1_200, parse(packets[1]).payload.size)
    }

    @Test
    fun `非关键帧不带关键帧标志`() {
        val packet = parse(FramePacketizer.packetize(frameOf(size = 10, seq = 1)).single())
        assertTrue(!packet.isKeyframe)
    }

    @Test
    fun `非法分包长度直接抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            FramePacketizer.packetize(frameOf(size = 10, seq = 1), maxPayloadSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            FramePacketizer.packetize(frameOf(size = 10, seq = 1), maxPayloadSize = Protocol.MAX_PAYLOAD + 1)
        }
    }

    @Test
    fun `截断的包被拒绝`() {
        val bytes = FramePacketizer.packetize(frameOf(size = 100, seq = 1)).single()
        assertIs<PacketParseResult.Malformed>(FramePacketParser.parse(bytes.copyOf(16)))
    }

    @Test
    fun `魔数错误的包被拒绝`() {
        val bytes = FramePacketizer.packetize(frameOf(size = 100, seq = 1)).single()
        bytes[0] = 0x00
        bytes[1] = 0x00
        assertIs<PacketParseResult.Malformed>(FramePacketParser.parse(bytes))
    }

    @Test
    fun `协议版本不符的包被拒绝`() {
        val bytes = FramePacketizer.packetize(frameOf(size = 100, seq = 1)).single()
        bytes[2] = 99
        assertIs<PacketParseResult.Malformed>(FramePacketParser.parse(bytes))
    }

    @Test
    fun `未知消息类型的包被拒绝`() {
        val bytes = FramePacketizer.packetize(frameOf(size = 100, seq = 1)).single()
        bytes[3] = 0x7F
        assertIs<PacketParseResult.Malformed>(FramePacketParser.parse(bytes))
    }

    @Test
    fun `负载长度声明与实际不符时被拒绝`() {
        val bytes = FramePacketizer.packetize(frameOf(size = 100, seq = 1)).single()
        // payloadLen 位于 30..31，改大到与实际不符
        bytes[30] = 0x01
        bytes[31] = 0x00
        assertIs<PacketParseResult.Malformed>(FramePacketParser.parse(bytes))
    }

    @Test
    fun `包序号超出总包数时被拒绝`() {
        val bytes = FramePacketizer.packetize(frameOf(size = 100, seq = 1)).single()
        bytes[18] = 0x00
        bytes[19] = 0x05 // packetIndex = 5
        bytes[20] = 0x00
        bytes[21] = 0x02 // packetCount = 2
        assertIs<PacketParseResult.Malformed>(FramePacketParser.parse(bytes))
    }
}

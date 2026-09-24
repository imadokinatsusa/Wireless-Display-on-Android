package com.whalecast.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameReassemblerTest {

    private fun packetsOf(size: Int, seq: Long, maxPayload: Int = 1_200, isKeyframe: Boolean = false) =
        FramePacketizer.packetize(
            EncodedFrame(
                sessionId = 7,
                frameSeq = seq,
                timestampTicks = Protocol.TICKS_PER_SECOND * seq,
                isKeyframe = isKeyframe,
                data = ByteArray(size) { (it % 251).toByte() },
            ),
            maxPayloadSize = maxPayload,
        ).map { assertIs<PacketParseResult.Success>(FramePacketParser.parse(it)).packet }

    @Test
    fun `顺序到达时重组出完整帧`() {
        val reassembler = FrameReassembler()
        val events = packetsOf(size = 3_000, seq = 1).flatMap { reassembler.onPacket(it) }

        val complete = events.filterIsInstance<ReassemblyEvent.FrameComplete>()
        assertEquals(1, complete.size)
        assertEquals(1L, complete.single().frame.frameSeq)
        assertEquals(3_000, complete.single().frame.size)
        assertEquals(0, reassembler.pendingFrameCount, "完成后不应残留在途帧")
    }

    @Test
    fun `乱序到达仍能重组`() {
        val reassembler = FrameReassembler()
        val frames = mutableListOf<EncodedFrame>()
        packetsOf(size = 3_000, seq = 4).reversed().forEach { packet ->
            reassembler.onPacket(packet)
                .filterIsInstance<ReassemblyEvent.FrameComplete>()
                .forEach { frames += it.frame }
        }
        assertEquals(1, frames.size)
        assertEquals(4L, frames.single().frameSeq)
    }

    @Test
    fun `重复包被忽略且不影响重组`() {
        val reassembler = FrameReassembler()
        val packets = packetsOf(size = 3_000, seq = 2)

        reassembler.onPacket(packets[0])
        val duplicateEvents = reassembler.onPacket(packets[0])
        assertEquals(listOf(ReassemblyEvent.Ignored), duplicateEvents, "重复包应被忽略")

        val remaining = packets.drop(1).flatMap { reassembler.onPacket(it) }
        assertEquals(1, remaining.filterIsInstance<ReassemblyEvent.FrameComplete>().size)
    }

    @Test
    fun `缺包时该帧永不交付并被更新帧判定为丢弃`() {
        val reassembler = FrameReassembler()
        val incomplete = packetsOf(size = 3_000, seq = 1)
        // 故意漏掉中间一个包
        val delivered = incomplete.filterIndexed { index, _ -> index != 1 }
            .flatMap { reassembler.onPacket(it) }
        assertTrue(
            delivered.none { it is ReassemblyEvent.FrameComplete },
            "缺包的帧不得作为完整帧交付",
        )

        // 更新的帧开始到达 → 旧的残缺帧应被上报为 MISSING_PACKETS
        val next = packetsOf(size = 100, seq = 2).flatMap { reassembler.onPacket(it) }
        val dropped = next.filterIsInstance<ReassemblyEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(1L, dropped.single().frameSeq)
        assertEquals(DropReason.MISSING_PACKETS, dropped.single().reason)
        assertTrue(next.any { it is ReassemblyEvent.FrameComplete }, "新帧本身应完成")
    }

    @Test
    fun `在途帧超出上限时淘汰最旧的一帧`() {
        val reassembler = FrameReassembler(maxPendingFrames = 2)
        // 三个都是"只到第一个包"的残缺帧
        listOf(1L, 2L, 3L).forEach { seq ->
            reassembler.onPacket(packetsOf(size = 3_000, seq = seq).first())
        }
        assertEquals(2, reassembler.pendingFrameCount, "在途帧数应受上限约束")

        val drops = mutableListOf<ReassemblyEvent.FrameDropped>()
        reassembler.onPacket(packetsOf(size = 3_000, seq = 4).first())
            .filterIsInstance<ReassemblyEvent.FrameDropped>()
            .forEach { drops += it }
        assertTrue(drops.any { it.reason == DropReason.TOO_MANY_PENDING }, "应有帧因内存守护被淘汰")
    }

    @Test
    fun `同一帧序号但分包数不一致时丢弃整帧`() {
        val reassembler = FrameReassembler()
        val packets = packetsOf(size = 3_000, seq = 1)
        reassembler.onPacket(packets[0])
        // 伪造一个分包数不同的同序号包
        val forged = VideoPacket(
            sessionId = 7,
            frameSeq = 1,
            timestampTicks = 0,
            isKeyframe = false,
            packetIndex = 1,
            packetCount = 99,
            payload = ByteArray(10),
        )
        val events = reassembler.onPacket(forged)
        val dropped = events.filterIsInstance<ReassemblyEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(DropReason.STALE, dropped.single().reason)
        assertEquals(0, reassembler.pendingFrameCount)
    }

    @Test
    fun `reset 清空在途状态`() {
        val reassembler = FrameReassembler()
        reassembler.onPacket(packetsOf(size = 3_000, seq = 1).first())
        assertEquals(1, reassembler.pendingFrameCount)
        reassembler.reset()
        assertEquals(0, reassembler.pendingFrameCount)
    }

    @Test
    fun `重组出的数据与原始数据逐字节一致`() {
        val payload = ByteArray(5_000) { (it * 7 % 256).toByte() }
        val reassembler = FrameReassembler()
        var reassembled: EncodedFrame? = null
        FramePacketizer.packetize(
            EncodedFrame(1, 42L, 90_000L, true, payload),
            maxPayloadSize = 512,
        ).forEach { raw ->
            val packet = assertIs<PacketParseResult.Success>(FramePacketParser.parse(raw)).packet
            reassembler.onPacket(packet)
                .filterIsInstance<ReassemblyEvent.FrameComplete>()
                .forEach { reassembled = it.frame }
        }
        val frame = reassembled ?: error("应重组出完整帧")
        assertContentEquals(payload, frame.data)
        assertTrue(frame.isKeyframe)
    }
}

package com.whalecast.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把一个 [EncodedFrame] 切成若干带上包头的字节包。
 *
 * 纯函数、不可变输入输出，因此是切片 01 里最好测的一段逻辑。
 */
object FramePacketizer {

    /**
     * @param maxPayloadSize 单包最大负载字节数；越小越抗丢包，但包头开销越大。
     */
    fun packetize(
        frame: EncodedFrame,
        maxPayloadSize: Int = Protocol.DEFAULT_MAX_PAYLOAD,
    ): List<ByteArray> {
        require(maxPayloadSize in 1..Protocol.MAX_PAYLOAD) {
            "maxPayloadSize 必须在 1..${Protocol.MAX_PAYLOAD} 之间，实际 $maxPayloadSize"
        }
        val data = frame.data
        if (data.isEmpty()) {
            // 空帧也要发一个只带包头的包，保证接收端帧序号连续、不产生空洞。
            return listOf(encode(frame, packetIndex = 0, packetCount = 1, payload = ByteArray(0)))
        }
        val packetCount = (data.size + maxPayloadSize - 1) / maxPayloadSize
        require(packetCount <= Protocol.MAX_PACKET_COUNT) {
            "分包数 $packetCount 超出上限 ${Protocol.MAX_PACKET_COUNT}"
        }
        return List(packetCount) { index ->
            val from = index * maxPayloadSize
            val to = minOf(from + maxPayloadSize, data.size)
            encode(frame, index, packetCount, data.copyOfRange(from, to))
        }
    }

    private fun encode(
        frame: EncodedFrame,
        packetIndex: Int,
        packetCount: Int,
        payload: ByteArray,
    ): ByteArray {
        var flags = 0
        if (frame.isKeyframe) flags = flags or FrameFlags.KEYFRAME
        if (packetIndex == 0) flags = flags or FrameFlags.FIRST_PACKET
        if (packetIndex == packetCount - 1) flags = flags or FrameFlags.LAST_PACKET

        return ByteBuffer.allocate(Protocol.HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(Protocol.MAGIC.toShort())
                put(Protocol.VERSION.toByte())
                put(MessageType.VIDEO_FRAME.code.toByte())
                put(flags.toByte())
                put(0) // reserved
                putInt(frame.sessionId)
                putLong(frame.frameSeq)
                putShort(packetIndex.toShort())
                putShort(packetCount.toShort())
                putLong(frame.timestampTicks)
                putShort(payload.size.toShort())
                put(payload)
            }
            .array()
    }
}

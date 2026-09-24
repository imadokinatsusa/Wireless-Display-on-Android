package com.whalecast.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** [FramePacketParser.parse] 的结果。畸形包必须被显式拒绝，而不是静默吞掉。 */
sealed interface PacketParseResult {
    data class Success(val packet: VideoPacket) : PacketParseResult

    data class Malformed(val reason: String) : PacketParseResult
}

/**
 * 解析单包字节为 [VideoPacket]。
 *
 * 每一步校验都对应一条单测：魔数、版本、类型、字段自洽性、负载长度。
 */
object FramePacketParser {

    fun parse(bytes: ByteArray): PacketParseResult {
        if (bytes.size < Protocol.HEADER_SIZE) {
            return PacketParseResult.Malformed("包长 ${bytes.size} 小于包头 ${Protocol.HEADER_SIZE}")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.short.toInt() and 0xFFFF
        if (magic != Protocol.MAGIC) {
            return PacketParseResult.Malformed("魔数不匹配: 0x${magic.toString(16)}")
        }

        val version = buffer.get().toInt() and 0xFF
        if (version != Protocol.VERSION) {
            return PacketParseResult.Malformed("协议版本不支持: $version，本端为 ${Protocol.VERSION}")
        }

        val typeCode = buffer.get().toInt() and 0xFF
        val type = MessageType.fromCode(typeCode)
            ?: return PacketParseResult.Malformed("未知消息类型: $typeCode")
        if (type != MessageType.VIDEO_FRAME) {
            return PacketParseResult.Malformed("切片 01 只处理 VIDEO_FRAME，收到 $type")
        }

        val flags = buffer.get().toInt() and 0xFF
        buffer.get() // reserved
        val sessionId = buffer.int
        val frameSeq = buffer.long
        val packetIndex = buffer.short.toInt() and 0xFFFF
        val packetCount = buffer.short.toInt() and 0xFFFF
        val timestampTicks = buffer.long
        val payloadLen = buffer.short.toInt() and 0xFFFF

        if (sessionId < 0) return PacketParseResult.Malformed("sessionId 为负: $sessionId")
        if (frameSeq < 0) return PacketParseResult.Malformed("frameSeq 为负: $frameSeq")
        if (timestampTicks < 0) return PacketParseResult.Malformed("timestampTicks 为负: $timestampTicks")
        if (packetCount == 0) return PacketParseResult.Malformed("packetCount 为 0")
        if (packetIndex >= packetCount) {
            return PacketParseResult.Malformed("packetIndex($packetIndex) 超出 packetCount($packetCount)")
        }
        if (payloadLen > Protocol.MAX_PAYLOAD) {
            return PacketParseResult.Malformed("负载超限: $payloadLen > ${Protocol.MAX_PAYLOAD}")
        }
        val actualPayloadLen = bytes.size - Protocol.HEADER_SIZE
        if (payloadLen != actualPayloadLen) {
            return PacketParseResult.Malformed("负载长度不符: 包声明 $payloadLen，实际 $actualPayloadLen")
        }

        val payload = ByteArray(payloadLen)
        buffer.get(payload)

        return PacketParseResult.Success(
            VideoPacket(
                sessionId = sessionId,
                frameSeq = frameSeq,
                timestampTicks = timestampTicks,
                isKeyframe = FrameFlags.has(flags, FrameFlags.KEYFRAME),
                packetIndex = packetIndex,
                packetCount = packetCount,
                payload = payload,
            ),
        )
    }
}

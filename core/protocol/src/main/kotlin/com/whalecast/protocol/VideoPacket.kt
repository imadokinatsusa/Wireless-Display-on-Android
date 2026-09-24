package com.whalecast.protocol

/**
 * 一个编码帧被切分后的传输单元（见 [Protocol.HEADER_SIZE] 的包头布局）。
 */
class VideoPacket(
    val sessionId: Int,
    val frameSeq: Long,
    val timestampTicks: Long,
    val isKeyframe: Boolean,
    val packetIndex: Int,
    val packetCount: Int,
    val payload: ByteArray,
) {
    val isFirst: Boolean get() = packetIndex == 0
    val isLast: Boolean get() = packetIndex == packetCount - 1

    override fun toString(): String =
        "VideoPacket(session=$sessionId, seq=$frameSeq, packet=${packetIndex + 1}/$packetCount, " +
            "key=$isKeyframe, bytes=${payload.size})"
}

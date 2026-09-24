package com.whalecast.protocol

/**
 * 一帧完整的编码数据（切包前的形态）。
 *
 * 刻意不用 `data class`：`ByteArray` 的默认相等性是引用比较，
 * 而这里的语义显然是"内容相同即相等"，所以显式实现 equals/hashCode。
 */
class EncodedFrame(
    val sessionId: Int,
    val frameSeq: Long,
    val timestampTicks: Long,
    val isKeyframe: Boolean,
    val data: ByteArray,
) {
    init {
        require(sessionId >= 0) { "sessionId 不能为负: $sessionId" }
        require(frameSeq >= 0) { "frameSeq 不能为负: $frameSeq" }
        require(timestampTicks >= 0) { "timestampTicks 不能为负: $timestampTicks" }
    }

    val size: Int get() = data.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedFrame) return false
        return sessionId == other.sessionId &&
            frameSeq == other.frameSeq &&
            timestampTicks == other.timestampTicks &&
            isKeyframe == other.isKeyframe &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = sessionId
        result = 31 * result + frameSeq.hashCode()
        result = 31 * result + timestampTicks.hashCode()
        result = 31 * result + isKeyframe.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String =
        "EncodedFrame(session=$sessionId, seq=$frameSeq, ts=$timestampTicks, key=$isKeyframe, bytes=${data.size})"
}

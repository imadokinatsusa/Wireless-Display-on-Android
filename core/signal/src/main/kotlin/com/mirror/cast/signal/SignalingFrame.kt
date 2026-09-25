package com.mirror.cast.signal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 信令帧：4 字节大端长度 + 负载。
 *
 * 信令走 TCP，而 TCP 只是字节流 —— 分帧是**我们的**责任。
 * 这一层刻意做到最简单：长度前缀是几十年的成熟做法，不需要发明任何东西。
 */
object SignalingFrame {

    const val HEADER_BYTES: Int = 4

    /** 单帧上限：SDP 通常几 KB，给足余量同时挡住畸形长度。 */
    const val MAX_PAYLOAD_BYTES: Int = 512 * 1024

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size in 1..MAX_PAYLOAD_BYTES) {
            "信令负载长度非法：${payload.size}"
        }
        return ByteBuffer.allocate(HEADER_BYTES + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    /**
     * 从 4 字节头部读出负载长度。
     *
     * 长度 <= 0 或 > [MAX_PAYLOAD_BYTES] 一律判非法（返回 null）：
     * 否则一个畸形头就能让对端申请一大块内存。
     */
    fun payloadLength(header: ByteArray): Int? {
        if (header.size < HEADER_BYTES) return null
        val length = ByteBuffer.wrap(header, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN).int
        return if (length in 1..MAX_PAYLOAD_BYTES) length else null
    }
}

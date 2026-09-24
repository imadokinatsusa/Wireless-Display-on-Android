package com.whalecast.protocol

/**
 * 协议常量：所有跨端字节格式的单一事实来源。
 *
 * 本模块刻意保持**零 Android 依赖**（CONTEXT.md 的硬约束），
 * 因此切包、重组、握手、码率策略等逻辑都能在 JVM 上直接单测。
 */
object Protocol {

    /** 包头魔数 'W'（0x57）'C'（0x43）。 */
    const val MAGIC: Int = 0x5743

    /** 当前协议版本；握手阶段取双方交集（切片 03）。 */
    const val VERSION: Int = 1

    /**
     * 包头固定长度（字节）。布局（大端）：
     * ```
     *  0..1   magic          2B
     *  2      version        1B
     *  3      type           1B
     *  4      flags          1B
     *  5      reserved       1B
     *  6..9   sessionId      4B
     * 10..17  frameSeq       8B
     * 18..19  packetIndex    2B
     * 20..21  packetCount    2B
     * 22..29  timestampTicks 8B
     * 30..31  payloadLen     2B
     * ```
     * 合计 32 字节。
     */
    const val HEADER_SIZE: Int = 32

    /** 默认单包最大负载，取 UDP 友好值（切片 07 走 UDP 时无需再改）。 */
    const val DEFAULT_MAX_PAYLOAD: Int = 1200

    /** 单包负载绝对上限，用于拒绝畸形包，防止撑爆内存。 */
    const val MAX_PAYLOAD: Int = 65_535

    /** 单帧最大分包数（2 字节字段的上限）。 */
    const val MAX_PACKET_COUNT: Int = 65_535

    /** 媒体时钟频率：90kHz，RTP 惯例。 */
    const val TICKS_PER_SECOND: Long = 90_000L

    /**
     * 把采集时刻的纳秒时间戳换算为 90kHz 媒体时钟。
     *
     * 刻意先除后乘（而不是 `nanos * TICKS_PER_SECOND / 1e9`），
     * 因为 `System.nanoTime()` 量级下直接相乘会溢出 Long。
     */
    fun ticksFromNanos(nanos: Long): Long {
        val millis = nanos / 1_000_000L
        val remainder = nanos % 1_000_000L
        // 毫秒部分：1ms = 90 ticks；亚毫秒部分：1ns = 9e-5 ticks
        return millis * TICKS_PER_SECOND / 1_000L + remainder * TICKS_PER_SECOND / 1_000_000_000L
    }

    /** [ticksFromNanos] 的逆运算，同样避免溢出。 */
    fun nanosFromTicks(ticks: Long): Long {
        val seconds = ticks / TICKS_PER_SECOND
        val remainder = ticks % TICKS_PER_SECOND
        return seconds * 1_000_000_000L + remainder * 1_000_000_000L / TICKS_PER_SECOND
    }
}

/** 消息类型。切片 01 只用到 [VIDEO_FRAME]，其余为后续切片占位。 */
enum class MessageType(val code: Int) {
    VIDEO_FRAME(0x01),
    AUDIO_FRAME(0x02),
    CONTROL(0x10),
    ;

    companion object {
        private val byCode: Map<Int, MessageType> = entries.associateBy { it.code }

        fun fromCode(code: Int): MessageType? = byCode[code and 0xFF]
    }
}

/** 包头 flags 位定义。 */
object FrameFlags {
    const val KEYFRAME: Int = 0x01
    const val FIRST_PACKET: Int = 0x02
    const val LAST_PACKET: Int = 0x04

    fun has(flags: Int, mask: Int): Boolean = (flags and mask) != 0
}

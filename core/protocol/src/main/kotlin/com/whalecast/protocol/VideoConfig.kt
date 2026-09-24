package com.whalecast.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 解码器初始化信息：分辨率 + H.264 的 SPS/PPS（即 MediaCodec 的 csd-0 / csd-1）。
 *
 * 发送端在**首个关键帧之前**发一次（[MessageType.VIDEO_CONFIG] 包），
 * 接收端据此 `configure` 解码器；之后只喂纯帧数据，避免每帧重复解析参数集。
 */
class VideoConfig(
    val width: Int,
    val height: Int,
    /** SPS，Annex-B 形式（含起始码）。 */
    val csd0: ByteArray,
    /** PPS，Annex-B 形式（含起始码）。 */
    val csd1: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "分辨率必须为正: ${width}x$height" }
    }

    fun encode(): ByteArray = ByteBuffer
        .allocate(HEADER_BYTES + csd0.size + csd1.size)
        .order(ByteOrder.BIG_ENDIAN)
        .apply {
            putInt(width)
            putInt(height)
            putShort(csd0.size.toShort())
            put(csd0)
            putShort(csd1.size.toShort())
            put(csd1)
        }
        .array()

    override fun toString(): String =
        "VideoConfig(${width}x$height, sps=${csd0.size}B, pps=${csd1.size}B)"

    companion object {
        const val HEADER_BYTES: Int = 12

        /** 解析失败返回 null —— 畸形配置不允许掀翻接收端。 */
        fun decode(bytes: ByteArray): VideoConfig? {
            if (bytes.size < HEADER_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val width = buffer.int
            val height = buffer.int
            val spsLen = buffer.short.toInt() and 0xFFFF
            if (width <= 0 || height <= 0) return null
            if (buffer.remaining() < spsLen) return null
            val sps = ByteArray(spsLen).also { buffer.get(it) }
            if (buffer.remaining() < 2) return null
            val ppsLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < ppsLen) return null
            val pps = ByteArray(ppsLen).also { buffer.get(it) }
            return VideoConfig(width, height, sps, pps)
        }
    }
}

/**
 * 只读包头、不做完整校验的类型探测 —— 让接收端在读到一个包后决定该走
 * 视频帧解析还是配置解析。返回 null 表示这段数据不像本协议的包。
 */
object PacketPeek {

    fun type(bytes: ByteArray): MessageType? {
        if (bytes.size < Protocol.HEADER_SIZE) return null
        val magic = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (magic != Protocol.MAGIC) return null
        if ((bytes[2].toInt() and 0xFF) != Protocol.VERSION) return null
        return MessageType.fromCode(bytes[3].toInt() and 0xFF)
    }

    /** 读取包头里声明的负载长度，供 TCP 分帧使用。 */
    fun payloadLength(bytes: ByteArray): Int? {
        if (bytes.size < Protocol.HEADER_SIZE) return null
        return ((bytes[30].toInt() and 0xFF) shl 8) or (bytes[31].toInt() and 0xFF)
    }
}

/** 把 [VideoConfig] 打成单个协议包（不分片：参数集很小）。 */
object ConfigPacketizer {

    fun packetize(
        config: VideoConfig,
        sessionId: Int,
        frameSeq: Long = 0L,
        timestampTicks: Long = 0L,
    ): ByteArray {
        val payload = config.encode()
        return ByteBuffer
            .allocate(Protocol.HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(Protocol.MAGIC.toShort())
                put(Protocol.VERSION.toByte())
                put(MessageType.VIDEO_CONFIG.code.toByte())
                put(FrameFlags.KEYFRAME.toByte())
                put(0) // reserved
                putInt(sessionId)
                putLong(frameSeq)
                putShort(0) // packetIndex
                putShort(1) // packetCount
                putLong(timestampTicks)
                putShort(payload.size.toShort())
                put(payload)
            }
            .array()
    }

    /** 与 [packetize] 配对：从完整包里取回配置。 */
    fun parse(bytes: ByteArray): VideoConfig? {
        if (PacketPeek.type(bytes) != MessageType.VIDEO_CONFIG) return null
        val length = PacketPeek.payloadLength(bytes) ?: return null
        if (bytes.size < Protocol.HEADER_SIZE + length) return null
        return VideoConfig.decode(bytes.copyOfRange(Protocol.HEADER_SIZE, Protocol.HEADER_SIZE + length))
    }
}

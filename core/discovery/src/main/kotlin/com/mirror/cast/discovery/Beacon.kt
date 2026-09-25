package com.mirror.cast.discovery

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 接收端周期性广播的发现报文。
 *
 * 发送端收到后就能把"连接码"解析成 `IP:端口`，于是主人不必手敲 IP。
 *
 * 报文刻意极小（< 100 字节）且**不加密**：它只携带建立连接所需的最小信息。
 * 真正的隐私保护发生在会话层 —— [token] 既是连接凭证，也是后续加密握手的材料，
 * 因此旁观者即便听到广播，也拿不到屏幕内容。
 */
class Beacon(
    /** 短连接码，见 [ConnectCode]。 */
    val code: String,
    /** 接收端的 TCP 监听端口。 */
    val tcpPort: Int,
    /** 设备名，用于界面上区分"这是哪台设备"。 */
    val deviceName: String,
    /** 一次性令牌（十六进制字符串）。 */
    val token: String,
) {
    init {
        require(ConnectCode.isValid(code)) { "连接码非法：$code" }
        require(tcpPort in 1..65_535) { "端口非法：$tcpPort" }
    }

    fun encode(): ByteArray {
        val codeBytes = code.toByteArray(Charsets.US_ASCII)
        val tokenBytes = token.toByteArray(Charsets.US_ASCII)
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)

        require(codeBytes.size <= 255 && tokenBytes.size <= 255 && nameBytes.size <= 255) {
            "字段超长：code=${codeBytes.size} token=${tokenBytes.size} name=${nameBytes.size}"
        }

        return ByteBuffer
            .allocate(FIXED_BYTES + codeBytes.size + tokenBytes.size + nameBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(MAGIC.toShort())
                put(VERSION.toByte())
                put(codeBytes.size.toByte())
                put(codeBytes)
                putShort(tcpPort.toShort())
                put(tokenBytes.size.toByte())
                put(tokenBytes)
                put(nameBytes.size.toByte())
                put(nameBytes)
            }
            .array()
    }

    override fun toString(): String = "Beacon($code @ $deviceName:$tcpPort)"

    companion object {
        /** 'M' 'C'（Mirror Cast Beacon）。 */
        const val MAGIC: Int = 0x4D43

        const val VERSION: Int = 1

        /** 广播与监听使用的 UDP 端口。 */
        const val UDP_PORT: Int = 47_922

        /**
         * 固定字段总字节数：magic(2) + version(1) + codeLen(1) + tcpPort(2)
         * + tokenLen(1) + nameLen(1) = 8。
         *
         * 早先这里写成 3（只算了 magic+version），导致 allocate 少 5 字节、
         * encode 直接 BufferOverflow —— 广播根本发不出去。
         */
        private const val FIXED_BYTES: Int = 8

        /** 解析失败返回 null —— 局域网上什么垃圾都可能飘过来。 */
        fun decode(bytes: ByteArray): Beacon? {
            if (bytes.size < FIXED_BYTES + ConnectCode.LENGTH) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val magic = buffer.short.toInt() and 0xFFFF
            if (magic != MAGIC) return null
            if ((buffer.get().toInt() and 0xFF) != VERSION) return null

            val codeLength = buffer.get().toInt() and 0xFF
            if (codeLength != ConnectCode.LENGTH || buffer.remaining() < codeLength + 2) return null
            val code = ByteArray(codeLength).also { buffer.get(it) }.toString(Charsets.US_ASCII)
            if (!ConnectCode.isValid(code)) return null

            val tcpPort = buffer.short.toInt() and 0xFFFF
            if (tcpPort == 0) return null

            if (buffer.remaining() < 1) return null
            val tokenLength = buffer.get().toInt() and 0xFF
            if (buffer.remaining() < tokenLength + 1) return null
            val token = ByteArray(tokenLength).also { buffer.get(it) }.toString(Charsets.US_ASCII)

            val nameLength = buffer.get().toInt() and 0xFF
            if (buffer.remaining() < nameLength) return null
            val name = ByteArray(nameLength).also { buffer.get(it) }.toString(Charsets.UTF_8)

            return Beacon(code = code, tcpPort = tcpPort, deviceName = name, token = token)
        }
    }
}

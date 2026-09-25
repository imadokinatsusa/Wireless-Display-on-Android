package com.mirror.cast.discovery

/** 一次探测的应答：这个地址上到底是谁在等着接收。 */
data class ProbeReply(
    val deviceName: String,
    /** 真正用于信令的端口 —— 它和 [ProbeProtocol.PORT] 是两个端口，所以叫"双端口"。 */
    val signalingPort: Int,
    val code: String,
)

/**
 * 发现阶段的一问一答（走 TCP）。
 *
 * **它和 UDP 广播是并行的两条路，不是替代关系**：
 * 广播是"喊"，对方可能听不见（路由器 AP 隔离、热点过滤组播）；
 * 扫端口是"敲门"，能敲开就是能敲开。两条都留着，任何一条坏了另一条还在。
 *
 * 两个端口各司其职，也就是"双端口"的含义：
 * - [PORT]：**发现端口**，固定不变，只回答"这儿有接收端吗、叫什么、信令端口是多少"；
 * - 应答里带回来的 signalingPort：**信令端口**，真正跑投屏协商的那个。
 *
 * 协议刻意极简（一行前缀 + 一行负载）。纯逻辑、无 Android 依赖，可在 JVM 上单测。
 */
object ProbeProtocol {

    /** 发现端口：固定不变，发送端就靠扫它找人。 */
    const val PORT: Int = 47_800

    /** 发送端 → 接收端：一句话，问"这儿有接收端吗"。 */
    const val REQUEST: String = "MIRROR/PROBE/1"

    /** 接收端 → 发送端：前缀 + 负载（设备名 / 信令端口 / 连接码）。 */
    const val REPLY_PREFIX: String = "MIRROR/HERE/1\t"

    fun encodeReply(reply: ProbeReply): ByteArray = buildString {
        append(REPLY_PREFIX)
        // 设备名里出现分隔符会把整个格式拆坏，先在源头抹掉
        append(reply.deviceName.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '))
        append('\t')
        append(reply.signalingPort)
        append('\t')
        append(ConnectCode.normalize(reply.code))
    }.toByteArray(Charsets.UTF_8)

    /** 解析失败返回 null —— 扫端口会撞上一堆别的东西，不能因此崩溃。 */
    fun decodeReply(bytes: ByteArray): ProbeReply? {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (!text.startsWith(REPLY_PREFIX)) return null
        val parts = text.removePrefix(REPLY_PREFIX).split('\t')
        if (parts.size != 3) return null
        val port = parts[1].toIntOrNull() ?: return null
        if (port !in 1..65_535) return null
        val code = ConnectCode.normalize(parts[2])
        if (!ConnectCode.isValid(code)) return null
        return ProbeReply(
            deviceName = parts[0].ifBlank { "Android" },
            signalingPort = port,
            code = code,
        )
    }

    /** 这段字节是不是一个探测请求。 */
    fun isRequest(bytes: ByteArray): Boolean =
        bytes.toString(Charsets.UTF_8).trim() == REQUEST

    /**
     * 从一个本机地址推出要扫的网段前缀。
     *
     * 只扫 /24：投屏的两台设备必然在同一段里，扫更宽既慢又没意义。
     * 返回 null 表示这个地址不适合拿来扫（比如回环）。
     */
    fun subnetPrefix(localAddress: String): String? {
        val parts = localAddress.split('.')
        if (parts.size != 4) return null
        if (parts.any { it.toIntOrNull() !in 0..255 }) return null
        if (parts[0] == "127") return null
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }
}
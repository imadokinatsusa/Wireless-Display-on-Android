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
 * **为什么不用 UDP 广播**：它太容易被环境吃掉 —— 路由器开 AP 隔离、热点下过滤组播、
 * 建了 Wi-Fi Direct 组之后网络接口一变、socket 还绑在旧接口上。
 * 这些场景里"广播收不到"是常态，但"**主动连过去**"往往是通的。
 *
 * 所以改成扫端口：接收端在一个**固定端口**上待着，发送端并发扫自己网段的那一个端口，
 * 谁应答谁就是可投的设备。
 *
 * 两个端口各司其职，这也是"双端口验证"的含义：
 * - [PORT]：**发现端口**，固定不变，只回答"这儿有接收端吗、叫什么、信令端口是多少"；
 * - 应答里带回来的 `signalingPort`：**信令端口**，真正跑投屏协商的那个。
 *
 * 协议刻意极简（一行前缀 + 一行负载），因为它的唯一任务是回答
 * "这个地址上是不是一台待接收的 Mirror"。纯逻辑、无 Android 依赖，可在 JVM 上单测。
 */
object ProbeProtocol {

    /** 发现端口：固定不变，发送端就靠扫它找人。 */
    const val PORT: Int = 47_800

    /** 发送端 → 接收端：一句话，问"这儿有接收端吗"。 */
    const val REQUEST: String = "MIRROR/PROBE/1"

    /** 接收端 → 发送端：前缀 + 负载（设备名 \t 信令端口 \t 连接码）。 */
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
     * 只扫 `/24`：投屏的两台设备必然在同一段里，扫更宽既慢又没意义。
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

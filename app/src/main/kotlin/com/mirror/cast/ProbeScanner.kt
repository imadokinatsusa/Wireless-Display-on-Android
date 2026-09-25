package com.mirror.cast

import com.mirror.cast.discovery.ProbeProtocol
import com.mirror.cast.discovery.ProbeReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 发送端的"敲门"：并发扫自己所在网段的那一个固定端口。
 *
 * 这是**广播之外的第二条路**，不是替代品。广播是"喊"，对方可能听不见
 * （路由器 AP 隔离、热点过滤组播）；扫端口是"敲门"，能敲开就是能敲开。
 *
 * 但它很贵：一轮就是几百个 TCP 连接。如果一直扫，链路会被打满、投屏画面直接卡顿（踩过）。
 * 所以调用方必须遵守两条纪律：
 * 1. **已经有设备了就别扫**（投屏期间根本不需要继续找）；
 * 2. 没设备时也别太密。
 *
 * 并发刻意压到 12：快是快在"该扫的时候扫"，不是快在"同时开一堆连接"。
 */
object ProbeScanner {

    /**
     * 扫一轮，返回所有应答了的主机。
     *
     * 刻意**返回列表**而不是用回调：12 路并发同时往里塞结果，
     * 用回调就要求调用方自己保证线程安全，容易埋一个"偶尔少一台"的暗雷。
     */
    suspend fun scan(
        prefixes: List<String>,
        port: Int = ProbeProtocol.PORT,
    ): List<Pair<String, ProbeReply>> {
        val targets = prefixes.distinct().flatMap { prefix -> (1..LAST_OCTET).map { "$prefix$it" } }
        if (targets.isEmpty()) return emptyList()
        val gate = Semaphore(CONCURRENCY)
        return coroutineScope {
            targets
                .map { host ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            probe(host, port)?.let { reply -> host to reply }
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    /** 敲一下门。任何异常都只是"这家没人"，不是错误。 */
    private fun probe(host: String, port: Int): ProbeReply? = runCatching {
        Socket().use { socket ->
            socket.soTimeout = READ_TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            socket.getOutputStream().write(ProbeProtocol.REQUEST.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val buffer = ByteArray(REPLY_BYTES)
            val read = socket.getInputStream().read(buffer)
            if (read <= 0) null else ProbeProtocol.decodeReply(buffer.copyOf(read))
        }
    }.getOrNull()

    /** 本机地址 → 要扫的网段前缀列表。 */
    fun prefixesFor(localAddresses: List<String>): List<String> =
        localAddresses.mapNotNull { ProbeProtocol.subnetPrefix(it) }.distinct()

    private const val LAST_OCTET = 254
    private const val CONCURRENCY = 12
    private const val CONNECT_TIMEOUT_MILLIS = 350
    private const val READ_TIMEOUT_MILLIS = 600
    private const val REPLY_BYTES = 256
}
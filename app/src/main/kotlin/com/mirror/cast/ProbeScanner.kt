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
 * 发送端的"找人"：并发扫自己所在网段的那一个固定端口。
 *
 * 为什么是扫端口而不是发广播：**广播是"喊"，对方可能听不见；扫端口是"敲门"，能敲开就是能敲开。**
 * 路由器 AP 隔离会吃掉广播，却通常放行设备之间的单播 —— 这正是投屏真正需要的。
 *
 * 只扫 `/24`、只扫一个端口、并发上限固定：实测一轮 3～4 秒，
 * 而"搜不到"这种状态本身就不该让用户等太久。
 */
object ProbeScanner {

    suspend fun scan(
        prefixes: List<String>,
        port: Int = ProbeProtocol.PORT,
        onFound: (host: String, reply: ProbeReply) -> Unit,
    ) {
        val targets = prefixes.distinct().flatMap { prefix -> (1..LAST_OCTET).map { "$prefix$it" } }
        if (targets.isEmpty()) return
        val gate = Semaphore(CONCURRENCY)
        coroutineScope {
            targets
                .map { host ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            probe(host, port)?.let { reply -> onFound(host, reply) }
                        }
                    }
                }
                .awaitAll()
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
    private const val CONCURRENCY = 24
    private const val CONNECT_TIMEOUT_MILLIS = 350
    private const val READ_TIMEOUT_MILLIS = 600
    private const val REPLY_BYTES = 256
}

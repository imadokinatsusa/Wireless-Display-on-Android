package com.mirror.cast

import com.mirror.cast.discovery.ProbeProtocol
import com.mirror.cast.discovery.ProbeReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 接收端的"门牌"：守在一个**固定端口**上，谁来问就报出自己是谁、信令端口是多少。
 *
 * 它替掉了 UDP 广播。广播是"喊"，对方可能听不见（AP 隔离、热点过滤组播、
 * 换网卡后 socket 还绑在旧接口上）；这里是**应答**，主动找过来的人一定能得到回复。
 *
 * 刻意只做一件事、只说一句话：多余的信息都会让"扫整个网段"这件事变慢。
 * 全程静默 —— 界面上不会出现"端口""信道"这类字眼。
 */
class ProbeResponder(
    private val scope: CoroutineScope,
    private val replyProvider: () -> ProbeReply,
) {

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var failureReason: String? = null
        private set

    fun start(preferredPort: Int = ProbeProtocol.PORT) {
        if (job != null) return
        job = scope.launch {
            val server = withContext(Dispatchers.IO) {
                runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(preferredPort))
                        soTimeout = ACCEPT_POLL_MILLIS
                    }
                }.getOrElse { error ->
                    failureReason = "${error::class.java.simpleName}: ${error.message}"
                    null
                }
            } ?: return@launch

            serverSocket = server
            port = server.localPort
            failureReason = null

            while (isActive) {
                val socket = withContext(Dispatchers.IO) {
                    try {
                        server.accept()
                    } catch (timeout: SocketTimeoutException) {
                        null
                    } catch (error: Exception) {
                        null
                    }
                } ?: continue
                withContext(Dispatchers.IO) { answer(socket) }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        job?.cancel()
        job = null
        port = 0
    }

    /** 读一句、答一句、立刻挂断 —— 扫端口时对面不会等太久。 */
    private fun answer(socket: Socket) {
        try {
            socket.soTimeout = PROBE_TIMEOUT_MILLIS
            val input = socket.getInputStream()
            val buffer = ByteArray(REQUEST_BYTES)
            val read = input.read(buffer)
            if (read <= 0 || !ProbeProtocol.isRequest(buffer.copyOf(read))) return
            val output = socket.getOutputStream()
            output.write(ProbeProtocol.encodeReply(replyProvider()))
            output.flush()
        } catch (error: Exception) {
            // 扫端口的路上什么古怪的客户端都有，答不上就直接放下，不能影响下一位
        } finally {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val ACCEPT_POLL_MILLIS = 500
        const val PROBE_TIMEOUT_MILLIS = 800
        const val REQUEST_BYTES = 64
    }
}
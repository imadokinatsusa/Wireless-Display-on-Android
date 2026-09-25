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
 * 接收端的"门牌"服务：守在一个**固定端口**上，谁来问就报出自己是谁、信令端口是多少。
 *
 * 它和 UDP 广播**并存**：广播继续照发（原来那套一行没动），这里只是多开一个应答口，
 * 让"主动找过来"的发送端也能找到人。两条路谁通了都算通。
 *
 * 刻意只做一件事、只说一句话：任何多余的信息都会让"扫整个网段"这件事变慢。
 */
class ProbeResponder(
    private val scope: CoroutineScope,
    private val replyProvider: () -> ProbeReply,
) {

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    /** 实际绑上的端口。 */
    @Volatile
    var port: Int = 0
        private set

    /** 起不来的原因（端口被占等）。真机没有 adb，这是唯一的出口。 */
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
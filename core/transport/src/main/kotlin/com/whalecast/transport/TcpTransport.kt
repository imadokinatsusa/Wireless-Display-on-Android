package com.whalecast.transport

import com.whalecast.protocol.PacketPeek
import com.whalecast.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** 默认投屏端口：接收端监听，发送端连入。 */
const val DEFAULT_CAST_PORT: Int = 47_921

/**
 * 基于 TCP 的可靠通道。
 *
 * **分帧方式**：直接复用协议包头里的 `payloadLen` 字段 —— 先读满 32 字节包头，
 * 再按声明长度读负载。这样不需要额外的长度前缀（不与协议层重复定义），
 * 而且任何异常数据都会在魔数校验时被立刻发现。
 *
 * 包头魔数不匹配意味着流已失步，此时判定连接失败而不是继续解析垃圾数据 ——
 * 静默地把垃圾当帧喂给解码器是最糟糕的选择。
 */
class TcpTransport internal constructor(
    private val scope: CoroutineScope,
    private val socket: Socket,
) : Transport {

    private val _state = MutableStateFlow<TransportState>(TransportState.Connecting)
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = socket.getOutputStream().buffered()

    private var readerJob: Job? = null

    var sentBytes: Long = 0L
        private set

    var receivedBytes: Long = 0L
        private set

    var receivedPackets: Long = 0L
        private set

    /** 流失步、对端断开等致命错误的原因，供 UI 显示。 */
    var failureReason: String? = null
        private set

    override val state: StateFlow<TransportState> = _state.asStateFlow()

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow().catch { /* 对端关闭即流结束，不算异常 */ }

    val remoteAddress: String
        get() = socket.inetAddress?.hostAddress ?: "?"

    override suspend fun start() {
        if (readerJob != null) return
        _state.value = TransportState.Connected
        readerJob = scope.launch(Dispatchers.IO) {
            val header = ByteArray(Protocol.HEADER_SIZE)
            try {
                while (currentCoroutineContext().isActive) {
                    input.readFully(header)

                    val magic = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                    if (magic != Protocol.MAGIC) {
                        throw IOException("数据流已失步（魔数 0x${magic.toString(16)}）")
                    }
                    val version = header[2].toInt() and 0xFF
                    if (version != Protocol.VERSION) {
                        throw IOException("协议版本不匹配（对端 $version，本端 ${Protocol.VERSION}）")
                    }

                    val payloadLength = PacketPeek.payloadLength(header) ?: 0
                    val payload = ByteArray(payloadLength)
                    if (payloadLength > 0) input.readFully(payload)

                    val packet = ByteArray(Protocol.HEADER_SIZE + payloadLength)
                    header.copyInto(packet, 0)
                    payload.copyInto(packet, Protocol.HEADER_SIZE)

                    receivedBytes += packet.size
                    receivedPackets += 1
                    inbox.send(packet)
                }
            } catch (eof: EOFException) {
                failureReason = "对端已断开"
                _state.value = TransportState.Closed
            } catch (io: IOException) {
                failureReason = io.message ?: "读取失败"
                _state.value = TransportState.Failed(failureReason!!)
            } finally {
                if (!inbox.isClosedForSend) inbox.close()
            }
        }
    }

    override suspend fun send(message: ByteArray): Result<Unit> {
        if (_state.value != TransportState.Connected) {
            return Result.failure(IllegalStateException("通道未连接（${_state.value}）"))
        }
        return withContext(Dispatchers.IO) {
            try {
                writeMutex.withLock {
                    output.write(message)
                    output.flush()
                    sentBytes += message.size
                }
                Result.success(Unit)
            } catch (io: IOException) {
                failureReason = io.message ?: "写入失败"
                _state.value = TransportState.Failed(failureReason!!)
                Result.failure(io)
            }
        }
    }

    override suspend fun close() {
        readerJob?.cancel()
        readerJob = null
        runCatching { socket.close() }
        if (_state.value !is TransportState.Failed) {
            _state.value = TransportState.Closed
        }
        if (!inbox.isClosedForSend) inbox.close()
    }
}

/** TCP 通道的两种建立方式。 */
object TcpTransports {

    /** 发送端：连到接收端。 */
    suspend fun connect(
        host: String,
        port: Int = DEFAULT_CAST_PORT,
        scope: CoroutineScope,
        connectTimeoutMillis: Int = 5_000,
    ): TcpTransport = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        TcpTransport(scope, socket)
    }

    /** 接收端：监听端口，等待发送端连入。端口被占时抛异常。 */
    fun listen(port: Int = DEFAULT_CAST_PORT, scope: CoroutineScope): TcpServer =
        TcpServer(ServerSocket(port).apply { reuseAddress = true }, scope)

    /**
     * 与 [listen] 相同，但**监听失败返回 null 而不是抛异常**。
     *
     * UI 层用它就能把"端口被占用"变成一句可读提示，而不是一次闪退。
     */
    fun listenOrNull(port: Int = DEFAULT_CAST_PORT, scope: CoroutineScope): TcpServer? =
        runCatching { listen(port, scope) }.getOrNull()
}

/** 监听中的接收端。 */
class TcpServer internal constructor(
    private val serverSocket: ServerSocket,
    private val scope: CoroutineScope,
) {

    val localPort: Int get() = serverSocket.localPort

    /** 挂起等待一个发送端连入，返回已建立的通道。 */
    suspend fun awaitClient(): TcpTransport = withContext(Dispatchers.IO) {
        val socket = serverSocket.accept()
        socket.tcpNoDelay = true
        TcpTransport(scope, socket)
    }

    fun close() {
        runCatching { serverSocket.close() }
    }
}

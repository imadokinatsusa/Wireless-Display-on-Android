package com.mirror.cast.signal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** 默认握手/接受超时。局域网里对端就在旁边，不需要等太久。 */
const val DEFAULT_SIGNALING_TIMEOUT_MILLIS: Long = 10_000

/** 信令失败原因 —— 会直接显示在诊断行上（真机没有 adb，这是唯一的现场信息）。 */
class SignalingException(message: String) : Exception(message)

/**
 * 帧读写：把 TCP 字节流切成 [SignalingMessage]。
 *
 * 握手阶段会设置读超时（避免对端连上却不说话就永久挂住），
 * 握手完成后清零，转入正常的长连接等待。
 */
internal class FrameIo(private val socket: Socket) {

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val writeLock = Any()

    fun write(message: SignalingMessage) {
        val frame = SignalingFrame.encode(SignalingCodec.encode(message))
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    /** 读一条消息；对端关闭、超时或报文非法都返回 null。 */
    fun read(): SignalingMessage? = try {
        val header = ByteArray(SignalingFrame.HEADER_BYTES)
        input.readFully(header)
        val length = SignalingFrame.payloadLength(header) ?: return null
        val payload = ByteArray(length)
        input.readFully(payload)
        SignalingCodec.decode(payload)
    } catch (error: Exception) {
        null
    }

    fun close() {
        runCatching { socket.close() }
    }
}

/**
 * 一条已通过握手校验的信令连接。
 *
 * 单向职责：搬运消息。它不理解 SDP，也不决定任何媒体参数。
 */
class SignalingChannel internal constructor(
    private val io: FrameIo,
    /** 对端声明的连接码（已校验一致）。 */
    val remoteCode: String,
) {

    /** 收到的消息流；对端关闭或报文非法时正常结束（不抛异常）。 */
    val incoming: Flow<SignalingMessage> = flow {
        while (true) {
            val message = withContext(Dispatchers.IO) { io.read() } ?: break
            emit(message)
        }
    }

    suspend fun send(message: SignalingMessage): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { io.write(message) }
    }

    suspend fun receive(): SignalingMessage? = withContext(Dispatchers.IO) { io.read() }

    suspend fun close() = withContext(Dispatchers.IO) { io.close() }
}

/**
 * 发起方：连上对端并完成连接码握手。
 */
class SignalingClient(
    private val code: String,
    private val timeoutMillis: Long = DEFAULT_SIGNALING_TIMEOUT_MILLIS,
) {

    suspend fun connect(host: String, port: Int): Result<SignalingChannel> = withContext(Dispatchers.IO) {
        runCatching {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMillis.toInt())
            socket.soTimeout = timeoutMillis.toInt()
            val io = FrameIo(socket)
            try {
                io.write(SignalingMessage.Hello(code))
                val reply = io.read() ?: throw SignalingException("握手失败：对端没有回应连接码")
                if (reply !is SignalingMessage.Hello) {
                    throw SignalingException("握手失败：对端发来了非握手消息")
                }
                if (reply.code != code) {
                    throw SignalingException("连接码不匹配：对端是 ${reply.code}")
                }
                socket.soTimeout = 0
                SignalingChannel(io, reply.code)
            } catch (error: Exception) {
                io.close()
                throw error
            }
        }
    }
}

/**
 * 接受方：监听端口，等待一个连接码正确的对端。
 *
 * 连接码不匹配的连接会被立即关闭并继续等待下一个 —— 局域网上出现陌生连接是常态，
 * 不该因此让整台接收端不可用。
 */
class SignalingServer(private val expectedCode: String) {

    private var serverSocket: ServerSocket? = null

    /** 在 [port] 上监听（0 = 让系统分配），返回实际端口。 */
    suspend fun start(port: Int = 0): Int = withContext(Dispatchers.IO) {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        socket.localPort
    }

    /** 等待一个通过校验的对端。 */
    suspend fun accept(timeoutMillis: Long = DEFAULT_SIGNALING_TIMEOUT_MILLIS): Result<SignalingChannel> =
        withContext(Dispatchers.IO) {
            val server = serverSocket
                ?: return@withContext Result.failure(SignalingException("信令服务尚未启动"))
            val deadline = System.currentTimeMillis() + timeoutMillis
            server.soTimeout = ACCEPT_POLL_MILLIS

            while (System.currentTimeMillis() < deadline) {
                val socket = try {
                    server.accept()
                } catch (timeout: SocketTimeoutException) {
                    continue
                } catch (error: Exception) {
                    return@withContext Result.failure(SignalingException("信令监听已关闭：${error.message}"))
                }
                socket.tcpNoDelay = true
                socket.soTimeout = ACCEPT_POLL_MILLIS * 4
                val outcome = runCatching { handshake(socket) }
                outcome.getOrNull()?.let { return@withContext Result.success(it) }
                runCatching { socket.close() }
            }
            Result.failure(SignalingException("等待对端连接超时"))
        }

    private fun handshake(socket: Socket): SignalingChannel {
        val io = FrameIo(socket)
        val hello = io.read()
        if (hello !is SignalingMessage.Hello) {
            io.close()
            throw SignalingException("握手失败：对端首条消息不是连接码")
        }
        if (hello.code != expectedCode) {
            io.close()
            throw SignalingException("连接码不匹配：对端是 ${hello.code}")
        }
        io.write(SignalingMessage.Hello(expectedCode))
        socket.soTimeout = 0
        return SignalingChannel(io, hello.code)
    }

    fun close() {
        runCatching { serverSocket?.close() }
    }

    companion object {
        /** accept 轮询间隔：让关闭操作能在一小段时间内生效。 */
        private const val ACCEPT_POLL_MILLIS = 250
    }
}

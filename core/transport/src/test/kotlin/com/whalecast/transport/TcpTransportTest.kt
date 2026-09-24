package com.whalecast.transport

import com.whalecast.protocol.ConfigPacketizer
import com.whalecast.protocol.EncodedFrame
import com.whalecast.protocol.FramePacketParser
import com.whalecast.protocol.FramePacketizer
import com.whalecast.protocol.PacketParseResult
import com.whalecast.protocol.VideoConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TCP 通道的集成测试：用 localhost 上的真实 socket，端口交给系统分配（传 0），
 * 因此不会有端口冲突导致的偶发失败。
 */
class TcpTransportTest {

    /** 十六进制字面量不能隐式窄化成 Byte（`byteArrayOf(0x67)` 编译不过），统一走这里。 */
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `两端可以互发协议包`() = runBlocking {
        val server = TcpTransports.listen(0, this)
        val clientTask = async { TcpTransports.connect("127.0.0.1", server.localPort, this@runBlocking) }
        val serverSide = server.awaitClient()
        val clientSide = clientTask.await()

        try {
            serverSide.start()
            clientSide.start()

            // 通道按协议包头分帧，所以只能发完整协议包（裸字符串会让 reader 一直等包头）
            val atServer = CompletableDeferred<VideoConfig>()
            val atClient = CompletableDeferred<VideoConfig>()
            launch {
                serverSide.incoming.collect {
                    if (!atServer.isCompleted) ConfigPacketizer.parse(it)?.let(atServer::complete)
                }
            }
            launch {
                clientSide.incoming.collect {
                    if (!atClient.isCompleted) ConfigPacketizer.parse(it)?.let(atClient::complete)
                }
            }

            val fromClient = VideoConfig(1280, 720, bytes(0, 0, 0, 1, 0x67), bytes(0, 0, 0, 1, 0x68))
            val fromServer = VideoConfig(1920, 1080, bytes(0, 0, 0, 1, 0x67), bytes(0, 0, 0, 1, 0x68))

            clientSide.send(ConfigPacketizer.packetize(fromClient, sessionId = 1)).getOrThrow()
            serverSide.send(ConfigPacketizer.packetize(fromServer, sessionId = 2)).getOrThrow()

            assertEquals(1280, withTimeout(5_000) { atServer.await() }.width)
            assertEquals(1920, withTimeout(5_000) { atClient.await() }.width)
            assertEquals(TransportState.Connected, clientSide.state.value)
            assertTrue(clientSide.sentBytes > 0)
            assertTrue(serverSide.receivedPackets > 0)
        } finally {
            serverSide.close()
            clientSide.close()
            server.close()
        }
    }

    @Test
    fun `视频帧包能原样穿过 TCP 并解析回原数据`() = runBlocking {
        val server = TcpTransports.listen(0, this)
        val clientTask = async { TcpTransports.connect("127.0.0.1", server.localPort, this@runBlocking) }
        val serverSide = server.awaitClient()
        val clientSide = clientTask.await()

        try {
            serverSide.start()
            clientSide.start()

            val origin = EncodedFrame(
                sessionId = 9,
                frameSeq = 7,
                timestampTicks = 630_000,
                isKeyframe = true,
                data = ByteArray(4_000) { (it % 251).toByte() },
            )
            val packets = FramePacketizer.packetize(origin, maxPayloadSize = 1_200)
            assertEquals(4, packets.size)

            val collected = CompletableDeferred<MutableList<ByteArray>>()
            val sink = mutableListOf<ByteArray>()
            launch {
                serverSide.incoming.collect {
                    sink += it
                    if (sink.size == packets.size && !collected.isCompleted) collected.complete(sink)
                }
            }

            packets.forEach { clientSide.send(it).getOrThrow() }
            val received = withTimeout(5_000) { collected.await() }

            val parsed = received.map { assertIs<PacketParseResult.Success>(FramePacketParser.parse(it)).packet }
            val rejoined = ByteArray(parsed.sumOf { it.payload.size })
            var offset = 0
            parsed.sortedBy { it.packetIndex }.forEach {
                it.payload.copyInto(rejoined, offset)
                offset += it.payload.size
            }
            assertTrue(origin.data.contentEquals(rejoined), "经过真实 socket 后数据必须逐字节一致")
            assertEquals(origin.frameSeq, parsed.first().frameSeq)
            assertTrue(parsed.all { it.isKeyframe })
        } finally {
            serverSide.close()
            clientSide.close()
            server.close()
        }
    }

    @Test
    fun `配置包能穿过 TCP 并还原 SPS 与 PPS`() = runBlocking {
        val server = TcpTransports.listen(0, this)
        val clientTask = async { TcpTransports.connect("127.0.0.1", server.localPort, this@runBlocking) }
        val serverSide = server.awaitClient()
        val clientSide = clientTask.await()

        try {
            serverSide.start()
            clientSide.start()

            val config = VideoConfig(
                width = 1080,
                height = 2400,
                csd0 = byteArrayOf(0, 0, 0, 1, 0x67.toByte(), 0x42, 0x00, 0x1E),
                csd1 = byteArrayOf(0, 0, 0, 1, 0x68.toByte(), 0xCE.toByte(), 0x38, 0x80.toByte()),
            )
            val received = CompletableDeferred<VideoConfig>()
            launch { serverSide.incoming.collect { if (!received.isCompleted) received.complete(ConfigPacketizer.parse(it)!!) } }

            clientSide.send(ConfigPacketizer.packetize(config, sessionId = 3)).getOrThrow()
            val parsed = withTimeout(5_000) { received.await() }

            assertEquals(1080, parsed.width)
            assertEquals(2400, parsed.height)
            assertTrue(config.csd0.contentEquals(parsed.csd0))
            assertTrue(config.csd1.contentEquals(parsed.csd1))
        } finally {
            serverSide.close()
            clientSide.close()
            server.close()
        }
    }

    @Test
    fun `对端断开后状态变为 Closed`() = runBlocking {
        val server = TcpTransports.listen(0, this)
        val clientTask = async { TcpTransports.connect("127.0.0.1", server.localPort, this@runBlocking) }
        val serverSide = server.awaitClient()
        val clientSide = clientTask.await()

        try {
            serverSide.start()
            clientSide.start()
            assertEquals(TransportState.Connected, serverSide.state.value)

            clientSide.close()
            // 服务端的读循环应在一个短超时内察觉对端断开
            val deadline = System.currentTimeMillis() + 5_000
            while (serverSide.state.value == TransportState.Connected && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(20)
            }
            assertTrue(
                serverSide.state.value == TransportState.Closed || serverSide.state.value is TransportState.Failed,
                "对端断开后本端不应停留在 Connected，实际 ${serverSide.state.value}",
            )
        } finally {
            serverSide.close()
            clientSide.close()
            server.close()
        }
    }

    @Test
    fun `地址不可达时连接抛出异常而不是默默成功`() = runBlocking {
        // 用一个几乎不可能有人监听的端口
        val failure = runCatching {
            TcpTransports.connect("127.0.0.1", 1, this@runBlocking, connectTimeoutMillis = 1_000)
        }
        assertTrue(failure.isFailure, "连不上的端口必须报错")
    }

    @Test
    fun `本机回环地址可解析`() {
        val loopback = InetAddress.getLoopbackAddress()
        assertTrue(loopback.isLoopbackAddress)
    }
}

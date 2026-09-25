package com.mirror.cast.signal

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 信令通道的真实套接字测试（localhost）。
 *
 * 这里刻意用真实时间与真实端口：握手、超时、断开这些语义的 bug 只在真实 IO 上暴露。
 * 参考 `core:discovery` 的 UDP 环回测试先例。
 */
class SignalingChannelTest {

    private companion object {
        const val CODE = "ABC234"
        const val OTHER_CODE = "XYZ789"
    }

    @Test
    fun handshakeSucceedsAndMessagesFlowBothWays() = runBlocking {
        withTimeout(20_000) {
            val server = SignalingServer(CODE)
            val port = server.start(0)
            try {
                val accepted = async { server.accept(10_000) }
                val client = SignalingClient(CODE).connect("127.0.0.1", port)
                val clientChannel = client.getOrThrow()
                val serverChannel = accepted.await().getOrThrow()

                assertEquals(CODE, clientChannel.remoteCode)
                assertEquals(CODE, serverChannel.remoteCode)

                clientChannel.send(SignalingMessage.Offer("v=0 offer")).getOrThrow()
                val offer = serverChannel.receive()
                assertEquals(SignalingMessage.Offer("v=0 offer"), offer)

                serverChannel.send(SignalingMessage.Answer("v=0 answer")).getOrThrow()
                assertEquals(SignalingMessage.Answer("v=0 answer"), clientChannel.receive())

                serverChannel.send(
                    SignalingMessage.Candidate(sdpMid = "video", sdpMLineIndex = 0, candidate = "candidate:1 udp"),
                ).getOrThrow()
                assertEquals(
                    SignalingMessage.Candidate("video", 0, "candidate:1 udp"),
                    clientChannel.receive(),
                )

                clientChannel.close()
                serverChannel.close()
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun wrongConnectCodeIsRejected() = runBlocking {
        withTimeout(20_000) {
            val server = SignalingServer(CODE)
            val port = server.start(0)
            try {
                val accepted = async { server.accept(2_000) }
                val result = SignalingClient(OTHER_CODE).connect("127.0.0.1", port)
                assertTrue(result.isFailure, "连接码不匹配必须失败，实际：$result")
                val acceptResult = accepted.await()
                assertTrue(acceptResult.isFailure, "服务端不应接受连接码错误的对端")
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun acceptTimesOutWhenNobodyConnects() = runBlocking {
        withTimeout(20_000) {
            val server = SignalingServer(CODE)
            server.start(0)
            try {
                val result = server.accept(700)
                assertTrue(result.isFailure)
                assertTrue(
                    result.exceptionOrNull()?.message?.contains("超时") == true,
                    "失败原因应说明超时：${result.exceptionOrNull()?.message}",
                )
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun receiveReturnsNullAfterPeerCloses() = runBlocking {
        withTimeout(20_000) {
            val server = SignalingServer(CODE)
            val port = server.start(0)
            try {
                val accepted = async { server.accept(10_000) }
                val clientChannel = SignalingClient(CODE).connect("127.0.0.1", port).getOrThrow()
                val serverChannel = accepted.await().getOrThrow()

                clientChannel.close()
                val received = serverChannel.receive()
                assertNull(received, "对端关闭后应读到 null 而不是抛异常")

                serverChannel.close()
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun incomingFlowEndsWhenPeerSaysBye() = runBlocking {
        withTimeout(20_000) {
            val server = SignalingServer(CODE)
            val port = server.start(0)
            try {
                val accepted = async { server.accept(10_000) }
                val clientChannel = SignalingClient(CODE).connect("127.0.0.1", port).getOrThrow()
                val serverChannel = accepted.await().getOrThrow()

                serverChannel.send(SignalingMessage.Bye).getOrThrow()
                val first = clientChannel.receive()
                assertNotNull(first)
                assertEquals(SignalingMessage.Bye, first)

                clientChannel.close()
                serverChannel.close()
            } finally {
                server.close()
            }
        }
    }
}

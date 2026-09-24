package com.whalecast.transport

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopbackTransportTest {

    @Test
    fun `两端可以互发消息`() = runTest(UnconfinedTestDispatcher()) {
        val hub = LoopbackHub(backgroundScope)
        val (a, b) = hub.createPair()
        a.start()
        b.start()

        val receivedByB = mutableListOf<ByteArray>()
        val receivedByA = mutableListOf<ByteArray>()
        backgroundScope.launch { b.incoming.collect { receivedByB += it } }
        backgroundScope.launch { a.incoming.collect { receivedByA += it } }
        runCurrent()

        a.send("hello-b".encodeToByteArray()).getOrThrow()
        b.send("hello-a".encodeToByteArray()).getOrThrow()
        advanceUntilIdle()

        assertEquals(1, receivedByB.size)
        assertEquals("hello-b", receivedByB.single().decodeToString())
        assertEquals(1, receivedByA.size)
        assertEquals("hello-a", receivedByA.single().decodeToString())
    }

    @Test
    fun `未连接时发送返回失败而不是抛异常`() = runTest(UnconfinedTestDispatcher()) {
        val hub = LoopbackHub(backgroundScope)
        val (a, _) = hub.createPair()
        val result = a.send(byteArrayOf(1, 2, 3))
        assertTrue(result.isFailure, "未 start 时应返回失败")
    }

    @Test
    fun `关闭后发送返回失败且状态为 Closed`() = runTest(UnconfinedTestDispatcher()) {
        val hub = LoopbackHub(backgroundScope)
        val (a, b) = hub.createPair()
        a.start()
        b.start()
        a.close()
        assertEquals(TransportState.Closed, a.state.value)
        assertTrue(a.send(byteArrayOf(9)).isFailure)
    }

    @Test
    fun `注入延迟后消息按虚拟时间到达`() = runTest(UnconfinedTestDispatcher()) {
        val hub = LoopbackHub(backgroundScope).apply { latencyMillis = 100 }
        val (a, b) = hub.createPair()
        a.start()
        b.start()

        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { b.incoming.collect { received += it } }
        runCurrent()

        a.send("delayed".encodeToByteArray()).getOrThrow()
        runCurrent()
        assertEquals(0, received.size, "延迟未到时不应送达")

        advanceTimeBy(50)
        runCurrent()
        assertEquals(0, received.size, "50ms 时仍不应送达")

        advanceUntilIdle()
        assertEquals(1, received.size, "虚拟时间推进后应送达")
    }

    @Test
    fun `丢包率 100% 时消息全部丢弃`() = runTest(UnconfinedTestDispatcher()) {
        val hub = LoopbackHub(backgroundScope).apply { dropRate = 1.0 }
        val (a, b) = hub.createPair()
        a.start()
        b.start()

        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { b.incoming.collect { received += it } }
        runCurrent()

        repeat(5) { a.send("lost-$it".encodeToByteArray()).getOrThrow() }
        advanceUntilIdle()

        assertEquals(0, received.size)
        assertEquals(5, hub.droppedCount, "丢弃计数应等于发送次数")
    }

    @Test
    fun `同一 seed 下丢包序列可复现`() = runTest(UnconfinedTestDispatcher()) {
        suspend fun TestScope.droppedAfterSends(seed: Long): Int {
            val hub = LoopbackHub(backgroundScope, seed = seed).apply { dropRate = 0.5 }
            val (a, b) = hub.createPair()
            a.start()
            b.start()
            repeat(20) { index -> a.send("p-$index".encodeToByteArray()).getOrThrow() }
            advanceUntilIdle()
            return hub.droppedCount
        }

        val first = droppedAfterSends(7L)
        val second = droppedAfterSends(7L)
        assertEquals(first, second, "同一 seed 的丢包序列必须可复现")
    }
}

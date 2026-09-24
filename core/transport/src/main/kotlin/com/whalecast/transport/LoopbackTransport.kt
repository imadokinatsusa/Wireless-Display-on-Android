package com.whalecast.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 进程内环回 —— 一对 [Transport] 的"媒人"。
 *
 * 价值有两个：
 * 1. 切片 01 的单机 demo：一台设备上就能看到画面从"发送端"流到"接收端"；
 * 2. 会话级测试：可以注入**延迟**与**丢包**，把网络异常变成确定性可断言的行为。
 *
 * 延迟用协程 `delay` 表达，因此在 `runTest` 的虚拟时间里是瞬时推进的，
 * 测试不需要真实等待（CONTEXT.md 明令禁止 `Thread.sleep`）。
 */
class LoopbackHub(
    private val scope: CoroutineScope,
    seed: Long = 0L,
) {

    /** 单向延迟（毫秒）。 */
    var latencyMillis: Long = 0L

    /** 丢包率，0.0..1.0。 */
    var dropRate: Double = 0.0

    private var random = Random(seed)

    /** 被丢弃的消息数，供测试与统计面板读取。 */
    var droppedCount: Int = 0
        private set

    fun reseed(seed: Long) {
        random = Random(seed)
    }

    /** 造一对互通的 Transport。 */
    fun createPair(): Pair<Transport, Transport> {
        val a = LoopbackTransport(scope, this, "A")
        val b = LoopbackTransport(scope, this, "B")
        a.peer = b
        b.peer = a
        return a to b
    }

    internal fun deliver(target: LoopbackTransport, message: ByteArray) {
        if (dropRate > 0.0 && random.nextDouble() < dropRate) {
            droppedCount++
            return
        }
        scope.launch {
            if (latencyMillis > 0) delay(latencyMillis)
            target.receive(message)
        }
    }
}

internal class LoopbackTransport(
    private val scope: CoroutineScope,
    private val hub: LoopbackHub,
    val name: String,
) : Transport {

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    internal var peer: LoopbackTransport? = null

    internal var sentCount: Int = 0
        private set

    internal var sendFailureCount: Int = 0
        private set

    override val state: StateFlow<TransportState> = _state.asStateFlow()

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    override suspend fun start() {
        if (_state.value == TransportState.Closed) return
        _state.value = TransportState.Connected
    }

    override suspend fun send(message: ByteArray): Result<Unit> {
        if (_state.value != TransportState.Connected) {
            sendFailureCount++
            return Result.failure(IllegalStateException("$name 未连接，无法发送"))
        }
        val target = peer ?: run {
            sendFailureCount++
            return Result.failure(IllegalStateException("$name 没有对端"))
        }
        sentCount++
        hub.deliver(target, message)
        return Result.success(Unit)
    }

    internal suspend fun receive(message: ByteArray) {
        inbox.send(message)
    }

    override suspend fun close() {
        if (_state.value == TransportState.Closed) return
        _state.value = TransportState.Closed
        inbox.close()
    }
}

package com.whalecast.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 连接状态。UI 直接观察它来决定显示"连接中 / 投屏中 / 已断开"。 */
sealed interface TransportState {
    data object Idle : TransportState

    data object Connecting : TransportState

    data object Connected : TransportState

    data class Failed(val reason: String) : TransportState

    data object Closed : TransportState
}

/**
 * **唯一跨端接缝**（见 CONTEXT.md）。
 *
 * 所有跨端逻辑只能穿过它：实现负责线程、Socket、分帧与将来的加解密，
 * 上层只看到"消息进、消息出、状态变"。切片 01 提供进程内环回实现，
 * 切片 02 提供真实局域网 TCP 实现，切片 07 再补 UDP 媒体通道。
 */
interface Transport {

    val state: StateFlow<TransportState>

    /** 收到的消息。单消费者语义：一个 Transfer 实例对应一个收集方。 */
    val incoming: Flow<ByteArray>

    /** 建立连接（环回实现是立刻成功；TCP 实现会真正去连）。 */
    suspend fun start()

    /** 发送一条消息；未连接时返回失败而不是抛异常。 */
    suspend fun send(message: ByteArray): Result<Unit>

    /** 关闭并释放资源。可重复调用。 */
    suspend fun close()
}

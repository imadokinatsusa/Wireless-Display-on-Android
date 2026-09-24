package com.whalecast.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 会话运行时可观测指标（见 CONTEXT.md 的 SessionStats）。
 *
 * 它驱动两件事：UI 上的状态面板，以及将来的 `BitratePolicy` 决策（切片 12）。
 * 所有字段都是单向累加的计数器，便于断言"发生了什么"。
 */
data class StatsSnapshot(
    val packetsSent: Long = 0L,
    val packetsReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val framesSent: Long = 0L,
    val framesReceived: Long = 0L,
    val framesDropped: Long = 0L,
    val malformedPackets: Long = 0L,
    val sendFailures: Long = 0L,
    val lastMalformedReason: String? = null,
) {
    /** 丢帧率（0.0..1.0）；没有收到过帧时返回 0。 */
    val frameDropRate: Double
        get() {
            val total = framesReceived + framesDropped
            return if (total == 0L) 0.0 else framesDropped.toDouble() / total
        }
}

class SessionStats {

    private val _snapshot = MutableStateFlow(StatsSnapshot())

    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    fun onPacketSent(bytes: Int) = _snapshot.update {
        it.copy(packetsSent = it.packetsSent + 1, bytesSent = it.bytesSent + bytes)
    }

    fun onPacketReceived(bytes: Int) = _snapshot.update {
        it.copy(packetsReceived = it.packetsReceived + 1, bytesReceived = it.bytesReceived + bytes)
    }

    fun onFrameSent(bytes: Int) = _snapshot.update {
        it.copy(framesSent = it.framesSent + 1, bytesSent = it.bytesSent + bytes)
    }

    fun onFrameReceived(bytes: Int) = _snapshot.update {
        it.copy(framesReceived = it.framesReceived + 1, bytesReceived = it.bytesReceived + bytes)
    }

    fun onFrameDropped() = _snapshot.update {
        it.copy(framesDropped = it.framesDropped + 1)
    }

    fun onMalformedPacket(reason: String) = _snapshot.update {
        it.copy(malformedPackets = it.malformedPackets + 1, lastMalformedReason = reason)
    }

    fun onSendFailure() = _snapshot.update {
        it.copy(sendFailures = it.sendFailures + 1)
    }

    fun reset() {
        _snapshot.value = StatsSnapshot()
    }
}

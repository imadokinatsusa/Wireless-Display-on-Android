package com.whalecast.protocol

/** 帧被丢弃的原因。 */
enum class DropReason {
    /** 缺包且已被更新的帧取代 —— 切片 07 会据此触发关键帧请求。 */
    MISSING_PACKETS,

    /** 超过在途帧上限，为控制内存被淘汰。 */
    TOO_MANY_PENDING,

    /** 帧序号倒退，视为乱序残留。 */
    STALE,
}

/** [FrameReassembler.onPacket] 产生的事件。一个包可能同时触发多个事件（旧帧被丢 + 新帧完成）。 */
sealed interface ReassemblyEvent {
    data class FrameComplete(val frame: EncodedFrame) : ReassemblyEvent

    data class FrameDropped(
        val sessionId: Int,
        val frameSeq: Long,
        val reason: DropReason,
    ) : ReassemblyEvent

    /** 包已被吸收但不构成完整帧（正常中间态），或重复包被忽略。 */
    data object Ignored : ReassemblyEvent
}

/**
 * 把乱序、可能丢包、可能重复的 [VideoPacket] 重新组装成完整 [EncodedFrame]。
 *
 * 策略（切片 01，延迟优先）：
 * - 允许乱序与重复到达；
 * - **只要缺包就永不交付该帧**（宁可丢帧也不把残缺数据喂给解码器）；
 * - 更新的帧到达时，旧的未完成帧判定为 [DropReason.MISSING_PACKETS] 并上报；
 * - 在途帧数量有上限，超出时淘汰最旧的，避免内存无界增长。
 *
 * 关键帧请求与抖动缓冲属于切片 07，本类不做等待与重传。
 *
 * 纯逻辑、无线程、无 Android 依赖：所有行为都能用可控时钟在单测里断言。
 */
class FrameReassembler(private val maxPendingFrames: Int = 4) {

    init {
        require(maxPendingFrames >= 1) { "maxPendingFrames 至少为 1，实际 $maxPendingFrames" }
    }

    private val pending = LinkedHashMap<Long, PendingFrame>()

    val pendingFrameCount: Int get() = pending.size

    fun onPacket(packet: VideoPacket): List<ReassemblyEvent> {
        val events = mutableListOf<ReassemblyEvent>()

        // 重复包：同一帧同一序号已经收到过，直接忽略（网络重传/多路径的常见结果）。
        val existing = pending[packet.frameSeq]
        if (existing != null && existing.payloads[packet.packetIndex] != null) {
            return listOf(ReassemblyEvent.Ignored)
        }

        // 更新的帧开始到达 → 之前的未完成帧不可能再补齐（发送端不会回头重发旧包）。
        val superseded = pending.keys.filter { it < packet.frameSeq }
        for (seq in superseded) {
            val dropped = pending.remove(seq) ?: continue
            events += ReassemblyEvent.FrameDropped(
                sessionId = dropped.sessionId,
                frameSeq = seq,
                reason = DropReason.MISSING_PACKETS,
            )
        }

        val frame = existing ?: PendingFrame(
            sessionId = packet.sessionId,
            packetCount = packet.packetCount,
            timestampTicks = packet.timestampTicks,
            isKeyframe = packet.isKeyframe,
        ).also { pending[packet.frameSeq] = it }

        if (packet.packetCount != frame.packetCount) {
            // 同一帧序号却报出不同分包数：协议层已不一致，丢掉整帧以免拼出脏数据。
            pending.remove(packet.frameSeq)
            events += ReassemblyEvent.FrameDropped(
                sessionId = frame.sessionId,
                frameSeq = packet.frameSeq,
                reason = DropReason.STALE,
            )
            return events
        }

        frame.payloads[packet.packetIndex] = packet.payload
        frame.received++

        if (frame.isComplete) {
            pending.remove(packet.frameSeq)
            events += ReassemblyEvent.FrameComplete(frame.build(packet.frameSeq))
        }

        // 内存守护：在途帧过多时淘汰最旧的。
        while (pending.size > maxPendingFrames) {
            val oldestSeq = pending.keys.first()
            val dropped = pending.remove(oldestSeq) ?: break
            events += ReassemblyEvent.FrameDropped(
                sessionId = dropped.sessionId,
                frameSeq = oldestSeq,
                reason = DropReason.TOO_MANY_PENDING,
            )
        }

        return events.ifEmpty { listOf(ReassemblyEvent.Ignored) }
    }

    /** 会话结束或重连时清空在途状态。 */
    fun reset() {
        pending.clear()
    }

    private class PendingFrame(
        val sessionId: Int,
        val packetCount: Int,
        val timestampTicks: Long,
        val isKeyframe: Boolean,
    ) {
        val payloads: Array<ByteArray?> = arrayOfNulls(packetCount)
        var received: Int = 0

        val isComplete: Boolean get() = received == packetCount

        fun build(frameSeq: Long): EncodedFrame {
            var total = 0
            for (payload in payloads) total += payload?.size ?: 0
            val data = ByteArray(total)
            var offset = 0
            for (payload in payloads) {
                if (payload == null) continue
                payload.copyInto(data, offset)
                offset += payload.size
            }
            return EncodedFrame(
                sessionId = sessionId,
                frameSeq = frameSeq,
                timestampTicks = timestampTicks,
                isKeyframe = isKeyframe,
                data = data,
            )
        }
    }
}

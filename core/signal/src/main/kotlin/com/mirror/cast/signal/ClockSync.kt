package com.mirror.cast.signal

/**
 * 四时戳时钟标定（NTP 式）。
 *
 * 时间轴定义：
 * - `t0` 发送端发出探测时的本机时刻
 * - `t1` 接收端收到探测时的本机时刻
 * - `t2` 接收端回发应答时的本机时刻
 * - `t3` 发送端收到应答时的本机时刻
 *
 * 设 `θ = 接收端时钟 − 发送端时钟`，`d` 为单程耗时，则：
 * - `t1 = t0 + θ + d`
 * - `t3 = t2 − θ + d`
 *
 * 两式相减消掉 `d`，得 `θ = ((t1 − t0) + (t2 − t3)) / 2`；
 * 往返时延 `rtt = (t3 − t0) − (t2 − t1)`（减去接收端的处理耗时）。
 *
 * 纯逻辑、无 Android 依赖，可在 JVM 上单测。
 */
object ClockSync {

    /** 标定结果。`offsetMillis` 就是 `接收端时钟 − 发送端时钟`。 */
    data class Estimate(val offsetMillis: Long, val roundTripMillis: Long)

    fun estimate(t0: Long, t1: Long, t2: Long, t3: Long): Estimate {
        val offset = ((t1 - t0) + (t2 - t3)) / 2
        val roundTrip = (t3 - t0) - (t2 - t1)
        return Estimate(offsetMillis = offset, roundTripMillis = roundTrip)
    }

    init {
        // 自检：真偏差 500ms、单程 20ms 时，估算必须还原出 500ms。
        // 这条断言放进 init 是为了让"公式被人改坏"在启动时就炸，而不是等装到真机上才发现。
        val sample = estimate(t0 = 0, t1 = 520, t2 = 520, t3 = 40)
        require(sample.offsetMillis == 500L && sample.roundTripMillis == 40L) {
            "时钟标定公式自检失败：$sample"
        }
    }
}

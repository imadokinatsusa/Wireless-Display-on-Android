package com.mirror.cast

import kotlinx.coroutines.flow.StateFlow

/**
 * 一次投屏会话的门面。
 *
 * UI 只依赖这个接口：发送端与接收端的实现都在 `web/` 目录里，
 * 界面不需要知道 PeerConnection、VideoTrack 或 SurfaceViewRenderer 的存在。
 */
interface CastSession {

    val state: StateFlow<SessionState>

    /** 无 adb 时唯一的现场数据来源。 */
    val diagnostics: StateFlow<Diagnostics>

    /** 停止并释放全部资源；可重复调用。 */
    suspend fun stop()
}

/**
 * 会话的**可选**能力：画质可调（分辨率 + 码率联动），只有发送端实现。
 *
 * 调的是编码输出，不是虚拟屏尺寸（后者必须与屏幕一致）。
 * 「自动」打开时会根据实时网络状况（丢包/RTT）自行升降档。
 */
interface QualityAdjustable {

    val quality: CaptureSpec.Quality

    /** 自动画质是否开启。 */
    val autoQuality: Boolean

    suspend fun setQuality(quality: CaptureSpec.Quality): Boolean

    suspend fun setAutoQuality(enabled: Boolean)

    /** 当前帧率（fps）。 */
    val frameRate: Int

    /** 换帧率（接收端也能通过反向请求触发）。 */
    suspend fun setFrameRate(fps: Int): Boolean

    /** 最近一次的网络观测值（丢包率 0..1、往返时延毫秒、估算码率 kbps）。 */
    val linkStats: LinkStats
}

/** 发送端观测到的链路状况。 */
data class LinkStats(
    val lostFraction: Double = 0.0,
    val roundTripMs: Double = 0.0,
    val bitrateKbps: Double = 0.0,
) {
    val lostPercent: String get() = "%.1f%%".format(lostFraction * 100)
}

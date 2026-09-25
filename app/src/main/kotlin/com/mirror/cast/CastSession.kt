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

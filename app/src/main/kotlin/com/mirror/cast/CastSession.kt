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
 * 会话的**可选**能力：画质可调（只有发送端实现）。
 *
 * 接收端不需要它，所以不塞进 [CastSession] 主接口 —— 界面用 `as?` 探测即可。
 * 调的是**编码输出**尺寸，不是虚拟屏尺寸（后者必须与屏幕一致）。
 */
interface ResolutionAdjustable {

    /** 换一档画质；0 表示不缩放。返回是否真的应用成功。 */
    suspend fun setQuality(quality: CaptureSpec.Quality): Boolean

    /** 当前档位。 */
    val quality: CaptureSpec.Quality
}

package com.mirror.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内会话登记：Activity 与前台服务之间的唯一桥。
 *
 * 投屏必须活在 Service 里（否则切后台就被系统掐），而界面随时可能被重建；
 * 用一个进程内单例把"当前会话"摆在这里，界面重建后照样能看到状态与诊断行。
 * M1 只需要一个会话，不做多会话管理。
 */
object SessionRegistry {

    private val _active = MutableStateFlow<CastSession?>(null)

    val active: StateFlow<CastSession?> = _active.asStateFlow()

    fun set(session: CastSession?) {
        _active.value = session
    }

    fun clear() {
        _active.value = null
    }
}

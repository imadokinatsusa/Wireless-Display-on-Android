package com.mirror.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 只用来把失败原因摆在界面上的空会话。
 *
 * 存在的理由：真机没有 adb，"服务没能启动"这件事也必须有地方显示 ——
 * 否则界面只会静静地停在"空闲"，主人只能猜。
 */
class FailedSession(reason: String) : CastSession {

    private val _state = MutableStateFlow<SessionState>(SessionState.Failed(reason))
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(Diagnostics(state = "失败：$reason"))
    override val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    override suspend fun stop() {
        _state.value = SessionState.Closed
    }
}

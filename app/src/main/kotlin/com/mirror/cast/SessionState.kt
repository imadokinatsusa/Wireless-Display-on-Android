package com.mirror.cast

/**
 * 一次投屏会话的状态。
 *
 * **为什么用密封状态而不是若干布尔**：真机上没有 adb，界面是唯一的现场信息出口。
 * 状态机必须能表达"现在是哪一步、失败在哪一步"，而不是"能不能投"。
 */
sealed interface SessionState {

    /** 尚未开始。 */
    data object Idle : SessionState

    /** 已请求屏幕授权，等待系统对话框返回。 */
    data object RequestingPermission : SessionState

    /** 已拿到对端地址，正在交换会话描述与网络候选。 */
    data class Connecting(val peer: String) : SessionState

    /** 通道已建立，正在传画面。 */
    data class Streaming(val peer: String) : SessionState

    /** 失败：`reason` 直接显示在诊断行上，必须是人能看懂的一句话。 */
    data class Failed(val reason: String) : SessionState

    /** 正常结束。 */
    data object Closed : SessionState

    /** 给诊断行用的一行文字。 */
    val label: String
        get() = when (this) {
            Idle -> "空闲"
            RequestingPermission -> "等待屏幕授权"
            is Connecting -> "连接中：$peer"
            is Streaming -> "投屏中：$peer"
            is Failed -> "失败：$reason"
            Closed -> "已断开"
        }
}

/**
 * 诊断数据：无 adb 时唯一的现场数据来源。
 *
 * 字段刻意都是"人能一眼读懂"的标量，不做花哨格式化。
 */
data class Diagnostics(
    val state: String = "空闲",
    val resolution: String? = null,
    val fps: Int? = null,
    val frames: Long? = null,
    val note: String? = null,
) {
    /** 拼成一行（界面宽度有限，只显示有效字段）。 */
    fun line(): String = buildList {
        add(state)
        resolution?.let { add(it) }
        fps?.let { add("${it}fps") }
        frames?.let { add("${it}帧") }
        note?.let { add(it) }
    }.joinToString(" · ")
}

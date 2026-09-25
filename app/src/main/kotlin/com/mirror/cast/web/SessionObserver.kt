package com.mirror.cast.web

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.VideoTrack

/**
 * 把 `PeerConnection` 的十几个回调收敛成我们真正关心的四件事：
 * 有新候选、连上了、挂了、远端画面来了。
 *
 * **方法清单来自 AAR 字节码实证**（`javap org.webrtc.PeerConnection$Observer`）：
 * abstract（必须实现）= onSignalingChange / onIceConnectionChange / onIceConnectionReceivingChange
 * / onIceGatheringChange / onIceCandidate / onIceCandidatesRemoved / onAddStream / onRemoveStream
 * / onDataChannel / onRenegotiationNeeded；
 * default（可选）= onStandardizedIceConnectionChange / onConnectionChange / onIceCandidateError
 * / onSelectedCandidatePairChanged / onAddTrack / onRemoveTrack / onTrack。
 */
internal class SessionObserver(
    private val onCandidate: (IceCandidate) -> Unit,
    private val onConnected: () -> Unit,
    private val onFailed: (String) -> Unit,
    private val onRemoteVideo: (VideoTrack) -> Unit,
    /**
     * ICE 状态的每一步都上报。
     *
     * 卡在"连接中"的时候，这是**唯一**能说明卡在哪儿的信号：
     * `CHECKING` = 还在互相试探（多半是候选地址对不上）；
     * `FAILED` = 彻底打不通；`CONNECTED` = 媒体通道成了。
     */
    private val onIceState: (String) -> Unit = {},
) : PeerConnection.Observer {

    // ── abstract 成员：这些必须实现，漏一个就编译不过 ──────────────────────────

    override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

    override fun onAddStream(stream: MediaStream?) = Unit

    override fun onRemoveStream(stream: MediaStream?) = Unit

    override fun onDataChannel(dataChannel: DataChannel?) = Unit

    override fun onRenegotiationNeeded() = Unit

    override fun onIceCandidate(candidate: IceCandidate?) {
        candidate?.let(onCandidate)
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        onIceState("ICE ${newState?.name ?: "未知"}")
        when (newState) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED,
            -> onConnected()

            PeerConnection.IceConnectionState.FAILED -> onFailed("网络候选协商失败（ICE failed）")
            PeerConnection.IceConnectionState.DISCONNECTED -> onFailed("连接已断开（ICE disconnected）")
            else -> Unit
        }
    }

    /** 候选收集失败（例如权限或网络限制）—— 这类原因平时完全看不见，必须报出来。 */
    override fun onIceCandidateError(
        address: String?,
        port: Int,
        url: String?,
        errorCode: Int,
        errorText: String?,
    ) {
        onIceState("候选失败 $errorCode $errorText")
    }

    // ── default 成员：挑有用的覆盖 ────────────────────────────────────────────

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
        when (newState) {
            PeerConnection.PeerConnectionState.CONNECTED -> onConnected()
            PeerConnection.PeerConnectionState.FAILED -> onFailed("媒体连接失败")
            PeerConnection.PeerConnectionState.DISCONNECTED -> onFailed("媒体连接断开")
            else -> Unit
        }
    }

    /** 接收端拿到远端画面就在这里。 */
    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
        val track = receiver?.track()
        if (track is VideoTrack) onRemoteVideo(track)
    }
}

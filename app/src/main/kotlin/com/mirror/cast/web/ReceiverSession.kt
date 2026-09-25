package com.mirror.cast.web

import com.mirror.cast.CastSession
import com.mirror.cast.Diagnostics
import com.mirror.cast.SessionState
import com.mirror.cast.signal.SignalingChannel
import com.mirror.cast.signal.SignalingMessage
import com.mirror.cast.signal.SignalingServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * 接收端会话：等发送端来连 → 协商 → 把远端画面挂到渲染器上。
 *
 * 接收端不需要采集，所以也不需要前台服务；它的寿命跟着界面走。
 * 声音由媒体栈直接播放（这正是引入 libwebrtc 的收益之一：不用自己写播放与抖动缓冲）。
 */
class ReceiverSession(
    private val runtime: WebRtcRuntime,
    private val code: String,
    private val expectedPeerCode: String? = null,
) : CastSession {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(Diagnostics(state = "待机"))
    override val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    private val outgoing = Channel<SignalingMessage>(Channel.UNLIMITED)
    private val server = SignalingServer(code)

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var channel: SignalingChannel? = null
    private var peerConnection: PeerConnection? = null
    private var remoteVideo: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null

    /** 信令监听端口（0 表示尚未启动）。UI 拿到它之后再去广播连接码。 */
    @Volatile
    var signalingPort: Int = 0
        private set

    /** 开始监听，返回实际端口。 */
    suspend fun prepare(): Int {
        val port = server.start(0)
        signalingPort = port
        _diagnostics.update { it.copy(state = "等待连接（端口 $port）") }
        return port
    }

    /** 绑定渲染器；远端画面到达后会自动挂上去。 */
    fun attachRenderer(view: SurfaceViewRenderer) {
        val previous = renderer
        if (previous === view) return
        if (previous != null && remoteVideo != null) {
            runCatching { remoteVideo?.removeSink(previous) }
        }
        renderer = view
        remoteVideo?.let { track -> runCatching { track.addSink(view) } }
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        this.scope = scope
        job = scope.launch {
            try {
                runSession()
            } catch (error: Exception) {
                fail("${error::class.java.simpleName}: ${error.message}")
            }
        }
    }

    private suspend fun runSession() {
        if (signalingPort == 0) prepare()
        runtime.ensureStarted()

        val accepted = server.accept().getOrElse { error ->
            throw IllegalStateException("等待发送端失败：${error.message}")
        }
        channel = accepted
        _state.value = SessionState.Connecting(expectedPeerCode ?: "已连接发送端")
        _diagnostics.update { it.copy(state = "协商中") }

        scope?.launch {
            for (message in outgoing) {
                accepted.send(message)
            }
        }

        val observer = SessionObserver(
            onCandidate = { candidate: IceCandidate ->
                outgoing.trySend(
                    SignalingMessage.Candidate(
                        sdpMid = candidate.sdpMid ?: "",
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp,
                    ),
                )
            },
            onConnected = {
                _state.value = SessionState.Streaming("发送端")
                _diagnostics.update { it.copy(state = "接收中") }
            },
            onFailed = { reason -> fail(reason) },
            onRemoteVideo = { track -> bindRemoteVideo(track) },
        )

        for (message in accepted.incoming) {
            when (message) {
                is SignalingMessage.Offer -> handleOffer(message, observer)
                is SignalingMessage.Candidate -> peerConnection?.let { connection ->
                    runtime.onSignaling {
                        connection.addIceCandidate(
                            IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate),
                        )
                    }
                }

                SignalingMessage.Bye -> {
                    _diagnostics.update { it.copy(state = "发送端已停止") }
                    stop()
                    return
                }

                else -> Unit
            }
        }
    }

    private suspend fun handleOffer(offer: SignalingMessage.Offer, observer: SessionObserver) {
        val connection = runtime.onSignaling {
            val factory = runtime.requireFactory()
            val config = PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            factory.createPeerConnection(config, observer) ?: error("创建 PeerConnection 失败")
        }
        peerConnection = connection

        runtime.onSignaling {
            connection.setRemoteAwait(SessionDescription(SessionDescription.Type.OFFER, offer.sdp))
        }
        val answer = runtime.onSignaling { connection.awaitAnswer() }
        runtime.onSignaling { connection.setLocalAwait(answer) }
        channel?.send(SignalingMessage.Answer(answer.description))?.getOrElse { error ->
            throw IllegalStateException("发送应答失败：${error.message}")
        }
    }

    private fun bindRemoteVideo(track: VideoTrack) {
        remoteVideo = track
        renderer?.let { view ->
            runCatching { track.addSink(view) }
        }
        _diagnostics.update { it.copy(state = "已收到画面") }
    }

    private fun fail(reason: String) {
        _state.value = SessionState.Failed(reason)
        _diagnostics.update { it.copy(state = "失败：$reason") }
        scope?.launch { stop() }
    }

    override suspend fun stop() {
        // 同 SenderSession：不 cancel 自己所在的协程，靠关闭监听与信令通道退出循环
        server.close()

        runCatching { channel?.send(SignalingMessage.Bye) }
        runCatching { channel?.close() }
        channel = null

        remoteVideo?.let { track -> renderer?.let { view -> runCatching { track.removeSink(view) } } }
        remoteVideo = null

        runtime.onSignaling {
            runCatching { peerConnection?.close() }
            runCatching { peerConnection?.dispose() }
            peerConnection = null
        }

        if (_state.value !is SessionState.Failed) {
            _state.value = SessionState.Closed
        }
        _diagnostics.update { it.copy(state = "已停止") }
    }

    /**
     * 渲染器由界面创建与释放；会话结束时只解绑。
     * （`SurfaceViewRenderer.release()` 必须在主线程、EGL 仍有效时调用，交给界面最安全。）
     */
    fun detachRenderer() {
        remoteVideo?.let { track -> renderer?.let { view -> runCatching { track.removeSink(view) } } }
        renderer = null
    }
}

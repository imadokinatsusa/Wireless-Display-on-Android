package com.mirror.cast.web

import com.mirror.cast.CastSession
import com.mirror.cast.Diagnostics
import com.mirror.cast.SessionState
import com.mirror.cast.signal.SignalingChannel
import com.mirror.cast.signal.SignalingMessage
import com.mirror.cast.signal.SignalingServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * 接收端会话：等发送端来连 → 协商 → 把远端画面挂到渲染器上。
 *
 * 行为准则：**接收端是"守在那里"的一方**。
 * - 一次等待超时不算失败，继续等（诊断行会写已等多久）；
 * - 发送端停下来之后，只释放这一个连接、**回去继续等下一个**，而不是把自己关掉；
 * - 真正的"停止"只发生在界面退出时（[shutdown]）。
 *
 * 它还承担一件"反向"的事：画质与帧率是发送端的编码参数，但用户是在**看画面这台设备**
 * 上做决定，所以接收端通过信令连接把 [SignalingMessage.QualityRequest] 发回去。
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

    /** 收尾专用：不随界面协程一起被取消（否则对端永远等不到 Bye）。 */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /** 当前生效（或最近请求）的画质档位名与帧率，用于界面回显。 */
    @Volatile
    var requestedQuality: String = ""

    @Volatile
    var requestedFrameRate: Int = 0

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

    /**
     * 请求发送端换画质/帧率。
     *
     * 走的是已经建立的信令连接（本来就双向），所以不需要任何额外的控制通道。
     * 失败（还没连上）时返回 false，界面据此提示"尚未连接"。
     */
    suspend fun requestQuality(qualityName: String, fps: Int): Boolean {
        requestedQuality = qualityName
        requestedFrameRate = fps
        val target = channel ?: return false
        return target.send(SignalingMessage.QualityRequest(quality = qualityName, frameRate = fps)).isSuccess
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        this.scope = scope
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                attempt += 1
                try {
                    runSession()
                    // runSession 正常返回 = 对端走了：接着守，等下一个
                    _state.value = SessionState.Idle
                    attempt = 0
                } catch (bye: PeerSaidBye) {
                    _diagnostics.update { it.copy(state = "发送端已停止，继续等待…") }
                    runCatching { releasePeer() }
                    _state.value = SessionState.Idle
                    attempt = 0
                } catch (error: Exception) {
                    // 注意：这里**不能**调 stop()，那会把监听端口也关掉、再也等不到下一个
                    runCatching { releasePeer() }
                    if (attempt >= MAX_ATTEMPTS) {
                        fail("${error::class.java.simpleName}: ${error.message}")
                        return@launch
                    }
                    _diagnostics.update {
                        it.copy(state = "失败，${RETRY_DELAY_MILLIS / 1000}s 后重新等待：${error.message}")
                    }
                    delay(RETRY_DELAY_MILLIS)
                }
            }
        }
    }

    /**
     * 一直等到有发送端连上来。
     *
     * 一次等待超时**不代表失败**（对端可能还没打开界面），所以循环等待，
     * 并把已等待时长写到诊断行上 —— 否则用户只看到界面"卡住不动"。
     */
    private suspend fun awaitPeer(): SignalingChannel {
        var rounds = 0
        while (currentCoroutineContext().isActive) {
            server.accept(ACCEPT_TIMEOUT_MILLIS).getOrNull()?.let { return it }
            rounds += 1
            _diagnostics.update {
                it.copy(state = "等待发送端…（已等 ${rounds * (ACCEPT_TIMEOUT_MILLIS / 1000)}s）")
            }
        }
        throw IllegalStateException("等待被取消")
    }

    fun shutdown() {
        teardownScope.launch { stop() }
    }

    private suspend fun runSession() {
        if (signalingPort == 0) prepare()
        runtime.ensureStarted()

        val accepted = awaitPeer()
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

        accepted.incoming.collect { message ->
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
                    throw PeerSaidBye()
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
                // 抖动缓冲调小：默认 50 包（约 1 秒）会让声音明显滞后、看着不同步
                audioJitterBufferMaxPackets = 12
                audioJitterBufferFastAccelerate = true
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

    /**
     * 只释放"这一个对端"的资源，**保留监听**。
     *
     * 对端断开后接收端要能接着等下一个，而不是把自己也关掉 ——
     * 所以"收尾一个连接"与"彻底停止"必须分开。
     */
    private suspend fun releasePeer() {
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
    }

    override suspend fun stop() {
        // 同 SenderSession：不 cancel 自己所在的协程，靠关闭监听与信令通道退出循环
        server.close()
        releasePeer()

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

    private companion object {
        /** 一次等待的超时：超时后**继续等**，不是失败。 */
        const val ACCEPT_TIMEOUT_MILLIS = 10_000L

        /** 协商失败后的重试上限与间隔。 */
        const val MAX_ATTEMPTS = 20
        const val RETRY_DELAY_MILLIS = 2_000L
    }
}

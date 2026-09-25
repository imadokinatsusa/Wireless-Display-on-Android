package com.mirror.cast.web

import android.content.Context
import android.media.projection.MediaProjection
import com.mirror.cast.CaptureSpec
import com.mirror.cast.CastSession
import com.mirror.cast.Diagnostics
import com.mirror.cast.SessionState
import com.mirror.cast.signal.SignalingClient
import com.mirror.cast.signal.SignalingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * 发送端会话：屏幕 + 系统声音 → 媒体栈 → 对端。
 *
 * 关键约束（都来自前面对 AAR 的实证）：
 * - `MediaProjection` 只取一次，视频（虚拟屏）与音频（内录）共用 —— Android 14 起 consent token 不可复用；
 * - 所有媒体栈调用都走 [WebRtcRuntime.onSignaling]（signaling 线程纪律）；
 * - ICE 候选由回调线程推入队列，再由协程搬给信令连接，避免在回调里做 IO。
 */
class SenderSession(
    private val context: Context,
    private val runtime: WebRtcRuntime,
    private val projection: MediaProjection,
    private val spec: CaptureSpec.Spec,
    private val host: String,
    private val signalingPort: Int,
    private val code: String,
) : CastSession {

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting("$host:$signalingPort"))
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(
        Diagnostics(state = "连接中", resolution = spec.label),
    )
    override val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    /** ICE 候选从回调线程排队到这里，再由协程写进信令连接。 */
    private val outgoing = Channel<SignalingMessage>(Channel.UNLIMITED)

    /** 收尾专用：不随界面/服务协程一起被取消。 */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var scope: CoroutineScope? = null
    private var pumpJob: Job? = null
    private var statsJob: Job? = null

    private var channel: com.mirror.cast.signal.SignalingChannel? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var capturer: ProjectionVideoCapturer? = null
    private var playback: PlaybackAudioCapturer? = null

    fun start(scope: CoroutineScope) {
        if (pumpJob != null) return
        this.scope = scope
        // 失败不放弃：局域网里对端可能还没准备好，重试到成功或用尽次数为止。
        pumpJob = scope.launch {
            var attempt = 0
            while (isActive) {
                attempt += 1
                try {
                    runSession()
                    return@launch
                } catch (bye: PeerSaidBye) {
                    _state.value = SessionState.Closed
                    stop()
                    return@launch
                } catch (error: Exception) {
                    runCatching { stop() }
                    if (attempt >= MAX_ATTEMPTS) {
                        fail("${error::class.java.simpleName}: ${error.message}")
                        return@launch
                    }
                    _diagnostics.update {
                        it.copy(state = "失败，${RETRY_DELAY_MILLIS / 1000}s 后自动重试（第 $attempt 次）：${error.message}")
                    }
                    delay(RETRY_DELAY_MILLIS)
                }
            }
        }
    }

    private suspend fun runSession() {
        runtime.ensureStarted()

        // ── 1) 信令 ────────────────────────────────────────────────────────────
        val signaling = SignalingClient(code).connect(host, signalingPort).getOrElse { error ->
            throw IllegalStateException("连接对端失败：${error.message}")
        }
        channel = signaling
        scope?.launch {
            for (message in outgoing) {
                signaling.send(message)
            }
        }

        // ── 2) 系统声音内录：失败只降级、不中断 ────────────────────────────────
        val audioCapture = PlaybackAudioCapturer(projection) { note ->
            _diagnostics.update { it.copy(note = note) }
        }
        if (audioCapture.start()) {
            playback = audioCapture
            runtime.audioBridge.delegate = audioCapture
        }

        // ── 3) PeerConnection 与两条轨道 ───────────────────────────────────────
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
                _state.value = SessionState.Streaming("$host:$signalingPort")
                _diagnostics.update { it.copy(state = "投屏中") }
            },
            onFailed = { reason -> fail(reason) },
            onRemoteVideo = { /* 发送端不接收画面 */ },
        )

        val connection = runtime.onSignaling {
            val factory = runtime.requireFactory()
            val newVideoSource = factory.createVideoSource(true)
            val newVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, newVideoSource)
            val newAudioSource = factory.createAudioSource(MediaConstraints())
            val newAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, newAudioSource)

            val config = PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>()).apply {
                // 无服务器直连：不配任何 STUN/TURN，只靠本机候选（见 docs/adr/0002）
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            val created = factory.createPeerConnection(config, observer)
                ?: error("创建 PeerConnection 失败")
            created.addTrack(newVideoTrack, listOf(STREAM_ID))
            created.addTrack(newAudioTrack, listOf(STREAM_ID))

            videoSource = newVideoSource
            videoTrack = newVideoTrack
            audioSource = newAudioSource
            audioTrack = newAudioTrack
            created
        }
        peerConnection = connection

        // ── 4) 采集（虚拟屏尺寸 = 屏幕真实尺寸） ───────────────────────────────
        startCapture()

        // ── 5) 发出 offer ─────────────────────────────────────────────────────
        val offer = runtime.onSignaling { connection.awaitOffer() }
        runtime.onSignaling { connection.setLocalAwait(offer) }
        signaling.send(SignalingMessage.Offer(offer.description)).getOrElse { error ->
            throw IllegalStateException("发送会话描述失败：${error.message}")
        }

        // ── 6) 等 answer / 候选 ───────────────────────────────────────────────
        signaling.incoming.collect { message ->
            when (message) {
                is SignalingMessage.Answer -> runtime.onSignaling {
                    connection.setRemoteAwait(
                        SessionDescription(SessionDescription.Type.ANSWER, message.sdp),
                    )
                }

                is SignalingMessage.Candidate -> runtime.onSignaling {
                    connection.addIceCandidate(
                        IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate),
                    )
                }

                SignalingMessage.Bye -> {
                    _state.value = SessionState.Closed
                    throw PeerSaidBye()
                }

                else -> Unit
            }
        }

        // 信令连接断了：会话也就结束了
        if (_state.value !is SessionState.Failed) {
            _state.value = SessionState.Closed
            _diagnostics.update { it.copy(state = "对端已断开") }
        }
    }

    private suspend fun startCapture() {
        val source = videoSource ?: error("视频源尚未就绪")
        val created = ProjectionVideoCapturer(projection, spec.densityDpi)
        val textureHelper = SurfaceTextureHelper.create("mirror-capture", runtime.eglContext)
        runtime.onSignaling {
            created.initialize(textureHelper, context, source.capturerObserver)
            created.startCapture(spec.width, spec.height, CaptureSpec.FRAME_RATE)
        }
        capturer = created

        statsJob = scope?.launch {
            var previous = 0L
            while (isActive) {
                delay(1_000)
                val current = created.capturedFrames
                _diagnostics.update {
                    it.copy(fps = (current - previous).toInt(), frames = current)
                }
                previous = current
            }
        }
    }

    private fun fail(reason: String) {
        _state.value = SessionState.Failed(reason)
        _diagnostics.update { it.copy(state = "失败：$reason") }
        scope?.launch { stop() }
    }

    /**
     * 由外部（前台服务）调用的**非挂起**收尾入口。
     *
     * 用独立 scope 而不是调用方的 scope：调用方可能正处在被取消的协程里，
     * 那样"发 Bye、关连接"就会半途夭折，对端只能干等超时。
     */
    fun shutdown() {
        teardownScope.launch { stop() }
    }

    override suspend fun stop() {
        statsJob?.cancel()
        statsJob = null
        // 刻意**不** cancel 会话协程：对端说 Bye 时，调用栈里正是那个协程自己，
        // 自我取消会让后面的释放逻辑被 CancellationException 打断、把状态错标成失败。
        // 关掉信令通道就足以让消息循环自然退出。

        runCatching { channel?.send(SignalingMessage.Bye) }
        runCatching { channel?.close() }
        channel = null

        runtime.onSignaling {
            runCatching { capturer?.stopCapture() }
            runCatching { capturer?.dispose() }
            capturer = null

            runCatching { peerConnection?.close() }
            runCatching { peerConnection?.dispose() }
            peerConnection = null

            runCatching { videoTrack?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { audioTrack?.dispose() }
            runCatching { audioSource?.dispose() }
            videoTrack = null
            videoSource = null
            audioTrack = null
            audioSource = null
        }

        runtime.audioBridge.delegate = null
        runCatching { playback?.stop() }
        playback = null

        if (_state.value !is SessionState.Failed) {
            _state.value = SessionState.Closed
        }
        _diagnostics.update { it.copy(state = "已停止") }
    }

    private companion object {
        const val VIDEO_TRACK_ID = "mirror-video"
        const val AUDIO_TRACK_ID = "mirror-audio"
        const val STREAM_ID = "mirror"

        /** 自动重试次数与间隔：局域网里等对端就绪，最多等这么久。 */
        const val MAX_ATTEMPTS = 20
        const val RETRY_DELAY_MILLIS = 2_000L
    }
}


/** 对端主动说 Bye：正常收尾，不是失败。 */
internal class PeerSaidBye : Exception()

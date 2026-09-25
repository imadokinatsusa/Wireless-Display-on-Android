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
import kotlinx.coroutines.Job
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
import org.webrtc.RtpParameters
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
        pumpJob = scope.launch {
            try {
                runSession()
            } catch (bye: PeerSaidBye) {
                _state.value = SessionState.Closed
                stop()
            } catch (error: Exception) {
                fail("${error::class.java.simpleName}: ${error.message}")
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
                // 屏幕内容场景关掉 CPU 过载检测：它按摄像头场景调优，投屏时只会白白降帧
                enableCpuOveruseDetection = false
                // 投屏下限码率：太低会让文字糊成一团
                screencastMinBitrate = MIN_SCREENCAST_BITRATE
            }
            val created = factory.createPeerConnection(config, observer)
                ?: error("创建 PeerConnection 失败")
            val videoSender = created.addTrack(newVideoTrack, listOf(STREAM_ID))
            created.addTrack(newAudioTrack, listOf(STREAM_ID))
            // 保帧率优先：屏幕内容宁可分辨率降一点，也不要卡顿
            runCatching {
                val params = videoSender.parameters
                params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                params.encodings.forEach { encoding ->
                    encoding.maxBitrateBps = spec.bitRate
                    encoding.maxFramerate = CaptureSpec.FRAME_RATE
                }
                videoSender.setParameters(params)
            }

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
        val (encodeWidth, encodeHeight) = CaptureSpec.encodeSize(spec.width, spec.height)
        val created = ProjectionVideoCapturer(projection, spec.densityDpi)
        val textureHelper = SurfaceTextureHelper.create("mirror-capture", runtime.eglContext)
        runtime.onSignaling {
            created.initialize(textureHelper, context, source.capturerObserver)
            created.startCapture(spec.width, spec.height, CaptureSpec.FRAME_RATE)
            // 采集仍是屏幕真实尺寸，但编码输出压到长边 1920 —— 帧率就是这么换回来的
            source.adaptOutputFormat(encodeWidth, encodeHeight, CaptureSpec.FRAME_RATE)
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

        /** 投屏下限码率（1.5Mbps）：低于这个数文字就开始糊。 */
        const val MIN_SCREENCAST_BITRATE = 1_500_000
    }
}


/** 对端主动说 Bye：正常收尾，不是失败。 */
internal class PeerSaidBye : Exception()

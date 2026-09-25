package com.mirror.cast.web

import android.content.Context
import android.media.projection.MediaProjection
import com.mirror.cast.CaptureSpec
import com.mirror.cast.CastSession
import com.mirror.cast.Diagnostics
import com.mirror.cast.LinkStats
import com.mirror.cast.QualityAdjustable
import com.mirror.cast.SessionState
import com.mirror.cast.signal.SignalingChannel
import com.mirror.cast.signal.SignalingClient
import com.mirror.cast.signal.SignalingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.coroutines.resume

/**
 * 发送端会话：屏幕 + 系统声音 → 媒体栈 → 对端。
 *
 * 关键约束（都来自对 AAR 的字节码实证）：
 * - `MediaProjection` 只取一次，视频（虚拟屏）与音频（内录）共用 —— Android 14 起 consent token 不可复用；
 * - 媒体栈调用都走 [WebRtcRuntime.onSignaling]（signaling 线程纪律）；
 * - ICE 候选由回调线程推入队列，再由协程搬给信令连接。
 *
 * **带宽分工**：采集尺寸锁在屏幕真实尺寸（Android 14 的要求），编码输出可调；
 * 发送端定**码率上限**（这条链路能花多少带宽），画质与帧率则由发送端或接收端在上限之内调。
 * 最终编码码率 = min(码率上限, 画质档位上限, 按分辨率与帧率估算值)。
 */
class SenderSession(
    private val context: Context,
    private val runtime: WebRtcRuntime,
    private val projection: MediaProjection,
    private val spec: CaptureSpec.Spec,
    private val host: String,
    private val signalingPort: Int,
    private val code: String,
    initialQuality: CaptureSpec.Quality = CaptureSpec.DEFAULT_QUALITY,
    initialFrameRate: Int = CaptureSpec.DEFAULT_FRAME_RATE,
    initialBitrateKbps: Int = 0,
) : CastSession, QualityAdjustable {

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting("$host:$signalingPort"))
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(Diagnostics(state = "连接中", resolution = spec.label))
    override val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    /** ICE 候选从回调线程排队到这里，再由协程写进信令连接。 */
    private val outgoing = Channel<SignalingMessage>(Channel.UNLIMITED)

    /** 收尾专用：不随界面/服务协程一起被取消。 */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var scope: CoroutineScope? = null
    private var pumpJob: Job? = null
    private var statsJob: Job? = null

    private var channel: SignalingChannel? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var capturer: ProjectionVideoCapturer? = null
    private var playback: PlaybackAudioCapturer? = null
    private var videoSender: RtpSender? = null

    @Volatile
    private var qualityValue: CaptureSpec.Quality = initialQuality

    @Volatile
    private var frameRateValue: Int = initialFrameRate

    @Volatile
    private var bitrateLimitKbpsValue: Int = initialBitrateKbps

    @Volatile
    private var autoQualityValue: Boolean = true

    @Volatile
    private var lastStats: LinkStats = LinkStats()

    private var lastBytesSent: Long = 0
    private var lastStatsAtMillis: Long = 0

    /** 升降档防抖：连续观测到同一趋势才真正切档，避免来回抖。 */
    private var congestedStreak = 0
    private var healthyStreak = 0

    override val quality: CaptureSpec.Quality get() = qualityValue

    override val frameRate: Int get() = frameRateValue

    override val bitrateLimitKbps: Int get() = bitrateLimitKbpsValue

    override val autoQuality: Boolean get() = autoQualityValue

    override val linkStats: LinkStats get() = lastStats

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
                broadcastQualityState()
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
                screencastMinBitrate = MIN_SCREENCAST_BITRATE
                // 抖动缓冲调小：默认 50 包（约 1 秒）对实时投屏太滞后
                audioJitterBufferMaxPackets = AUDIO_JITTER_BUFFER_PACKETS
                audioJitterBufferFastAccelerate = true
            }
            val created = factory.createPeerConnection(config, observer)
                ?: error("创建 PeerConnection 失败")

            videoSender = created.addTrack(newVideoTrack, listOf(STREAM_ID))
            applyVideoParams()
            created.addTrack(newAudioTrack, listOf(STREAM_ID))

            videoSource = newVideoSource
            videoTrack = newVideoTrack
            audioSource = newAudioSource
            audioTrack = newAudioTrack
            created
        }
        peerConnection = connection

        // ── 4) 采集（虚拟屏 = 屏幕真实尺寸；编码输出 = 当前参数） ──────────────
        startCapture()
        startStatsPolling()

        // ── 5) 发出 offer ─────────────────────────────────────────────────────
        val offer = runtime.onSignaling { connection.awaitOffer() }
        runtime.onSignaling { connection.setLocalAwait(offer) }
        signaling.send(SignalingMessage.Offer(offer.description)).getOrElse { error ->
            throw IllegalStateException("发送会话描述失败：${error.message}")
        }

        // ── 6) 等 answer / 候选 / 接收端的画质请求 ─────────────────────────────
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

                // 接收端只能在上限之内调画质与帧率
                is SignalingMessage.QualityRequest -> {
                    _diagnostics.update {
                        it.copy(state = "接收端请求：${message.quality} / ${message.frameRate}fps")
                    }
                    runCatching {
                        setQuality(CaptureSpec.qualityOf(message.quality))
                        setFrameRate(message.frameRate)
                    }
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
        val (encodeWidth, encodeHeight) = currentEncodeSize()
        val created = ProjectionVideoCapturer(projection, spec.densityDpi)
        val textureHelper = SurfaceTextureHelper.create("mirror-capture", runtime.eglContext)
        runtime.onSignaling {
            created.initialize(textureHelper, context, source.capturerObserver)
            created.startCapture(spec.width, spec.height, frameRateValue)
            // 采集仍是屏幕真实尺寸，编码输出按档位缩放 —— 帧率就是这么换回来的
            source.adaptOutputFormat(encodeWidth, encodeHeight, frameRateValue)
        }
        capturer = created
    }

    // ── 码率上限 / 画质 / 帧率 ─────────────────────────────────────────────────

    private fun currentEncodeSize(): Pair<Int, Int> =
        CaptureSpec.encodeSize(spec.width, spec.height, qualityValue.maxLongEdge)

    /** 当前生效的编码码率：三个约束取最小。 */
    private fun effectiveBitRate(width: Int, height: Int): Int {
        val estimated = CaptureSpec.bitRateFor(width, height, frameRateValue)
        val budget = if (bitrateLimitKbpsValue > 0) bitrateLimitKbpsValue * 1000 else Int.MAX_VALUE
        return minOf(qualityValue.maxBitrate, estimated, budget)
    }

    /**
     * 把当前参数写进编码器。
     *
     * **必须在 signaling 线程调用**（调用点都在 [WebRtcRuntime.onSignaling] 里）。
     */
    private fun applyVideoParams() {
        val sender = videoSender ?: return
        val (width, height) = currentEncodeSize()
        val bitRate = effectiveBitRate(width, height)
        runCatching {
            val params = sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = bitRate
                encoding.maxFramerate = frameRateValue
            }
            sender.setParameters(params)
        }
        reportResolution(width, height, bitRate)
    }

    override suspend fun setQuality(quality: CaptureSpec.Quality): Boolean {
        qualityValue = quality
        val source = videoSource ?: return false
        val (width, height) = currentEncodeSize()
        runtime.onSignaling {
            source.adaptOutputFormat(width, height, frameRateValue)
            applyVideoParams()
        }
        broadcastQualityState()
        return true
    }

    override suspend fun setFrameRate(fps: Int): Boolean {
        frameRateValue = fps.coerceIn(1, CaptureSpec.MAX_FRAME_RATE)
        val source = videoSource ?: return false
        val (width, height) = currentEncodeSize()
        runtime.onSignaling {
            source.adaptOutputFormat(width, height, frameRateValue)
            applyVideoParams()
        }
        broadcastQualityState()
        return true
    }

    /**
     * 设定码率上限（kbps，0 = 自动）。
     *
     * 这是发送端独有的权力：接收端只能在这个预算里选画质与帧率。
     */
    override suspend fun setBitrateLimit(kbps: Int) {
        bitrateLimitKbpsValue = kbps.coerceAtLeast(0)
        runtime.onSignaling { applyVideoParams() }
        broadcastQualityState()
    }

    override suspend fun setAutoQuality(enabled: Boolean) {
        autoQualityValue = enabled
        congestedStreak = 0
        healthyStreak = 0
        _diagnostics.update {
            it.copy(note = if (enabled) "自动画质已开启" else "自动画质已关闭（手动档位：${qualityValue.label}）")
        }
    }

    /** 把当前参数回传给接收端，让它知道预算与生效值。 */
    private fun broadcastQualityState() {
        val target = channel ?: return
        scope?.launch {
            runCatching {
                target.send(
                    SignalingMessage.QualityState(
                        quality = qualityValue.name,
                        frameRate = frameRateValue,
                        bitrateLimitKbps = bitrateLimitKbpsValue,
                    ),
                )
            }
        }
    }

    private fun reportResolution(width: Int, height: Int, bitRate: Int) {
        val budgetLabel = if (bitrateLimitKbpsValue > 0) {
            "上限 ${bitrateLimitKbpsValue / 1000}Mbps"
        } else {
            "上限 自动"
        }
        _diagnostics.update {
            it.copy(
                resolution = "${spec.width}×${spec.height} → ${width}×${height} @${frameRateValue}fps / " +
                    "${bitRate / 1_000_000}.${(bitRate % 1_000_000) / 100_000}Mbps · ${qualityValue.label} · $budgetLabel",
            )
        }
    }

    private fun startStatsPolling() {
        statsJob = scope?.launch {
            var previousFrames = 0L
            while (isActive) {
                delay(STATS_INTERVAL_MILLIS)
                val current = capturer?.capturedFrames ?: 0L
                _diagnostics.update { it.copy(fps = (current - previousFrames).toInt(), frames = current) }
                previousFrames = current
                runCatching { observeLink() }
            }
        }
    }

    /** 读一次 `getStats`，更新链路观测值，并在开启自动画质时决定要不要换档。 */
    private suspend fun observeLink() {
        val report = fetchStats() ?: return

        var bytesSent = 0L
        var fractionLost = 0.0
        var roundTripMs = 0.0
        report.statsMap.values.forEach { stat ->
            when (stat.type) {
                "outbound-rtp" -> {
                    (stat.members["bytesSent"] as? Number)?.let { bytesSent = it.toLong() }
                }

                "remote-inbound-rtp" -> {
                    (stat.members["fractionLost"] as? Number)?.let { fractionLost = it.toDouble() }
                    (stat.members["roundTripTime"] as? Number)?.let { roundTripMs = it.toDouble() * 1000.0 }
                }
            }
        }

        val now = System.currentTimeMillis()
        val kbps = if (lastStatsAtMillis > 0 && now > lastStatsAtMillis) {
            (bytesSent - lastBytesSent).toDouble() * 8.0 / (now - lastStatsAtMillis)
        } else {
            0.0
        }
        lastBytesSent = bytesSent
        lastStatsAtMillis = now

        val stats = LinkStats(lostFraction = fractionLost, roundTripMs = roundTripMs, bitrateKbps = kbps)
        lastStats = stats
        _diagnostics.update {
            it.copy(
                note = "丢包 ${stats.lostPercent} · RTT ${roundTripMs.toInt()}ms · ${kbps.toInt()}kbps" +
                    if (autoQualityValue) " · 自动画质" else "",
            )
        }

        if (autoQualityValue) maybeSwitchQuality(fractionLost, roundTripMs)
    }

    /**
     * 读一次媒体栈统计。
     *
     * 注意层次：`getStats` 必须在 signaling 线程调用，而
     * `suspendCancellableCoroutine` 的 block **不是** suspend lambda，
     * 所以 `onSignaling` 必须在**外层** —— 反过来写会编译失败（踩过）。
     */
    private suspend fun fetchStats(): RTCStatsReport? {
        val connection = peerConnection ?: return null
        return withTimeoutOrNull(STATS_TIMEOUT_MILLIS) {
            runtime.onSignaling {
                suspendCancellableCoroutine { continuation ->
                    connection.getStats(
                        object : RTCStatsCollectorCallback {
                            override fun onStatsDelivered(report: RTCStatsReport?) {
                                if (continuation.isActive) continuation.resume(report)
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * 网络状态 → 画质档位的智能切换。
     *
     * 判据（都来自实测统计，不猜）：
     * - 拥塞：丢包 > 6% 或 RTT > 250ms，**连续两次**才降档（防抖）；
     * - 良好：丢包 < 1% 且 RTT < 120ms，**连续六次**（约 12 秒）才升档；
     * - 从不自动升到「原画」：那个档位最吃算力，自动升上去反而容易卡。
     */
    private suspend fun maybeSwitchQuality(fractionLost: Double, roundTripMs: Double) {
        val tiers = CaptureSpec.Quality.entries
        val index = tiers.indexOf(qualityValue)
        val congested = fractionLost > 0.06 || roundTripMs > 250.0
        val healthy = fractionLost < 0.01 && roundTripMs > 0.0 && roundTripMs < 120.0

        when {
            congested -> {
                congestedStreak += 1
                healthyStreak = 0
                if (congestedStreak >= 2 && index < tiers.size - 1) {
                    congestedStreak = 0
                    val next = tiers[index + 1]
                    setQuality(next)
                    _diagnostics.update { it.copy(state = "网络拥塞，自动降到「${next.label}」") }
                }
            }

            healthy -> {
                healthyStreak += 1
                congestedStreak = 0
                if (healthyStreak >= 6 && index > 1) {
                    healthyStreak = 0
                    val next = tiers[index - 1]
                    setQuality(next)
                    _diagnostics.update { it.copy(state = "网络良好，自动升到「${next.label}」") }
                }
            }

            else -> {
                congestedStreak = 0
                healthyStreak = 0
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
            videoSender = null
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

        /** 音频抖动缓冲包数：默认 50 包（约 1 秒）对实时投屏太滞后。 */
        const val AUDIO_JITTER_BUFFER_PACKETS = 12

        /** 自动重试次数与间隔：局域网里等对端就绪，最多等这么久。 */
        const val MAX_ATTEMPTS = 20
        const val RETRY_DELAY_MILLIS = 2_000L

        /** 链路观测间隔与单次查询超时。 */
        const val STATS_INTERVAL_MILLIS = 2_000L
        const val STATS_TIMEOUT_MILLIS = 2_000L
    }
}

/** 对端主动说 Bye：正常收尾，不是失败。 */
internal class PeerSaidBye : Exception()

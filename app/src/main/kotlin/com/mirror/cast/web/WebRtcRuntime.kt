package com.mirror.cast.web

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * 媒体栈的宿主：EGL 上下文、单线程 Executor、PeerConnectionFactory、音频设备模块。
 *
 * **线程纪律（libwebrtc 的硬要求）**：`PeerConnectionFactory` 与 `PeerConnection`
 * 必须在**同一个 signaling 线程**上创建与使用，否则 native 侧的
 * `RTC_DCHECK_RUN_ON(signaling_thread())` 会直接让进程挂掉。
 * 所以这里把全部媒体栈调用都 post 到同一个单线程 executor，绝不在主线程直接调用。
 *
 * 生命周期：整个进程一个实例（由 Application 持有）—— 工厂与 EGL 重建成本高且容易泄漏。
 */
class WebRtcRuntime(private val context: Context) {

    /**
     * 音频注入的转发点。
     *
     * 音频设备模块在工厂创建时定型（回调也在此刻绑定），而内录源是"每次会话才有"的，
     * 所以用一个可换的转发器：ADM 只建一次，会话开始时把真正的内录挂上来。
     */
    class AudioBridge : JavaAudioDeviceModule.AudioBufferCallback {

        @Volatile
        var delegate: JavaAudioDeviceModule.AudioBufferCallback? = null

        override fun onBuffer(
            buffer: ByteBuffer,
            audioFormat: Int,
            channelCount: Int,
            sampleRate: Int,
            bytesRead: Int,
            captureTimeNs: Long,
        ): Long = delegate?.onBuffer(buffer, audioFormat, channelCount, sampleRate, bytesRead, captureTimeNs)
            ?: captureTimeNs
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mirror-signaling")
    }

    private val signaling = executor.asCoroutineDispatcher()

    private val eglBase: EglBase = EglBase.create()

    private var encoderFactory: DefaultVideoEncoderFactory? = null
    private var decoderFactory: DefaultVideoDecoderFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null

    val audioBridge = AudioBridge()

    /** 给渲染器与采集器共用的 EGL 上下文。 */
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    /**
     * 在 signaling 线程上执行一段媒体栈调用。
     *
     * `block` 本身可以是挂起函数：`createOffer` 这类 API 是回调式的，
     * 必须"在 signaling 线程上挂起等回调"，不能切到别的线程去等。
     */
    suspend fun <T> onSignaling(block: suspend () -> T): T = withContext(signaling) { block() }

    /** 幂等初始化。 */
    suspend fun ensureStarted() = onSignaling {
        if (factory != null) return@onSignaling

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .createInitializationOptions(),
        )

        val audio = JavaAudioDeviceModule.builder(context)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .setAudioBufferCallback(audioBridge)
            .createAudioDeviceModule()
        // 我们只发"系统正在播放的声音"（在回调里覆盖缓冲），
        // 这个静音是兜底：任何覆盖不到的地方都不该漏出环境噪声。
        audio.setMicrophoneMute(true)
        audioDeviceModule = audio

        val encoders = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoders = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        encoderFactory = encoders
        decoderFactory = decoders

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(encoders)
            .setVideoDecoderFactory(decoders)
            .createPeerConnectionFactory()
    }

    /** 取工厂（必须在 signaling 线程上调用，见 [onSignaling]）。 */
    fun requireFactory(): PeerConnectionFactory =
        factory ?: error("WebRtcRuntime 尚未初始化：先调用 ensureStarted()")

    /** 取音频设备模块（切换内录源时用）。 */
    fun audioDevice(): JavaAudioDeviceModule? = audioDeviceModule

    suspend fun shutdown() = onSignaling {
        factory?.dispose()
        factory = null
        encoderFactory = null
        decoderFactory = null
        audioDeviceModule?.release()
        audioDeviceModule = null
        eglBase.release()
    }
}

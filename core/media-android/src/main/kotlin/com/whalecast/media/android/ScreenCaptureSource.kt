package com.whalecast.media.android

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.whalecast.protocol.EncodedFrame
import com.whalecast.protocol.Protocol
import com.whalecast.protocol.VideoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 真实投屏的发送端管线：**MediaProjection 抓屏 → MediaCodec 硬编 H.264 → Annex-B 编码帧**。
 *
 * 零拷贝路径：虚拟屏直接渲染到编码器的输入 Surface，中间不经过任何 Bitmap。
 *
 * 关键约定：编码器抛出 `INFO_OUTPUT_FORMAT_CHANGED` 时先产出一条 [VideoConfig]
 * （SPS/PPS + 分辨率），接收端必须据此配置解码器，因此 **配置先于首个关键帧**。
 */
class ScreenCaptureSource(
    private val projection: MediaProjection,
    private val scope: CoroutineScope,
    val width: Int,
    val height: Int,
    private val densityDpi: Int,
    private val frameRate: Int = DEFAULT_FRAME_RATE,
    private val bitRate: Int = DEFAULT_BIT_RATE,
    private val keyframeIntervalSeconds: Int = DEFAULT_KEYFRAME_INTERVAL_SECONDS,
    private val sessionId: Int = 1,
) {

    private val frameChannel = Channel<EncodedFrame>(capacity = 4)
    private val configChannel = Channel<VideoConfig>(capacity = Channel.UNLIMITED)

    /** 编码后的帧流。消费者来不及取时宁可丢帧（延迟优先），也不阻塞采集。 */
    val encodedFrames: Flow<EncodedFrame> = frameChannel.receiveAsFlow()

    /** 解码器初始化信息。 */
    val configs: Flow<VideoConfig> = configChannel.receiveAsFlow()

    /**
     * 最近一次从编码器拿到的配置。
     *
     * 发送端用它实现"**配置先于首个关键帧**"这条约定：与帧流分开消费时，
     * 两个协程的调度顺序无法保证，因此发送侧在读第一帧前主动取这里的最新值。
     */
    @Volatile
    var latestConfig: VideoConfig? = null
        private set

    private var job: Job? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var frameSeq = 0L

    var encodedFrameCount: Long = 0L
        private set

    var droppedFrameCount: Long = 0L
        private set

    val configuredBitRate: Int get() = bitRate

    /**
     * 投影被外部停止（用户在通知栏点"停止投屏"、系统回收投影）时置位。
     * UI 可据此提示"需要重新授权"。
     */
    @Volatile
    var stopped: Boolean = false
        private set

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopped = true
        }
    }

    fun start() {
        if (job != null) return

        // Android 14+ 强制要求：不先注册回调就 createVirtualDisplay 会抛
        // IllegalStateException("Must register a callback before starting capture")。
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSeconds)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    )
                }
            }
        }

        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        this.codec = codec
        this.inputSurface = surface
        this.virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        job = scope.launch(Dispatchers.Default) { drainEncoder(codec) }
    }

    private suspend fun drainEncoder(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (currentCoroutineContext().isActive) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "编码器状态异常，停止采集", error)
                break
            }

            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    val sps = outputFormat.readBuffer("csd-0")
                    val pps = outputFormat.readBuffer("csd-1")
                    if (sps != null && pps != null) {
                        val config = VideoConfig(width, height, sps, pps)
                        latestConfig = config
                        configChannel.trySend(config)
                    } else {
                        Log.w(TAG, "编码器未提供 csd-0/csd-1，接收端将无法配置解码器")
                    }
                }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer == null) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    buffer.position(bufferInfo.offset)
                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                    val data = ByteArray(bufferInfo.size)
                    buffer.get(data)
                    codec.releaseOutputBuffer(index, false)
                    if (data.isEmpty()) continue

                    val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    frameSeq += 1
                    val ticks = Protocol.ticksFromNanos(bufferInfo.presentationTimeUs * 1_000L)
                    val result = frameChannel.trySend(
                        EncodedFrame(sessionId, frameSeq, ticks, isKeyframe, data),
                    )
                    if (result.isSuccess) {
                        encodedFrameCount += 1
                    } else {
                        droppedFrameCount += 1
                    }
                }
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        runCatching { projection.unregisterCallback(projectionCallback) }
        virtualDisplay?.release()
        virtualDisplay = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release()
        inputSurface = null
        if (!frameChannel.isClosedForSend) frameChannel.close()
        if (!configChannel.isClosedForSend) configChannel.close()
    }

    /** 要求编码器立刻产生一个关键帧（接收端丢包恢复时使用）。 */
    fun requestKeyframe() {
        val codec = this.codec ?: return
        runCatching {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        }
    }

    companion object {
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DEFAULT_FRAME_RATE = 30
        const val DEFAULT_BIT_RATE = 8_000_000
        const val DEFAULT_KEYFRAME_INTERVAL_SECONDS = 2
        private const val VIRTUAL_DISPLAY_NAME = "whalecast-capture"
        private const val TAG = "ScreenCaptureSource"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}

/** 从 MediaFormat 里读出 csd 字节数组。 */
internal fun MediaFormat.readBuffer(key: String): ByteArray? {
    val buffer = getByteBuffer(key) ?: return null
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

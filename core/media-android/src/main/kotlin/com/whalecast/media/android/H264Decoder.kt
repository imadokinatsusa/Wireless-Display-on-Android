package com.whalecast.media.android

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.whalecast.protocol.EncodedFrame
import com.whalecast.protocol.VideoConfig
import java.nio.ByteBuffer

/**
 * 接收端硬解：把 Annex-B 编码帧喂给 MediaCodec，直接渲染到 [Surface]。
 *
 * 必须先 [configure]（拿到发送端的 SPS/PPS），解码器才有可能解出画面 ——
 * 这是"配置包先于首个关键帧"这条协议约定的落地处。
 */
class H264Decoder(private val surface: Surface) {

    private var codec: MediaCodec? = null

    var decodedFrames: Long = 0L
        private set

    var droppedInputs: Long = 0L
        private set

    /** 起播前丢弃的非关键帧数（可用来判断关键帧是否来得太慢）。 */
    var skippedUntilKeyframe: Long = 0L
        private set

    private var sawKeyframe: Boolean = false

    val isConfigured: Boolean get() = codec != null

    fun configure(config: VideoConfig) {
        if (codec != null) return
        val format = MediaFormat.createVideoFormat(
            ScreenCaptureSource.MIME_TYPE,
            config.width,
            config.height,
        ).apply {
            setByteBuffer("csd-0", withStartCode(config.csd0))
            setByteBuffer("csd-1", withStartCode(config.csd1))
        }
        val codec = MediaCodec.createDecoderByType(ScreenCaptureSource.MIME_TYPE)
        codec.configure(format, surface, null, 0)
        codec.start()
        this.codec = codec
        Log.i(TAG, "解码器已配置：${config.width}x${config.height}")
    }

    /** 喂一帧编码数据。返回 false 表示这一帧没进去（解码器忙、未配置，或还在等关键帧）。 */
    fun decode(frame: EncodedFrame): Boolean {
        val codec = this.codec ?: return false

        // 成熟方案（scrcpy 等）都这么做：拿到关键帧之前，非关键帧喂进解码器只会报错或花屏。
        if (!sawKeyframe) {
            if (!frame.isKeyframe) {
                skippedUntilKeyframe += 1
                return false
            }
            sawKeyframe = true
        }
        val index = try {
            codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "解码器状态异常", error)
            return false
        }
        if (index < 0) {
            droppedInputs += 1
            return false
        }
        val buffer = codec.getInputBuffer(index) ?: run {
            droppedInputs += 1
            return false
        }
        buffer.clear()
        buffer.put(frame.data)

        val presentationTimeUs = frame.timestampTicks * 1_000_000L / 90_000L
        codec.queueInputBuffer(index, 0, frame.data.size, presentationTimeUs, 0)

        drainOutput(codec)
        decodedFrames += 1
        return true
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, 0L)
            } catch (error: IllegalStateException) {
                Log.w(TAG, "取解码输出失败", error)
                return
            }
            if (index < 0) return
            // render = true：交给 Surface 显示
            codec.releaseOutputBuffer(index, true)
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun withStartCode(data: ByteArray): ByteBuffer {
        val hasStartCode = data.size >= 4 &&
            data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()
        return if (hasStartCode) {
            ByteBuffer.wrap(data)
        } else {
            // 个别设备给出的 csd 不带起始码，补上以免 configure 失败
            ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + data)
        }
    }

    companion object {
        private const val TAG = "H264Decoder"
        private const val INPUT_TIMEOUT_US = 10_000L
    }
}

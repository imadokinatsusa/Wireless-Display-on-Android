package com.mirror.cast.web

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/**
 * 系统声音内录 → 媒体栈的录音缓冲。
 *
 * **为什么必须这样接**：libwebrtc 的 `JavaAudioDeviceModule` 内部自己 `new AudioRecord`，
 * 而且没有替换录制源的注入口（字节码实证：`WebRtcAudioRecord` 是包私有、Builder 里
 * 唯一相关的是 `setAudioBufferCallback`）。官方留给外部世界的唯一通道就是
 * **录音缓冲回调**：每一批采集到的 PCM 都会先经过 `onBuffer`，我们在这里把
 * `AudioPlaybackCapture` 读到的 PCM 覆盖进去，返回采集时间戳。
 * 这正是 LiveKit 在生产环境用的做法。
 *
 * 节拍由媒体栈自己的 `AudioRecord` 提供（保留默认的录音启用），所以时间戳稳定；
 * 同时 `setMicrophoneMute(true)` 作为兜底：万一某次覆盖不完整，也只会是静音，
 * 不会把环境噪声播到对端。
 *
 * 限制（写进界面，不许静默）：**未允许被捕获的应用会成为静音段** ——
 * 系统的播放捕获策略由被采集方决定，普通 App 无法绕过。
 */
internal class PlaybackAudioCapturer(
    private val projection: MediaProjection,
    private val onNote: (String) -> Unit,
) : JavaAudioDeviceModule.AudioBufferCallback {

    private var record: AudioRecord? = null

    @Volatile
    private var lastError: String? = null

    /** 与媒体栈默认输入一致的格式：16bit PCM / 48kHz / 立体声。 */
    private val sampleRate = SAMPLE_RATE
    private val channelMask = AudioFormat.CHANNEL_IN_STEREO

    /** 启动内录。返回 false 时调用方应降级为"仅画面"并在界面说明。 */
    fun start(): Boolean {
        if (record != null) return true
        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
            val minBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val created = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBytes * 2, sampleRate))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (created.state != AudioRecord.STATE_INITIALIZED) {
                created.release()
                onNote("系统声音内录初始化失败（设备不支持捕获播放音频）")
                return false
            }
            created.startRecording()
            record = created
            true
        } catch (error: Exception) {
            onNote("系统声音内录启动失败：${error::class.java.simpleName}: ${error.message}")
            false
        }
    }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        val source = record ?: return captureTimeNs
        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT || bytesRead <= 0) return captureTimeNs

        val startPosition = buffer.position()
        return try {
            buffer.position(startPosition)
            val read = source.read(buffer, bytesRead, AudioRecord.READ_NON_BLOCKING)
            val filled = if (read > 0) read else 0
            if (filled < bytesRead) {
                // 这一刻内录没有数据（例如正在播放的应用不允许被捕获）：
                // 余下部分填静音，绝不把麦克风里的环境声发出去。
                buffer.position(startPosition + filled)
                repeat(bytesRead - filled) { buffer.put(0) }
            }
            buffer.position(startPosition)
            captureTimeNs
        } catch (error: Exception) {
            if (lastError != error.message) {
                lastError = error.message
                onNote("读取系统声音失败：${error.message}")
            }
            captureTimeNs
        }
    }

    fun stop() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}

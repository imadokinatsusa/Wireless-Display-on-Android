package com.mirror.cast.web

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * 自己实现的屏幕采集器。
 *
 * **为什么不用库自带的 `ScreenCapturerAndroid`**（这是本项目关键的一处取舍）：
 * 它在 `startCapture` 内部自己调 `mediaProjectionManager.getMediaProjection(RESULT_OK, data)`
 * （字节码实证）。而 Android 14 起 **consent token 不可复用** —— 我们为了系统声音内录
 * 必须自己拿一次 `MediaProjection`，那就没有第二次机会给它。两者只能二选一，
 * 所以由我们持有 `MediaProjection`，视频（虚拟屏）与音频（内录）共用同一个实例。
 *
 * 顺带的好处：虚拟屏尺寸与 DPI 由我们决定（库自带实现把 DPI 硬编码成 400、
 * flags 写死 3），而"虚拟屏用屏幕真实尺寸"正是上一轮真机换来的结论。
 *
 * 帧的所有权规则照抄库实现（字节码实证）：`onFrame` 只把帧转交给 `capturerObserver`，
 * **自己绝不 `release()`** —— 控制权在转交那一刻就归对方了，多释放一次就是双重释放。
 */
internal class ProjectionVideoCapturer(
    private val projection: MediaProjection,
    private val densityDpi: Int,
) : VideoCapturer, VideoSink {

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var disposed = false

    @Volatile
    var capturedFrames: Long = 0L
        private set

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        context: Context,
        capturerObserver: CapturerObserver,
    ) {
        helper = surfaceTextureHelper
        observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val textureHelper = helper ?: error("initialize() 必须先调用")
        val capturerObserver = observer ?: error("initialize() 必须先调用")

        textureHelper.setTextureSize(width, height)
        virtualDisplay = projection.createVirtualDisplay(
            DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            Surface(textureHelper.surfaceTexture),
            null,
            null,
        )
        textureHelper.startListening(this)
        capturerObserver.onCapturerStarted(true)
    }

    override fun stopCapture() {
        runCatching { helper?.stopListening() }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        observer?.onCapturerStopped()
    }

    /** M1 不做运行时改规格：尺寸在会话开始时定下就不动。 */
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit

    override fun dispose() {
        disposed = true
    }

    override fun isScreencast(): Boolean = true

    override fun onFrame(frame: VideoFrame) {
        capturedFrames += 1
        if (disposed) return
        observer?.onFrameCaptured(frame)
    }

    private companion object {
        const val DISPLAY_NAME = "mirror-capture"
    }
}

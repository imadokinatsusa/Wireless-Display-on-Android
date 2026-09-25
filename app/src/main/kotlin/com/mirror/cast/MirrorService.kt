package com.mirror.cast

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.mirror.cast.web.SenderSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 投屏期间的前台服务 —— **采集与推流必须活在这里**，不能挂在 Activity 上。
 *
 * 两条硬规矩（都是上一轮用真机换来的）：
 *
 * 1. **顺序不可颠倒**：先 `startForeground(type=mediaProjection)` 成功，**再**取
 *    `MediaProjection`。该要求自 Android 10 / targetSdk ≥ 29 起成立，异常在
 *    `getMediaProjection()` 时就抛（不是等建虚拟屏）。服务内部自己做这件事，
 *    就不存在"跨组件赌时序"的竞争。
 * 2. **失败必须可见**：真机没有 adb，任何异常都要写进 [SessionRegistry] 的状态，
 *    由界面显示出来，不许静默。
 *
 * 另外：`MediaProjection` 只在这里取一次，然后交给会话 —— 视频（虚拟屏）与音频（内录）
 * 共用同一个实例，因为 Android 14 起 consent token 不可复用。
 */
class MirrorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: SenderSession? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val projectionData = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val code = intent.getStringExtra(EXTRA_CODE)
        val width = intent.getIntExtra(EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val dpi = intent.getIntExtra(EXTRA_DPI, 0)
        val qualityName = intent.getStringExtra(EXTRA_QUALITY)

        if (projectionData == null || host == null || port <= 0 || code == null || width <= 0 || height <= 0) {
            reportFailure("启动参数不完整（授权结果 / 对端地址 / 分辨率）")
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            // ★ 第一步：进入前台。失败就直接放弃采集 —— 顺序错了一定崩
            startForegroundWithType()

            val manager = getSystemService(MediaProjectionManager::class.java)
                ?: error("系统没有 MediaProjectionManager")
            val granted = manager.getMediaProjection(resultCode, projectionData)
                ?: error("系统没有返回 MediaProjection")
            // Android 14 起：建虚拟屏之前必须注册回调，否则抛 IllegalStateException
            granted.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        reportFailure("投屏授权被撤销")
                        stopEverything()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            projection = granted

            val app = application as MirrorApplication
            val spec = CaptureSpec.Spec(
                width = width,
                height = height,
                densityDpi = dpi,
                bitRate = CaptureSpec.bitRateFor(width, height),
            )
            val created = SenderSession(
                context = this,
                runtime = app.runtime,
                projection = granted,
                spec = spec,
                host = host,
                signalingPort = port,
                code = code,
                initialQuality = CaptureSpec.qualityOf(qualityName),
            )
            session = created
            SessionRegistry.set(created)
            created.start(scope)
            START_NOT_STICKY
        } catch (error: Exception) {
            reportFailure("${error::class.java.simpleName}: ${error.message}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun startForegroundWithType() {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mirror 正在投屏")
            .setContentText("屏幕与声音正在发送给接收端")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // 失败会抛异常，由调用方转成界面上的失败原因
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "投屏", NotificationManager.IMPORTANCE_LOW).apply {
                description = "投屏进行中的常驻通知"
            },
        )
    }

    private fun reportFailure(reason: String) {
        Log.w(TAG, "投屏失败：$reason")
        SessionRegistry.set(FailedSession(reason))
    }

    private fun stopEverything() {
        // 先摘掉引用再停投影：MediaProjection.stop() 会回调 onStop，
        // 万一回调里再进来一次，看见的已经是 null，不会递归。
        val currentProjection = projection
        projection = null
        session?.let { current -> scope.launch { current.stop() } }
        session = null
        runCatching { currentProjection?.stop() }
        if (SessionRegistry.active.value !is FailedSession) {
            SessionRegistry.clear()
        }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "mirror-cast"
        private const val NOTIFICATION_ID = 0x4D52

        const val ACTION_STOP = "com.mirror.cast.STOP"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_DPI = "dpi"
        private const val EXTRA_QUALITY = "quality"

        /** 启动投屏：界面把屏幕授权结果与对端地址交给服务，其余全在服务里发生。 */
        fun start(
            context: Context,
            resultCode: Int,
            projectionData: Intent,
            host: String,
            signalingPort: Int,
            code: String,
            spec: CaptureSpec.Spec,
            quality: CaptureSpec.Quality,
        ) {
            val intent = Intent(context, MirrorService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, signalingPort)
                putExtra(EXTRA_CODE, code)
                putExtra(EXTRA_WIDTH, spec.width)
                putExtra(EXTRA_HEIGHT, spec.height)
                putExtra(EXTRA_DPI, spec.densityDpi)
                putExtra(EXTRA_QUALITY, quality.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MirrorService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}

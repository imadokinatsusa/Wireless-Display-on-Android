package com.whalecast.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 投屏期间的前台服务。
 *
 * **为什么必须有它**：Android 14（API 34）起，targetSdk ≥ 34 的应用在调用
 * `MediaProjection.createVirtualDisplay()` 之前，必须先运行一个
 * `mediaProjection` 类型的**前台服务**，否则系统直接抛
 * `SecurityException: Media projections require a foreground service of type ...`
 * 把进程干掉 —— 这是投屏类 App 最经典的闪退原因。
 */
class CastForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            ensureChannel()
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WhaleCast 正在投屏")
                .setContentText("屏幕内容正在发送给接收端")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            START_NOT_STICKY
        } catch (error: Exception) {
            // 例如系统限制后台启动前台服务、或授权状态不满足类型要求。
            // 记下来即可，绝不能因为"保活失败"反过来把 App 炸掉。
            Log.w(TAG, "前台服务启动失败：${error.message}", error)
            stopSelf()
            START_NOT_STICKY
        }
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

    companion object {
        private const val TAG = "CastForegroundService"
        private const val CHANNEL_ID = "whalecast-cast"
        private const val NOTIFICATION_ID = 0x5743

        fun start(context: Context) {
            // Android 12+ 对后台启动前台服务有限制：失败了也不能让调用方崩溃
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CastForegroundService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastForegroundService::class.java)) }
        }
    }
}

package com.mirror.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 发送端热点：`LocalOnlyHotspot` —— **不共享外网**的本地热点，专供投屏。
 *
 * 为什么要它：同一 Wi-Fi 是最省事的连法，但很多环境根本走不通
 * （路由器开了 AP/客户端隔离、两台设备在不同频段、现场没有 Wi-Fi）。
 * 这时候由发送端自己开一个热点、接收端连上来，链路就必然互通 ——
 * 上层（发现、信令、媒体）一个字节都不用改，因为大家还是在一个 IP 子网里。
 *
 * 代价：单芯片手机开热点后原 Wi-Fi 会断开、两端都失去外网。所以它是**备用连法**，
 * 不是默认连法。
 */
class HotspotController(private val context: Context) {

    data class HotspotInfo(val ssid: String, val password: String) {
        val displayName: String get() = ssid.removePrefix("\"").removeSuffix("\"")
    }

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var info: HotspotInfo? = null
        private set

    @Volatile
    var failureReason: String? = null
        private set

    val running: Boolean get() = reservation != null

    /**
     * 开热点。回调在主线程执行，`info` 与 `error` 只会有一个非空。
     *
     * 重复调用是安全的：已经在跑就直接把现有信息给回去。
     */
    fun start(onResult: (HotspotInfo?, String?) -> Unit) {
        val existing = info
        if (reservation != null && existing != null) {
            onResult(existing, null)
            return
        }
        failureReason = null

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            onResult(null, "系统没有 WifiManager".also { failureReason = it })
            return
        }

        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(created: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = created
                        val parsed = readConfig(created)
                        info = parsed
                        onResult(parsed, null)
                    }

                    override fun onStopped() {
                        reservation = null
                        info = null
                    }

                    override fun onFailed(reason: Int) {
                        val message = "热点启动失败（代码 $reason）：可能是位置权限未授予，或系统限制了热点"
                        failureReason = message
                        onResult(null, message)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (error: Exception) {
            val message = "${error::class.java.simpleName}: ${error.message}"
            failureReason = message
            onResult(null, message)
        }
    }

    private fun readConfig(created: WifiManager.LocalOnlyHotspotReservation): HotspotInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+：SoftApConfiguration
            val config = created.softApConfiguration
            HotspotInfo(ssid = config.ssid ?: DEFAULT_SSID, password = config.passphrase ?: "")
        } else {
            @Suppress("DEPRECATION")
            val config = created.wifiConfiguration
            @Suppress("DEPRECATION")
            HotspotInfo(ssid = config?.SSID ?: DEFAULT_SSID, password = config?.preSharedKey ?: "")
        }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        info = null
    }

    private companion object {
        const val DEFAULT_SSID = "Mirror"
    }
}

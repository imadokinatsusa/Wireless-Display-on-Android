package com.mirror.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 接收端的本地热点：`LocalOnlyHotspot` —— **不共享外网**，专供投屏。
 *
 * **为什么必须有它**：两台设备连一个共同网络都没有时，要造链路只有两条路 ——
 * Wi-Fi Direct 或热点。Wi-Fi Direct 撞墙了（组能建、信令能通，但**媒体栈拿不到那条
 * P2P 网络**，所以永远停在"连接中"）；热点则是通的：**连上来的那一端是普通 Wi-Fi
 * 客户端**，地址老老实实待在 `ConnectivityManager` 里，媒体栈看得见它。
 *
 * **名字与信道都不用管**：
 * - 信道由系统自己挑（`LocalOnlyHotspot` 的默认行为就是自动选）；
 * - SSID 由系统生成（形如 `AndroidShare_1234`）—— 改名要用的
 *   `SoftApConfiguration.Builder` 是隐藏 API，普通 App 调不到。
 *
 * 但这两件事**都不影响使用**：热点名和密码会被编进二维码，
 * 对方一扫就自动连，**没有人需要认出那个名字**。
 *
 * 全程静默：界面上不会出现"热点""信道""建组"这类字眼。
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

    fun start(onResult: (HotspotInfo?, String?) -> Unit) {
        val existing = info
        if (reservation != null && existing != null) {
            onResult(existing, null)
            return
        }
        failureReason = null

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            val message = "系统没有 WifiManager"
            failureReason = message
            onResult(null, message)
            return
        }

        try {
            @Suppress("DEPRECATION")
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(created: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = created
                        val parsed = readConfig(created)
                        info = parsed
                        failureReason = null
                        onResult(parsed, null)
                    }

                    override fun onStopped() {
                        reservation = null
                        info = null
                    }

                    override fun onFailed(reason: Int) {
                        val message = describeFailure(reason)
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

    private fun describeFailure(reason: Int): String = when (reason) {
        ERROR_NO_CHANNEL -> "没有可用信道，稍后再试"
        ERROR_INCOMPATIBLE_MODE -> "当前不能同时连 Wi-Fi 又开热点，请先断开 Wi-Fi"
        ERROR_TETHERING_DISALLOWED -> "本机策略禁止应用开热点"
        else -> "开热点失败（错误码 $reason）"
    }

    private companion object {
        const val DEFAULT_SSID = "Mirror"

        const val ERROR_NO_CHANNEL = 1
        const val ERROR_INCOMPATIBLE_MODE = 3
        const val ERROR_TETHERING_DISALLOWED = 4
    }
}
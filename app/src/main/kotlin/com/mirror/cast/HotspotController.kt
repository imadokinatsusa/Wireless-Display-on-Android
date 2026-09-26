package com.mirror.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 接收端的本地热点：`LocalOnlyHotspot` —— **不共享外网**，专供投屏。
 *
 * 为什么是它而不是 Wi-Fi Direct：P2P 那条网络**普通 App 拿不到**
 * （`WifiP2pManager.requestNetwork` 是 `@hide`，反射也被系统禁掉），
 * 媒体栈看不见它就永远打不通。热点不一样 —— **连上来的那一端是普通 Wi-Fi 客户端**，
 * 地址老老实实待在 `ConnectivityManager` 里，媒体栈看得见。
 *
 * ⚠️ **频段与信道 App 改不了**：系统默认会落在 2.4G 的 1/6/11 上（最拥挤的地方，
 * 所以实测会跟别的热点互相干扰）。想指定 5GHz 得用 `SoftApConfiguration`，
 * 但那套 API **不在公开 SDK 里**（`Builder`/`BAND_5GHZ` 编译期就找不到符号），
 * 和 Wi-Fi Direct 的 `requestNetwork` 是同一个待遇。
 *
 * 所以真要避开拥挤，出路是**用系统设置里的「便携式热点」**：
 * 那里能选 5GHz 和信道，开好之后本应用会自动认出它的地址（`192.168.43.1` 这类）
 * 并写进二维码，效果完全一样。
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
        ERROR_NO_CHANNEL ->
            "热点启动失败：没有可用信道。稍后再试，或改用系统设置里的便携式热点。"

        ERROR_INCOMPATIBLE_MODE ->
            "热点启动失败：当前模式不兼容 —— 多数手机不能同时连 Wi-Fi 又开热点。" +
                "请先断开 Wi-Fi，再回来开热点。"

        ERROR_TETHERING_DISALLOWED ->
            "热点启动失败：本机策略禁止应用开热点。请到系统设置里手动开便携式热点" +
                "（那里还能顺便选 5GHz，避开拥挤的 2.4G）。"

        else ->
            "热点启动失败（错误码 $reason）。可以改用系统设置里的便携式热点。"
    }

    private companion object {
        const val DEFAULT_SSID = "Mirror"

        const val ERROR_NO_CHANNEL = 1
        const val ERROR_INCOMPATIBLE_MODE = 3
        const val ERROR_TETHERING_DISALLOWED = 4
    }
}
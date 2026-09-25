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
 * **但它经常开不起来**，而且原因几乎总是同一个：
 * 多数手机是单射频，**不能同时"连着 Wi-Fi"又"开热点"**，系统会直接回
 * `ERROR_INCOMPATIBLE_MODE`。所以这里把错误码翻成人话，并给出可执行的出路
 * （先关 Wi-Fi，或改用系统设置里的便携式热点）。
 *
 * 代价：开热点后原 Wi-Fi 会断开、两端都失去外网，所以它是**备用连法**。
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
                        val message = describeFailure(reason)
                        failureReason = message
                        onResult(null, message)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (error: Exception) {
            val message = "${error::class.java.simpleName}: ${error.message} —— 可改用系统设置里的便携式热点"
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

    private fun describeFailure(reason: Int): String = when (reason) {
        ERROR_NO_CHANNEL ->
            "热点启动失败：没有可用信道。稍后再试，或改用系统设置里的便携式热点。"

        ERROR_INCOMPATIBLE_MODE ->
            "热点启动失败：当前模式不兼容 —— 多数手机不能同时连 Wi-Fi 又开热点。" +
                "请先关闭 Wi-Fi，或直接用系统设置里的「便携式热点」再回来投屏。"

        ERROR_TETHERING_DISALLOWED ->
            "热点启动失败：本机策略禁止应用开热点。请到系统设置里手动开启「便携式热点」。"

        else ->
            "热点启动失败（错误码 $reason）。可以改用系统设置里的「便携式热点」再回来投屏。"
    }

    private companion object {
        const val DEFAULT_SSID = "Mirror"

        const val ERROR_NO_CHANNEL = 1
        const val ERROR_INCOMPATIBLE_MODE = 3
        const val ERROR_TETHERING_DISALLOWED = 4
    }
}

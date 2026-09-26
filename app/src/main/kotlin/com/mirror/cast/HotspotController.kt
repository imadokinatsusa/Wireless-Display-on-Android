package com.mirror.cast

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/**
 * 接收端的本地热点：`LocalOnlyHotspot` —— **不共享外网**，专供投屏。
 *
 * 为什么是它而不是 Wi-Fi Direct：P2P 那条网络**普通 App 拿不到**
 * （`WifiP2pManager.requestNetwork` 是 `@hide`，反射也被系统禁掉），
 * 媒体栈看不见它就永远打不通。热点不一样 —— **连上来的那一端是普通 Wi-Fi 客户端**，
 * 地址老老实实待在 `ConnectivityManager` 里，媒体栈看得见。
 *
 * **频段与信道是刻意挑的**：系统默认会落在 2.4G 的 1/6/11 上 —— 那是最拥挤的地方，
 * 既容易被干扰，也容易干扰别人（实测会影响到系统热点本身）。
 * Android 11 起可以指定，于是固定成 **5GHz + 信道 157**：
 * 它在中国可用，又不像 36/40/44/48 那样被路由器默认占着。
 * Android 10 没有这个 API，只能退回系统默认。
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

    /** 实际用上的频段与信道，显示在界面上用。 */
    @Volatile
    var bandLabel: String? = null
        private set

    private val directExecutor = Executor { command -> command.run() }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startWithPreferredConfig(wifi, onResult)
        } else {
            startWithSystemDefault(wifi, null, onResult)
        }
    }

    /** Android 11+：先按"5GHz + 信道 157"申请，被拒再退回系统默认。 */
    private fun startWithPreferredConfig(
        wifi: WifiManager,
        onResult: (HotspotInfo?, String?) -> Unit,
    ) {
        runCatching {
            wifi.startLocalOnlyHotspot(
                preferredConfiguration(),
                directExecutor,
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(created: WifiManager.LocalOnlyHotspotReservation) =
                        accept(created, onResult)

                    override fun onStopped() = clear()

                    override fun onFailed(reason: Int) {
                        // 指定频段不被这台设备接受时，退回系统默认再试一次 ——
                        // 否则主人只会看到"开不起来"，却不知道是被频段偏好卡住的
                        startWithSystemDefault(wifi, reason, onResult)
                    }
                },
            )
        }.onFailure {
            startWithSystemDefault(wifi, null, onResult)
        }
    }

    /** 系统默认配置：没有频段偏好，兼容性最好，但大概率落在 2.4G 的拥挤信道上。 */
    private fun startWithSystemDefault(
        wifi: WifiManager,
        firstReason: Int?,
        onResult: (HotspotInfo?, String?) -> Unit,
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifi.startLocalOnlyHotspot(
                    SoftApConfiguration.Builder().build(),
                    directExecutor,
                    callbackFor(onResult, firstReason),
                )
            } else {
                @Suppress("DEPRECATION")
                wifi.startLocalOnlyHotspot(callbackFor(onResult, firstReason), Handler(Looper.getMainLooper()))
            }
        }.onFailure {
            val message = firstReason?.let { reason -> describeFailure(reason) }
                ?: "${it::class.java.simpleName}: ${it.message}"
            failureReason = message
            onResult(null, message)
        }
    }

    private fun callbackFor(
        onResult: (HotspotInfo?, String?) -> Unit,
        firstReason: Int?,
    ) = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(created: WifiManager.LocalOnlyHotspotReservation) =
            accept(created, onResult)

        override fun onStopped() = clear()

        override fun onFailed(reason: Int) {
            // 连默认配置都失败：优先报第一次（频段偏好）的原因，它更能说明问题
            val message = describeFailure(firstReason ?: reason)
            failureReason = message
            onResult(null, message)
        }
    }

    /**
     * 首选配置：**5GHz + 信道 157**。
     *
     * 密码必须有：一是没有密码的热点在下一次配置时会被系统丢掉，
     * 二是二维码里要带上它，好让对方扫码后自动连上来。
     */
    private fun preferredConfiguration(): SoftApConfiguration {
        val builder = SoftApConfiguration.Builder()
            .setSsid(SSID_PREFIX + (1000..9999).random())
            .setPassphrase(HOTSPOT_PASSPHRASE)
        runCatching {
            builder.setBand(SoftApConfiguration.BAND_5GHZ)
            builder.setChannel(PREFERRED_5G_CHANNEL, SoftApConfiguration.BAND_5GHZ)
        }
        return builder.build()
    }

    private fun accept(
        created: WifiManager.LocalOnlyHotspotReservation,
        onResult: (HotspotInfo?, String?) -> Unit,
    ) {
        reservation = created
        val parsed = readConfig(created)
        info = parsed
        failureReason = null
        onResult(parsed, null)
    }

    private fun clear() {
        reservation = null
        info = null
        bandLabel = null
    }

    private fun readConfig(created: WifiManager.LocalOnlyHotspotReservation): HotspotInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = created.softApConfiguration
            bandLabel = when (config.band) {
                SoftApConfiguration.BAND_5GHZ -> "5GHz 信道 ${config.channel}"
                SoftApConfiguration.BAND_2GHZ -> "2.4GHz 信道 ${config.channel}"
                else -> null
            }
            HotspotInfo(ssid = config.ssid ?: DEFAULT_SSID, password = config.passphrase ?: "")
        } else {
            @Suppress("DEPRECATION")
            val config = created.wifiConfiguration
            @Suppress("DEPRECATION")
            HotspotInfo(ssid = config?.SSID ?: DEFAULT_SSID, password = config?.preSharedKey ?: "")
        }

    fun stop() {
        runCatching { reservation?.close() }
        clear()
    }

    private fun describeFailure(reason: Int): String = when (reason) {
        ERROR_NO_CHANNEL ->
            "热点启动失败：没有可用信道。稍后再试，或改用系统设置里的便携式热点。"

        ERROR_INCOMPATIBLE_MODE ->
            "热点启动失败：当前模式不兼容 —— 多数手机不能同时连 Wi-Fi 又开热点。" +
                "请先断开 Wi-Fi，再回来开热点。"

        ERROR_TETHERING_DISALLOWED ->
            "热点启动失败：本机策略禁止应用开热点。请到系统设置里手动开便携式热点。"

        else ->
            "热点启动失败（错误码 $reason）。可以改用系统设置里的便携式热点。"
    }

    private companion object {
        const val DEFAULT_SSID = "Mirror"

        const val SSID_PREFIX = "Mirror-"

        const val HOTSPOT_PASSPHRASE = "mirror-cast"

        /** 5GHz 里相对清静、且中国可用的信道。 */
        const val PREFERRED_5G_CHANNEL = 157

        const val ERROR_NO_CHANNEL = 1
        const val ERROR_INCOMPATIBLE_MODE = 3
        const val ERROR_TETHERING_DISALLOWED = 4
    }
}
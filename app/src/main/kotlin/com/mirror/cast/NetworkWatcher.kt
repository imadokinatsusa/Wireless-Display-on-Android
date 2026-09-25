package com.mirror.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 本机网络地址。
 *
 * 三个用途：
 * - **诊断**：热点下两台设备到底互相看得到什么地址，只能靠它写出来；
 * - **兜底**：广播不通时，接收端把 `IP:端口` 亮出来，发送端手输也能连；
 * - **排障**：开热点那台是否真的拿到了 `192.168.43.1` 这类 softap 地址。
 */
object LocalAddress {

    /** 取本机主要局域网 IPv4。 */
    fun ipv4(): String? = all().firstOrNull()

    /**
     * 列出所有已启用的 IPv4 地址。
     *
     * 刻意不用 `ConnectivityManager`：**softap 接口通常不在它的网络列表里**，
     * 而那正是热点模式下唯一可用的地址 —— 拿不到它就等于没有候选。
     */
    fun all(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrNull().orEmpty()

    /** 一行诊断文字，例如 `192.168.43.1`，多个时用逗号分隔。 */
    fun summary(): String = all().joinToString(",").ifEmpty { "无网络地址" }
}

/**
 * 网络接口变化观察者。
 *
 * **为什么必须有它**：开/关热点、切换 Wi-Fi、断网重连都会换掉网络接口，
 * 而发现用的 UDP socket 已经绑在旧接口上 —— 表现就是"热点明明开了，
 * 却一台设备都搜不到"，或者反过来"搜到了却连不上"。
 * 网络一变就重启发现/广播（重新绑定），这类问题自然消失。
 *
 * 自带节流：`onCapabilitiesChanged` 会连续触发多次，不节流的话会把
 * 发现反复重启、反而丢包。
 */
class NetworkWatcher(
    private val context: Context,
    private val onChanged: () -> Unit,
) {

    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private var registered = false

    @Volatile
    private var lastNotifiedAt: Long = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = notifyChanged()

        override fun onLost(network: Network) = notifyChanged()

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) = notifyChanged()
    }

    private fun notifyChanged() {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAt < THROTTLE_MILLIS) return
        lastNotifiedAt = now
        onChanged()
    }

    fun start() {
        if (registered) return
        val manager = connectivityManager ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        val manager = connectivityManager ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
    }

    private companion object {
        /** 1.5 秒内只通知一次：网络事件常常成串到来。 */
        const val THROTTLE_MILLIS = 1_500L
    }
}

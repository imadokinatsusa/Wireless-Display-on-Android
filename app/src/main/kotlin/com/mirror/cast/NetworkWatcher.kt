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

    /**
     * 取本机主要局域网 IPv4 —— **按"对端最可能连得上"排序后取第一个**。
     *
     * 为什么不直接取第一个：`NetworkInterface` 的枚举顺序**不保证**。
     * 带 VPN、蜂窝、Wi-Fi Direct 接口的设备上，排在最前面的往往不是对端能到达的那个，
     * 于是二维码里就写进一个连不上的地址 —— 表现正是"连接对端失败"（踩过）。
     */
    fun ipv4(): String? = all().maxByOrNull { reachability(it) }

    /**
     * 给地址打一个"对端有多可能连上"的分。
     *
     * 依据是各网段常见的身份：
     * - `192.168.x.x`：家用/随身 Wi-Fi 与热点的标准段，最可能通；
     * - `172.x`、`10.x`：企业网、VPN、蜂窝内网 —— 有时通，有时只是本地可达；
     * - `192.168.49.x`：**Wi-Fi Direct 群主**的固定段，除非对方已经加入这个组，
     *   否则连不上，所以排在最后。
     */
    private fun reachability(address: String): Int = when {
        address.startsWith(P2P_GROUP_PREFIX) -> 0
        address.startsWith("192.168.") -> 4
        address.startsWith("172.") -> 2
        address.startsWith("10.") -> 1
        else -> 1
    }

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

    /**
     * 局域网接口上的 IPv4 地址 —— 排掉蜂窝、排掉 Wi-Fi Direct 群主网段。
     *
     * 为什么要**按接口名**排除蜂窝：关掉 Wi-Fi 之后设备往往还挂着流量，
     * 运营商内网的 `10.x` 地址照样存在。只按网段判断的话，蜂窝会被当成"有局域网"，
     * 于是该建组的时候死活不建（踩过）。
     */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .filterNot { nic ->
                val name = nic.name.lowercase()
                CELLULAR_INTERFACE_HINTS.any { hint -> name.startsWith(hint) }
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .filterNot { it.startsWith(P2P_GROUP_PREFIX) }
            .distinct()
    }.getOrNull().orEmpty()

    /**
     * 现在有没有**可用于局域网直连**的地址。
     *
     * ⚠️ 判断只能看"接口上有没有地址"，**不能看 `ConnectivityManager` 的活动网络** ——
     * Wi-Fi Direct 的组同样报 `TRANSPORT_WIFI`，建完组会被判成"有局域网了"、
     * 然后立刻把自己拆掉，表现就是**二维码闪一下就不见了 / Wi-Fi Direct 用不了**（踩过）。
     *
     * 蜂窝[接口](CELLULAR_INTERFACE_HINTS)不算，P2P 群主网段也不算；
     * 而热点（`softap0` 之类）**算** —— 那正是接收端开热点时合法的局域网地址。
     */
    fun hasLan(): Boolean = lanAddresses().isNotEmpty()

    /** Wi-Fi Direct 群主的固定网段。 */
    private const val P2P_GROUP_PREFIX = "192.168.49."

    /**
     * 蜂窝数据接口的常见前缀。
     *
     * 这些接口上的地址（多为 `10.x`）对局域网直连毫无意义 —— 对端根本到不了，
     * 但它们的网段看起来又很像内网，所以必须按接口名排除，不能只看地址。
     */
    private val CELLULAR_INTERFACE_HINTS =
        listOf("rmnet", "ccmni", "pdp", "wwan", "seth", "clat", "v4-rmnet")
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

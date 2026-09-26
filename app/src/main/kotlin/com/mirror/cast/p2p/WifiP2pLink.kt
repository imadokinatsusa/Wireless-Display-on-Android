package com.mirror.cast.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Wi-Fi Direct 链路的当前状态。 */
data class P2pStatus(
    /** 系统的 Wi-Fi Direct 能力是否可用。 */
    val available: Boolean = false,
    /** 正在搜索附近设备。 */
    val searching: Boolean = false,
    /** 已经搜到、可以连的设备名。 */
    val peers: List<String> = emptyList(),
    /**
     * 链路建好后对端的地址。
     *
     * Group Owner 固定是 `192.168.49.1`，客户端由群主内置的 DHCP 分配 ——
     * 拿到这个地址之后，上层就完全是普通的 IP 通信了。
     */
    val groupOwnerAddress: String? = null,
    /** 本机是不是这个 P2P 组的群主（也就是"临时小 AP"那一台）。 */
    val isGroupOwner: Boolean = false,
    /**
     * 本进程的网络有没有成功绑到这条 P2P 链路上。
     *
     * **这一点决定了媒体能不能通**：Android 上 Wi-Fi Direct 建出来的网络
     * 默认不属于任何 App，媒体栈枚举网络时看不到它就收集不到候选地址。
     */
    val boundToGroup: Boolean = false,
    /** 给人看的一句话状态或失败原因（真机没有 adb，这是唯一的出口）。 */
    val message: String? = null,
)

/**
 * Wi-Fi Direct（Wi-Fi 直连 / P2P）链路。
 *
 * **为什么必须有它**：`LocalOnlyHotspot` 那条路有两个治不好的结构性问题 ——
 * ①"谁当网关"是写死的，必须用户跑去系统设置里手动连 Wi-Fi 并输密码；
 * ②**AP 侧的 softap 接口不会被注册成 `ConnectivityManager` 的网络**，
 * 于是媒体栈自己枚举网络时根本看不到这个地址，候选里就没有它。
 *
 * Wi-Fi Direct 把这两件事都交给了系统：
 * - 双方通过 **Group Owner Negotiation** 协商谁当群主，用户点一次系统弹窗即可；
 * - 群主会内置一个 DHCP，给客户端分配 `192.168.49.x` 的地址，
 *   **整条链路由系统当作一个正常的网络来管**；
 * - 全程不需要路由器、不需要互联网、不需要用户去设置里连任何东西。
 *
 * 链路建好之后，上层（扫码、信令、媒体）一个字节都不用改 —— 因为对它们来说，
 * 这跟"恰好连在同一个 Wi-Fi 上"没有区别。
 */
class WifiP2pLink(private val context: Context) {

    private val manager: WifiP2pManager? =
        context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null

    private var registered = false

    /** 搜到的设备本体（不只是名字）—— 连接时要用它的 `deviceAddress`。 */
    private var peerDevices: List<WifiP2pDevice> = emptyList()

    private val _status = MutableStateFlow(P2pStatus())
    val status: StateFlow<P2pStatus> = _status.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receivingContext: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _status.update {
                        it.copy(
                            available = enabled,
                            message = if (enabled) {
                                it.message ?: "Wi-Fi Direct 可用"
                            } else {
                                "系统的 Wi-Fi Direct 没有打开"
                            },
                        )
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    /** 注册广播并初始化通道；重复调用安全。 */
    fun start() {
        val wifiP2p = manager
        if (wifiP2p == null) {
            _status.update { it.copy(available = false, message = "这台设备没有 Wi-Fi Direct 服务") }
            return
        }
        if (channel == null) {
            channel = wifiP2p.initialize(context, Looper.getMainLooper(), null)
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }
    }

    /**
     * 接收端：直接建组，自己当群主。
     *
     * 群主不需要先"搜索"——这正是它的好处：接收端只要把组建起来守在那儿，
     * 发送端发现并加入就行。
     */
    fun createGroup() {
        val wifiP2p = manager ?: return
        val current = channel ?: return
        if (!wifiRadioReady()) return
        _status.update { it.copy(message = "正在建组…") }
        runCatching { wifiP2p.createGroup(current, actionListener("建组")) }
            .onFailure { _status.update { status -> status.copy(message = "建组失败：${it.message}") } }
    }

    /** 发送端：搜索附近的 Wi-Fi Direct 设备。 */
    fun discover() {
        val wifiP2p = manager ?: return
        val current = channel ?: return
        if (!wifiRadioReady()) return
        _status.update { it.copy(searching = true, message = "正在搜索附近设备…") }
        runCatching { wifiP2p.discoverPeers(current, actionListener("搜索")) }
            .onFailure {
                _status.update { status -> status.copy(searching = false, message = "搜索失败：${it.message}") }
            }
    }

    /**
     * Wi-Fi 射频必须是打开的。
     *
     * 这是最容易踩的一脚：**Wi-Fi Direct 不需要连上任何网络，但 Wi-Fi 开关必须开着** ——
     * 它用的是 Wi-Fi 射频本身。为了"离线投屏"特地把 Wi-Fi 关掉的设备，
     * 建组会直接返回 `ERROR`，而系统给的原因就只有"内部错误"四个字。
     */
    private fun wifiRadioReady(): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled == true) return true
        _status.update {
            it.copy(message = "Wi-Fi 开关没打开。Wi-Fi Direct 不用连任何网络，但射频必须开着 —— 请先打开 Wi-Fi")
        }
        return false
    }

    /**
     * 发送端：加入对方的组。
     *
     * [peerName] 为空时取搜到的第一台 —— 二维码里已经带了对方的名字，
     * 所以正常路径下总是精确匹配。
     *
     * WPS 用 PBC（按键确认）：对端不需要知道任何密码，系统弹一次确认框就够了。
     */
    fun connect(peerName: String? = null) {
        val wifiP2p = manager ?: return
        val current = channel ?: return
        // 精确匹配优先；名字对不上（厂商改过 deviceName）时退回第一台 ——
        // 这个场景里附近通常就只有对面那一台
        val target = peerDevices.firstOrNull { peerName == null || it.deviceName == peerName }
            ?: peerDevices.firstOrNull()
        if (target == null) {
            _status.update { it.copy(message = "还没搜到设备，先点「搜索」") }
            return
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = target.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        _status.update { it.copy(message = "正在连接「${target.deviceName}」…") }
        runCatching { wifiP2p.connect(current, config, actionListener("连接")) }
            .onFailure { _status.update { status -> status.copy(message = "连接失败：${it.message}") } }
    }

    /**
     * 把本进程的网络**绑定到这条 Wi-Fi Direct 链路**上。
     *
     * **为什么非做不可**：Android 上 Wi-Fi Direct 建出来的网络**默认不属于任何 App** ——
     * 系统把它建好了，但要让它被某个 App 使用，得由 App 主动申请。
     *
     * 我们自己的信令 Socket 靠"同网段直连路由"就能通，所以从信令上看一切正常；
     * 可**媒体栈（WebRTC）是靠枚举 `ConnectivityManager` 的网络来收集候选地址的** ——
     * 看不到这条链路，候选里就没有 `192.168.49.x`，两端于是永远停在"连接中"（踩过）。
     *
     * 绑定之后 WebRTC 才能看见并使用这条链路。返回是否绑定成功，界面据此显示进展。
     */
    fun bindToGroup(): Boolean = bindByScanning()

    /** 兜底路子：在 `allNetworks` 里翻找那条 P2P 网络。 */
    private fun bindByScanning(): Boolean {
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return false
        val groupOwnerNow = _status.value.groupOwnerAddress
        val target = manager.allNetworks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return@firstOrNull false
            }
            val link = manager.getLinkProperties(network) ?: return@firstOrNull false
            // 优先认接口名（p2p0 / p2p-wlan0-0），认不出来就靠群主地址对
            link.interfaceName?.startsWith("p2p") == true ||
                (groupOwnerNow != null &&
                    link.linkAddresses.any { it.address.hostAddress == groupOwnerNow })
        } ?: return false

        val bound = runCatching { manager.bindProcessToNetwork(target) }.getOrDefault(false)
        _status.update { it.copy(boundToGroup = bound) }
        return bound
    }

    /** 解绑，把流量还给系统默认网络。 */
    fun unbind() {
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { manager.bindProcessToNetwork(null) }
        _status.update { it.copy(boundToGroup = false) }
    }

    /**
     * 清掉可能残留的 P2P 组 —— 进程被强杀时留下的那种。
     *
     * 为什么要它：Wi-Fi Direct 的组是**系统级、跨进程**的。App 被划掉或被系统清理时，
     * `onDispose` 根本没机会跑，那个组就可能留在系统里**继续占着 Wi-Fi 射频** ——
     * 表现就是网速变慢、别的投屏软件连不上。所以每次启动先主动收拾一次。
     */
    fun cleanupStaleGroup() {
        val wifiP2p = manager ?: return
        val current = channel
            ?: runCatching { wifiP2p.initialize(context, Looper.getMainLooper(), null) }
                .getOrNull()
                ?.also { channel = it }
            ?: return
        runCatching { wifiP2p.removeGroup(current, null) }
    }

    /** 拆组并注销广播。 */
    fun stop() {
        // 先解绑，再拆组 —— 顺序反了的话，拆完组那个 Network 就找不到了
        unbind()
        val wifiP2p = manager
        val current = channel
        if (wifiP2p != null && current != null) {
            runCatching { wifiP2p.removeGroup(current, null) }
        }
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        peerDevices = emptyList()
        _status.value = P2pStatus()
    }

    private fun requestPeers() {
        val wifiP2p = manager ?: return
        val current = channel ?: return
        runCatching {
            wifiP2p.requestPeers(current) { list ->
                // getDeviceList() 返回的是 Collection（不是 List），这里统一收成 List
                peerDevices = list.deviceList.toList()
                _status.update {
                    it.copy(
                        peers = peerDevices.map { device -> device.deviceName },
                        searching = false,
                    )
                }
            }
        }
    }

    private fun requestConnectionInfo() {
        val wifiP2p = manager ?: return
        val current = channel ?: return
        runCatching {
            wifiP2p.requestConnectionInfo(current) { info ->
                if (info.groupFormed) {
                    val address = info.groupOwnerAddress?.hostAddress
                    _status.update {
                        it.copy(
                            groupOwnerAddress = address,
                            isGroupOwner = info.isGroupOwner,
                            searching = false,
                        )
                    }
                    // 链路一成就把它接管给本进程 —— 少这一步，媒体栈看不见这条链路：
                    // 信令能通（自己写的 Socket 走直连路由），画面却永远起不来。
                    // 结果是异步回来的，所以这里不拼"已接管"，让绑定结果自己写状态。
                    bindToGroup()
                    _status.update {
                        it.copy(
                            message = if (it.boundToGroup) {
                                "已建组（$address）· 已接管链路"
                            } else {
                                (if (info.isGroupOwner) "已建组，本机是群主（$address）" else "已加入对方的组（群主 $address）") +
                                    " · 正在申请链路…"
                            },
                        )
                    }
                } else {
                    _status.update {
                        it.copy(groupOwnerAddress = null, isGroupOwner = false)
                    }
                }
            }
        }
    }

    private fun actionListener(what: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            _status.update { it.copy(message = "$what 已发出") }
        }

        override fun onFailure(reason: Int) {
            _status.update {
                it.copy(searching = false, message = "$what 失败：${describe(reason)}")
            }
        }
    }

    private fun describe(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "这台设备不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "系统正忙，稍后再试"
        WifiP2pManager.ERROR ->
            "系统报错。先确认 Wi-Fi 开关是打开的（不用连任何网络，但射频必须开着）；" +
                "另外 Android 13 起还需要授予「附近的设备」权限"
        else -> "错误码 $reason"
    }
}

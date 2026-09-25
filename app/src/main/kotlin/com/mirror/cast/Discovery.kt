package com.mirror.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.mirror.cast.discovery.Beacon
import com.mirror.cast.discovery.BeaconBroadcaster
import com.mirror.cast.discovery.BeaconScanner
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.discovery.DiscoveredDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 发送端的发现：拿 Wi-Fi 组播锁 → 扫描广播 → 定期清理过期设备。
 *
 * 组播锁是必需的第一件事：Wi-Fi 芯片默认丢弃广播/组播包，
 * 不持锁时"接收端广播了、发送端一台都搜不到"——这是局域网发现类功能的头号真机坑。
 */
class Discovery(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val scanner = BeaconScanner(scope)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pruner: Job? = null

    val devices: StateFlow<Map<String, DiscoveredDevice>> get() = scanner.devices

    /** 发现失败的原因（端口被占、没 Wi-Fi…）。发现是尽力而为，不能因此崩溃。 */
    var failureReason: String? = null
        private set

    fun start() {
        multicastLock = acquireMulticastLock(context)
        scanner.start()
        pruner = scope.launch {
            while (isActive) {
                // 对端关掉接收端后，列表不能一直留着可点的僵尸设备
                scanner.pruneOlderThan()
                delay(1_000)
            }
        }
        failureReason = null
    }

    /** 按连接码等待设备出现；找不到返回 null（界面上提示"没搜到，请确认连接码"）。 */
    suspend fun resolve(code: String): DiscoveredDevice? = scanner.resolve(code)

    fun stop() {
        pruner?.cancel()
        pruner = null
        scanner.stop()
        failureReason = scanner.failureReason
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}

/**
 * 接收端的广播：周期性把连接码与信令端口播到局域网。
 *
 * 广播失败不影响"别人手输 IP 也能连"，所以这里的失败只上报不抛异常。
 */
class Broadcaster(
    private val scope: CoroutineScope,
    private val code: String,
    /** 端口在监听就绪后才有值，所以用取值函数而不是固定值。 */
    private val portProvider: () -> Int,
    private val deviceName: String,
) {

    private val inner = BeaconBroadcaster(
        scope = scope,
        beaconProvider = {
            Beacon(
                code = code,
                // 端口尚未就绪时不广播非法端口（Beacon 会校验 1..65535）
                tcpPort = portProvider().coerceAtLeast(1),
                deviceName = deviceName,
                token = "",
            )
        },
    )

    val failureReason: String? get() = inner.failureReason

    fun start() = inner.start()

    fun stop() = inner.stop()

    companion object {
        /** 6 位短连接码：去掉易混字符，方便主人抬头念给另一台设备。 */
        fun newCode(): String = ConnectCode.random()
    }
}

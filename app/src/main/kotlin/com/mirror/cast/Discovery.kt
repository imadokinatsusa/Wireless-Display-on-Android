package com.mirror.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.mirror.cast.discovery.Beacon
import com.mirror.cast.discovery.BeaconBroadcaster
import com.mirror.cast.discovery.BeaconScanner
import com.mirror.cast.discovery.ConnectCode
import com.mirror.cast.discovery.DiscoveredDevice
import com.mirror.cast.discovery.ProbeProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 发送端的发现。**两条路并行，谁通了都算通**：
 *
 * 1. **UDP 广播**：接收端周期性喊话，我们听着（原来那套，一行没动）；
 * 2. **扫端口**：我们主动去敲每个地址的那个固定端口，谁应答谁就是接收端。
 *
 * 为什么要两条：广播是"喊"，对方可能听不见 —— 路由器开 AP 隔离、热点过滤组播、
 * 换网卡后 socket 还绑在旧接口上；而扫端口是"敲门"，能敲开就是能敲开。
 * 反过来，扫端口要花几秒、还可能被防火墙挡，广播却几乎瞬时。
 * 所以**谁都不替代谁**，结果取并集。
 *
 * 组播锁仍然是必需的第一件事：Wi-Fi 芯片默认丢弃广播/组播包，
 * 不持锁时"接收端喊了、发送端一台都听不到"——这是局域网发现类功能的头号真机坑。
 */
class Discovery(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val scanner = BeaconScanner(scope)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pruner: Job? = null
    private var probeJob: Job? = null

    /** 扫端口敲开的那些设备。 */
    private val probed = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())

    /**
     * 两条路的并集：广播听见的 + 敲门敲开的。
     *
     * 同一台设备被两条路都报到时，后写的那份覆盖前一份 —— 无所谓，信息是一样的。
     */
    val devices: StateFlow<Map<String, DiscoveredDevice>> =
        combine(scanner.devices, probed) { broadcast, scanned -> broadcast + scanned }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** 发现失败的原因（端口被占、没 Wi-Fi…）。发现是尽力而为，不能因此崩溃。 */
    var failureReason: String? = null
        private set

    /** 最近一轮扫描的目标。扫不到时必须能一眼看出"到底扫的是哪儿"。 */
    @Volatile
    var lastScanTarget: String? = null
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
        startProbing()
        failureReason = null
    }

    /**
     * 扫端口找设备。
     *
     * ⚠️ **已经有设备了就不再扫** —— 一轮扫描是几百个 TCP 连接，一直扫会把链路打满，
     * 投屏画面会直接卡顿（这个坑踩过）。而投屏期间根本不需要继续找设备，
     * 所以这里只在列表为空、也就是"还在等人"的时候才动手。
     */
    private fun startProbing() {
        if (probeJob != null) return
        probeJob = scope.launch {
            while (isActive) {
                if (devices.value.isEmpty()) {
                    val prefixes = ProbeScanner.prefixesFor(LocalAddress.all())
                    lastScanTarget = prefixes
                        .joinToString(" ") { prefix -> "${prefix}*:${ProbeProtocol.PORT}" }
                        .ifEmpty { null }
                    if (prefixes.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        probed.value = ProbeScanner.scan(prefixes).associate { (host, reply) ->
                            host to DiscoveredDevice(
                                beacon = Beacon(
                                    code = reply.code,
                                    tcpPort = reply.signalingPort,
                                    deviceName = reply.deviceName,
                                    token = "",
                                ),
                                host = host,
                                lastSeenMillis = now,
                            )
                        }
                    }
                }
                delay(PROBE_INTERVAL_MILLIS)
            }
        }
    }

    /** 按连接码等待设备出现；找不到返回 null（界面上提示"没搜到，请确认连接码"）。 */
    suspend fun resolve(code: String): DiscoveredDevice? = scanner.resolve(code)

    fun stop() {
        pruner?.cancel()
        pruner = null
        probeJob?.cancel()
        probeJob = null
        probed.value = emptyMap()
        scanner.stop()
        failureReason = scanner.failureReason
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private companion object {
        /**
         * 空手时的扫描间隔。
         *
         * 找到设备后就不再扫了，所以这个间隔只决定"等待中的那几秒"有多快出结果；
         * 4 秒是给链路的喘息时间 —— 扫描本身很吵，没必要更密。
         */
        const val PROBE_INTERVAL_MILLIS = 4_000L
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

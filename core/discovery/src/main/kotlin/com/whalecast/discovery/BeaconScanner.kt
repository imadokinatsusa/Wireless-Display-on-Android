package com.whalecast.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** 扫描到的接收端。 */
data class DiscoveredDevice(
    val beacon: Beacon,
    /** 广播来源 IP（即接收端的局域网地址）。 */
    val host: String,
    val lastSeenMillis: Long,
) {
    val endpoint: String get() = "$host:${beacon.tcpPort}"
}

/**
 * 发送端：监听局域网广播，把收到的接收端按连接码记下来。
 *
 * 于是用户只需输入 6 位码（或扫码），本地一匹配就得到 `IP:端口`，直接连 TCP。
 */
class BeaconScanner(
    private val scope: CoroutineScope,
    port: Int = Beacon.UDP_PORT,
) {
    private val requestedPort: Int = port

    /**
     * 实际绑定的端口。传 0 时由系统分配。
     *
     * 测试与"默认端口被占"的场景都依赖它 —— 自己猜一个"空闲端口"的写法
     * 在受限网络环境下会拿到 `-1`，进而抛出 `port out of range:-1`。
     */
    @Volatile
    var localPort: Int = -1
        private set

    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())

    /** 连接码 → 设备。UI 可以直接列出来给用户看。 */
    val devices: StateFlow<Map<String, DiscoveredDevice>> = _devices.asStateFlow()

    private var job: Job? = null
    private var socket: DatagramSocket? = null

    var running: Boolean = false
        private set

    /** 发现失败的原因（端口被占、网络不可用…）。发现是尽力而为，不能因此崩溃。 */
    var failureReason: String? = null
        private set

    fun start() {
        if (job != null) return
        running = true
        job = scope.launch(Dispatchers.IO) {
            var localSocket: DatagramSocket? = null
            try {
                localSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(port))
                }
                socket = localSocket
                localPort = localSocket.localPort
                val buffer = ByteArray(512)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    localSocket.receive(packet)
                    val beacon = Beacon.decode(packet.data.copyOf(packet.length)) ?: continue
                    val host = packet.address?.hostAddress ?: continue
                    _devices.update { current ->
                        current + (beacon.code to DiscoveredDevice(beacon, host, System.currentTimeMillis()))
                    }
                }
            } catch (error: Exception) {
                // 端口被占、网络不可用、socket 被 stop() 关闭……
                // 发现是"尽力而为"的能力：任何失败都不该让 App 崩溃。
                failureReason = error.message ?: error::class.java.simpleName
            } finally {
                runCatching { localSocket?.close() }
            }
        }
    }

    /** 等待监听真正就绪（端口绑定完成）。传 0 端口时尤其需要它。 */
    suspend fun awaitReady(timeoutMillis: Long = 3_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (localPort > 0) return true
            if (failureReason != null) return false
            delay(50)
        }
        return localPort > 0
    }

    /** 按连接码等待设备出现（广播有周期，需要给一点时间）。 */
    suspend fun resolve(code: String, timeoutMillis: Long = 8_000): DiscoveredDevice? {
        val normalized = ConnectCode.normalize(code)
        if (!ConnectCode.isValid(normalized)) return null
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            _devices.value[normalized]?.let { return it }
            delay(200)
        }
        return null
    }

    /** 丢掉太久没再出现的设备（对方关掉接收端后列表不会一直留着）。 */
    fun pruneOlderThan(maxAgeMillis: Long = 5_000) {
        val now = System.currentTimeMillis()
        _devices.update { current -> current.filterValues { now - it.lastSeenMillis <= maxAgeMillis } }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }
}

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
    private val port: Int = Beacon.UDP_PORT,
) {

    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())

    /** 连接码 → 设备。UI 可以直接列出来给用户看。 */
    val devices: StateFlow<Map<String, DiscoveredDevice>> = _devices.asStateFlow()

    private var job: Job? = null
    private var socket: DatagramSocket? = null

    var running: Boolean = false
        private set

    fun start() {
        if (job != null) return
        running = true
        job = scope.launch(Dispatchers.IO) {
            val localSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(port))
            }
            socket = localSocket
            val buffer = ByteArray(512)
            try {
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    localSocket.receive(packet)
                    val beacon = Beacon.decode(packet.data.copyOf(packet.length)) ?: continue
                    val host = packet.address?.hostAddress ?: continue
                    _devices.update { current ->
                        current + (beacon.code to DiscoveredDevice(beacon, host, System.currentTimeMillis()))
                    }
                }
            } catch (_: IOException) {
                // socket 被 stop() 关闭，或网络不可用：静默结束，UI 会提示"没发现设备"
            } finally {
                runCatching { localSocket.close() }
            }
        }
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

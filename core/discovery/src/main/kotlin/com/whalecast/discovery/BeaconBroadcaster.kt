package com.whalecast.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 接收端：周期性把 [Beacon] 广播到局域网，让发送端能"按连接码找到我"。
 *
 * 这是**尽力而为**的发现通道：网络禁广播（AP 隔离、企业 Wi-Fi）时它静默失败，
 * 手动输入 IP 依然可用，所以这里不把异常抛给用户。
 *
 * [targets] 可注入，测试里换成 `127.0.0.1` 就能在 CI 上验证整条链路。
 */
class BeaconBroadcaster(
    private val scope: CoroutineScope,
    private val beaconProvider: () -> Beacon,
    private val port: Int = Beacon.UDP_PORT,
    private val intervalMillis: Long = 1_000,
    private val targets: List<String> = DEFAULT_TARGETS,
) {

    private var job: Job? = null
    private var socket: DatagramSocket? = null

    var sentCount: Long = 0L
        private set

    /** 广播失败的原因；广播失败不影响投屏本身，但要能上报给 UI。 */
    var failureReason: String? = null
        private set

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            var localSocket: DatagramSocket? = null
            try {
                localSocket = DatagramSocket().apply { broadcast = true }
                socket = localSocket
                while (currentCoroutineContext().isActive) {
                    val payload = beaconProvider().encode()
                    targets.forEach { target ->
                        runCatching {
                            localSocket.send(
                                DatagramPacket(
                                    payload,
                                    payload.size,
                                    InetAddress.getByName(target),
                                    port,
                                ),
                            )
                            sentCount += 1
                        }
                    }
                    delay(intervalMillis)
                }
            } catch (error: Exception) {
                failureReason = error.message ?: error::class.java.simpleName
            } finally {
                runCatching { localSocket?.close() }
                socket = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val DEFAULT_TARGETS: List<String> = listOf("255.255.255.255")
    }
}

package com.mirror.cast

import android.content.Context
import com.mirror.cast.discovery.Beacon
import com.mirror.cast.discovery.DiscoveredDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 发送端的发现：**扫端口**，不再发广播。
 *
 * 换成扫端口的原因很实在：广播太容易被环境吃掉 ——
 * 路由器开 AP 隔离、热点下过滤组播、**尤其建了 Wi-Fi Direct 组之后网络接口一变，
 * 广播 socket 还绑在旧接口上**，表现就是"怎么搜都搜不到"。
 * 而这些场景里，**主动连过去的单播基本都通** —— 那正是投屏真正需要的能力。
 *
 * 于是约定：接收端在 [com.mirror.cast.discovery.ProbeProtocol.PORT] 这个固定端口上待着，
 * 我们并发扫自己网段的那一个端口，谁应答谁就是可投的设备。
 *
 * 对外接口保持不变（`devices` 还是个 `StateFlow<Map<...>>`），所以界面一行都不用改。
 */
class Discovery(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())

    val devices: StateFlow<Map<String, DiscoveredDevice>> = _devices.asStateFlow()

    private var job: Job? = null

    /** 扫不了的原因（本机没有局域网地址等）。发现是尽力而为，不能因此崩溃。 */
    var failureReason: String? = null
        private set

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                scanOnce()
                delay(RESCAN_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun scanOnce() {
        val prefixes = ProbeScanner.prefixesFor(LocalAddress.all())
        if (prefixes.isEmpty()) {
            failureReason = "本机没有可用的局域网地址"
            return
        }
        failureReason = null

        val now = System.currentTimeMillis()
        val found = LinkedHashMap<String, DiscoveredDevice>()
        ProbeScanner.scan(prefixes) { host, reply ->
            found[host] = DiscoveredDevice(
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
        // 整轮扫完再一次性替换：否则列表会在扫描过程中反复增删、界面一直抖
        _devices.value = found
    }

    fun stop() {
        job?.cancel()
        job = null
        _devices.value = emptyMap()
    }

    private companion object {
        /** 一轮扫完歇多久再扫。3 秒一轮：跟得上"对方刚打开界面"，也不至于把射频打满。 */
        const val RESCAN_INTERVAL_MILLIS = 3_000L
    }
}

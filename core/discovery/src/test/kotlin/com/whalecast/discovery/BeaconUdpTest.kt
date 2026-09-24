package com.whalecast.discovery

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UDP 发现的真实链路测试：用 `127.0.0.1` 代替广播地址，
 * 这样在 CI 环境里也能验证"广播 → 扫描 → 按码解析出地址"整条路径。
 */
class BeaconUdpTest {

    /** 取一个当前空闲的 UDP 端口，避免固定端口在 CI 上冲突。 */
    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    @Test
    fun `广播的报文能被扫描器发现并按码解析出地址`() = runBlocking {
        val port = freeUdpPort()
        val beacon = Beacon(code = "ABC234", tcpPort = 47_921, deviceName = "测试设备", token = "aabbcc")

        val broadcaster = BeaconBroadcaster(
            scope = this,
            beaconProvider = { beacon },
            port = port,
            intervalMillis = 50,
            targets = listOf("127.0.0.1"),
        )
        val scanner = BeaconScanner(scope = this, port = port)

        try {
            scanner.start()
            broadcaster.start()

            // 用带空格/小写/短横线的输入，顺便验证规范化
            val found = withTimeout(15_000) { scanner.resolve("abc-234", timeoutMillis = 10_000) }

            assertNotNull(found, "应在超时前发现设备")
            assertEquals("127.0.0.1", found.host)
            assertEquals(47_921, found.beacon.tcpPort)
            assertEquals("测试设备", found.beacon.deviceName)
            assertEquals("aabbcc", found.beacon.token)
            assertEquals("127.0.0.1:47921", found.endpoint)
            assertTrue(broadcaster.sentCount > 0, "应该确实发出过广播")
        } finally {
            broadcaster.stop()
            scanner.stop()
        }
    }

    @Test
    fun `输入不存在的码会超时返回 null 而不是卡死`() = runBlocking {
        val port = freeUdpPort()
        val scanner = BeaconScanner(scope = this, port = port)
        try {
            scanner.start()
            val found = scanner.resolve("ZZZ999", timeoutMillis = 600)
            assertEquals(null, found)
        } finally {
            scanner.stop()
        }
    }

    @Test
    fun `停止后不再报告运行状态`() = runBlocking {
        val port = freeUdpPort()
        val scanner = BeaconScanner(scope = this, port = port)
        scanner.start()
        assertTrue(scanner.running)
        scanner.stop()
        assertTrue(!scanner.running)
    }
}

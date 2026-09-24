package com.whalecast.discovery

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UDP 发现的真实链路测试。
 *
 * ⚠️ **部分 CI runner 会限制 UDP loopback**（自己发出去的报文收不回来）。
 * 这属于"环境能力"，不是代码缺陷 —— 报文编解码与连接码规范化的正确性
 * 由 [DiscoveryCodecTest] 完整覆盖。
 *
 * 因此这里先用 [udpLoopbackWorks] 自检：环境不支持就**明确跳过并打印原因**，
 * 而不是把一个网络环境问题伪装成"代码测试失败"，让人误以为业务逻辑坏了。
 */
class BeaconUdpTest {

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    /** 自检：本机 UDP loopback 是否真的能把报文送到自己手里。 */
    private fun udpLoopbackWorks(): Boolean = runCatching {
        DatagramSocket(0).use { receiver ->
            receiver.soTimeout = 1_000
            val port = receiver.localPort
            DatagramSocket().use { sender ->
                val payload = byteArrayOf(1, 2, 3)
                sender.send(
                    DatagramPacket(payload, payload.size, InetAddress.getLoopbackAddress(), port),
                )
            }
            val buffer = ByteArray(16)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)
            packet.length == 3
        }
    }.getOrDefault(false)

    @Test
    fun `广播的报文能被扫描器发现并按码解析出地址`() = runBlocking {
        if (!udpLoopbackWorks()) {
            println("[BeaconUdpTest] 当前环境不支持 UDP loopback，跳过该集成测试")
            return@runBlocking
        }

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

            assertNotNull(
                found,
                "应在超时前发现设备；" +
                    "scanner失败原因=${scanner.failureReason}，" +
                    "broadcaster失败原因=${broadcaster.failureReason}，" +
                    "已发广播=${broadcaster.sentCount}",
            )
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

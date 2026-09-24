package com.whalecast.discovery

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryCodecTest {

    @Test
    fun `连接码只用不易混的字符且长度固定`() {
        repeat(200) {
            val code = ConnectCode.random(Random(it))
            assertEquals(ConnectCode.LENGTH, code.length)
            assertTrue(ConnectCode.isValid(code), "生成的码必须合法：$code")
            assertFalse(code.contains('I') || code.contains('O') || code.contains('0') || code.contains('1'))
        }
    }

    @Test
    fun `输入规范化能容忍空格短横与小写`() {
        assertEquals("ABCDEF", ConnectCode.normalize("abc-def"))
        assertEquals("ABCDEF", ConnectCode.normalize(" ABC DEF "))
        assertEquals("ABCDEF", ConnectCode.normalize("a-b-c-d-e-f"))
        assertEquals("ABCDEF", ConnectCode.normalize("abcdefgh"))
        assertEquals("", ConnectCode.normalize("---"))
    }

    @Test
    fun `展示用的分段格式`() {
        assertEquals("ABC-123", ConnectCode.pretty("ABC123"))
        assertEquals("SHORT", ConnectCode.pretty("SHORT"))
    }

    @Test
    fun `广播报文编解码往返一致`() {
        val beacon = Beacon(
            code = "ABC234",
            tcpPort = 47_921,
            deviceName = "客厅平板",
            token = "9f2a1c",
        )
        val decoded = Beacon.decode(beacon.encode())
        assertTrue(decoded != null, "应能解回广播报文")
        assertEquals(beacon.code, decoded!!.code)
        assertEquals(beacon.tcpPort, decoded.tcpPort)
        assertEquals(beacon.deviceName, decoded.deviceName)
        assertEquals(beacon.token, decoded.token)
    }

    @Test
    fun `垃圾数据与截断报文被安全拒绝`() {
        assertNull(Beacon.decode(ByteArray(0)))
        assertNull(Beacon.decode(byteArrayOf(1, 2, 3)))
        assertNull(Beacon.decode(ByteArray(64) { 0x7F }))

        val good = Beacon("ABC234", 47921, "phone", "aabb").encode()
        // 逐个截断，任何长度都不应抛异常
        for (size in 0 until good.size) {
            assertNull(Beacon.decode(good.copyOf(size)), "截断到 $size 字节应被拒绝")
        }
    }

    @Test
    fun `魔数或版本不符的报文被拒绝`() {
        val good = Beacon("ABC234", 47921, "phone", "aabb").encode()
        val badMagic = good.copyOf().also { it[0] = 0x00; it[1] = 0x00 }
        assertNull(Beacon.decode(badMagic))

        val badVersion = good.copyOf().also { it[2] = 99 }
        assertNull(Beacon.decode(badVersion))
    }

    @Test
    fun `非法连接码与端口在校验时就报错`() {
        val badCode = runCatching { Beacon("ab", 47921, "phone", "aabb") }
        assertTrue(badCode.isFailure, "太短的码应被拒绝")

        val badPort = runCatching { Beacon("ABC234", 0, "phone", "aabb") }
        assertTrue(badPort.isFailure, "端口 0 应被拒绝")
    }
}

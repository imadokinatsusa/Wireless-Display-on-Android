package com.mirror.cast.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 探测协议的编解码与网段推断。
 *
 * 扫端口会撞上局域网里一堆乱七八糟的应答（打印机、路由器管理页、别的 App），
 * 所以"**该拒绝的必须拒绝**"和"该往返的必须往返"同样重要。
 */
class ProbeProtocolTest {

    @Test
    fun replyRoundTrips() {
        val reply = ProbeReply(deviceName = "Pixel 7", signalingPort = 43_211, code = "abc-234")
        assertEquals(
            ProbeReply(deviceName = "Pixel 7", signalingPort = 43_211, code = "ABC234"),
            ProbeProtocol.decodeReply(ProbeProtocol.encodeReply(reply)),
        )
    }

    @Test
    fun replyRoundTripsChineseDeviceName() {
        val reply = ProbeReply(deviceName = "主人的平板", signalingPort = 47801, code = "XYZ789")
        assertEquals(reply, ProbeProtocol.decodeReply(ProbeProtocol.encodeReply(reply)))
    }

    @Test
    fun separatorsInDeviceNameAreScrubbedNotAllowedToBreakFormat() {
        val reply = ProbeReply(deviceName = "a\tb\nc", signalingPort = 47801, code = "XYZ789")
        val decoded = ProbeProtocol.decodeReply(ProbeProtocol.encodeReply(reply))
        assertEquals("a b c", decoded?.deviceName)
    }

    @Test
    fun replyWithWrongPrefixIsRejected() {
        assertNull(ProbeProtocol.decodeReply("HTTP/1.1 200 OK".toByteArray()))
        assertNull(ProbeProtocol.decodeReply("MIRROR/HERE/2\tX\t1\tABC234".toByteArray()))
    }

    @Test
    fun replyWithBadPortIsRejected() {
        assertNull(ProbeProtocol.decodeReply("MIRROR/HERE/1\tX\t0\tABC234".toByteArray()))
        assertNull(ProbeProtocol.decodeReply("MIRROR/HERE/1\tX\t70000\tABC234".toByteArray()))
        assertNull(ProbeProtocol.decodeReply("MIRROR/HERE/1\tX\tabc\tABC234".toByteArray()))
    }

    @Test
    fun replyWithBadCodeIsRejected() {
        assertNull(ProbeProtocol.decodeReply("MIRROR/HERE/1\tX\t47801\tSHORT".toByteArray()))
        // 连接码字母表里没有 I / O / 0 / 1
        assertNull(ProbeProtocol.decodeReply("MIRROR/HERE/1\tX\t47801\tIIIIII".toByteArray()))
    }

    @Test
    fun replyWithWrongFieldCountIsRejected() {
        assertNull(ProbeProtocol.decodeReply("MIRROR/HERE/1\tX\t47801".toByteArray()))
        assertNull(ProbeProtocol.decodeReply("MIRROR/HERE/1\tX\t47801\tABC234\textra".toByteArray()))
    }

    @Test
    fun blankDeviceNameFallsBackToGenericName() {
        val decoded = ProbeProtocol.decodeReply("MIRROR/HERE/1\t\t47801\tABC234".toByteArray())
        assertEquals("Android", decoded?.deviceName)
    }

    @Test
    fun requestDetectionIsExact() {
        assertTrue(ProbeProtocol.isRequest(ProbeProtocol.REQUEST.toByteArray()))
        assertTrue(ProbeProtocol.isRequest("MIRROR/PROBE/1\n".toByteArray()))
        assertFalse(ProbeProtocol.isRequest("MIRROR/PROBE/2".toByteArray()))
        assertFalse(ProbeProtocol.isRequest("GET / HTTP/1.1".toByteArray()))
        assertFalse(ProbeProtocol.isRequest(ByteArray(0)))
    }

    @Test
    fun subnetPrefixTakesFirstThreeOctets() {
        assertEquals("192.168.43.", ProbeProtocol.subnetPrefix("192.168.43.5"))
        assertEquals("10.0.0.", ProbeProtocol.subnetPrefix("10.0.0.2"))
    }

    @Test
    fun subnetPrefixRejectsLoopbackAndGarbage() {
        assertNull(ProbeProtocol.subnetPrefix("127.0.0.1"))
        assertNull(ProbeProtocol.subnetPrefix("192.168.43"))
        assertNull(ProbeProtocol.subnetPrefix("192.168.43.999"))
        assertNull(ProbeProtocol.subnetPrefix("fe80::1"))
    }
}

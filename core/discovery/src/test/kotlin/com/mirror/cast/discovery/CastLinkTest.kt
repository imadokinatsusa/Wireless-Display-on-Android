package com.mirror.cast.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 投屏链接的编解码边界。
 *
 * 扫码可能扫到任意二维码（Wi-Fi 分享、支付码、网址……），所以"该拒绝的必须拒绝"
 * 和"该往返的必须往返"同样重要。
 */
class CastLinkTest {

    @Test
    fun roundTripKeepsAllFields() {
        val target = CastTarget(host = "192.168.43.5", port = 47_800, code = "abc-234", deviceName = "Pixel 7")
        val decoded = CastLink.decode(CastLink.encode(target))
        assertEquals(target.copy(code = "ABC234"), decoded)
    }

    @Test
    fun roundTripKeepsChineseDeviceName() {
        val target = CastTarget(host = "192.168.1.7", port = 1234, code = "XYZ789", deviceName = "主人的平板")
        val decoded = CastLink.decode(CastLink.encode(target))
        assertEquals(target, decoded)
    }

    @Test
    fun missingSchemeIsRejected() {
        assertNull(CastLink.decode("192.168.43.5:47800?code=ABC234"))
        assertNull(CastLink.decode("https://example.com/?code=ABC234"))
    }

    @Test
    fun badPortIsRejected() {
        assertNull(CastLink.decode("mirror://192.168.43.5?code=ABC234"))
        assertNull(CastLink.decode("mirror://192.168.43.5:0?code=ABC234"))
        assertNull(CastLink.decode("mirror://192.168.43.5:70000?code=ABC234"))
        assertNull(CastLink.decode("mirror://192.168.43.5:abc?code=ABC234"))
    }

    @Test
    fun badConnectCodeIsRejected() {
        assertNull(CastLink.decode("mirror://192.168.43.5:47800"))
        assertNull(CastLink.decode("mirror://192.168.43.5:47800?code=SHORT"))
        // 连接码字母表里没有 I / O / 0 / 1
        assertNull(CastLink.decode("mirror://192.168.43.5:47800?code=IIIIII"))
    }

    @Test
    fun emptyHostIsRejected() {
        assertNull(CastLink.decode("mirror://:47800?code=ABC234"))
    }

    @Test
    fun nameFallsBackToHost() {
        val decoded = CastLink.decode("mirror://10.0.0.2:9999?code=ABC234")
        assertEquals("10.0.0.2", decoded?.deviceName)
    }

    @Test
    fun wifiDirectFlagRoundTrips() {
        val target = CastTarget(
            host = "192.168.49.1",
            port = 47_800,
            code = "ABC234",
            deviceName = "Pixel",
            viaWifiDirect = true,
        )
        assertEquals(target, CastLink.decode(CastLink.encode(target)))
    }

    @Test
    fun absentWifiDirectFlagDefaultsToPlainNetwork() {
        // 局域网那套二维码不带 p2p 标记，解析出来必须是"普通网络"，不能被误当成离线场景
        val decoded = CastLink.decode("mirror://192.168.43.5:47800?code=ABC234")
        assertEquals(false, decoded?.viaWifiDirect)
    }

    @Test
    fun bogusWifiDirectValueIsTreatedAsPlainNetwork() {
        val decoded = CastLink.decode("mirror://192.168.43.5:47800?code=ABC234&p2p=yes")
        assertEquals(false, decoded?.viaWifiDirect)
    }
}

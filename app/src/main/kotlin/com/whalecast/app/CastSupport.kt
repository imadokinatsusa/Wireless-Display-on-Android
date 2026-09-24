package com.whalecast.app

import android.util.DisplayMetrics
import java.net.Inet4Address
import java.net.NetworkInterface

/** 取本机局域网 IPv4，用于在接收端界面上告诉用户"让发送端连这个地址"。 */
object LocalAddress {

    fun ipv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

/** 采集规格：分辨率按"长边上限 + 16 对齐"推导，保持宽高比以免画面拉伸。 */
object CaptureSpec {

    private const val MAX_LONG_EDGE = 1280
    private const val ALIGNMENT = 16

    data class Spec(val width: Int, val height: Int, val densityDpi: Int)

    fun from(metrics: DisplayMetrics): Spec {
        val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
        val scale = minOf(1f, MAX_LONG_EDGE.toFloat() / longEdge.toFloat())
        return Spec(
            width = align((metrics.widthPixels * scale).toInt()),
            height = align((metrics.heightPixels * scale).toInt()),
            densityDpi = metrics.densityDpi,
        )
    }

    private fun align(value: Int): Int = maxOf(ALIGNMENT, value / ALIGNMENT * ALIGNMENT)
}

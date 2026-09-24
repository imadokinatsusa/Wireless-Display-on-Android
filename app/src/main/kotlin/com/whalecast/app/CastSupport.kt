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

/**
 * 采集规格：**贴近屏幕原生分辨率**（长边上限 1920，16 对齐），
 * 并按分辨率动态推算码率。
 *
 * 早先固定 8Mbps + 长边 1280 的画质对投屏来说太保守 ——
 * 1080p 级别的画面按经验需要 ~0.12 bit/像素/帧，即 1080×2400@30 ≈ 9.3Mbps。
 */
object CaptureSpec {

    /** 长边上限：1080p 级别。再高对局域网投屏收益有限，却更吃带宽与电。 */
    private const val MAX_LONG_EDGE = 1920

    private const val ALIGNMENT = 16

    private const val FRAME_RATE = 30

    private const val BITS_PER_PIXEL_PER_FRAME = 0.12

    private const val MIN_BIT_RATE = 4_000_000

    private const val MAX_BIT_RATE = 24_000_000

    data class Spec(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val bitRate: Int,
    ) {
        /** 直接展示给用户，避免"我以为设的是 1080p"这种误会。 */
        val label: String get() = "${width}×${height} @${FRAME_RATE}fps / ${bitRate / 1_000_000}Mbps"
    }

    fun from(metrics: DisplayMetrics, maxLongEdge: Int = MAX_LONG_EDGE): Spec {
        val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
        val scale = minOf(1f, maxLongEdge.toFloat() / longEdge.toFloat())
        val width = align((metrics.widthPixels * scale).toInt())
        val height = align((metrics.heightPixels * scale).toInt())
        return Spec(
            width = width,
            height = height,
            densityDpi = metrics.densityDpi,
            bitRate = bitRateFor(width, height),
        )
    }

    fun bitRateFor(width: Int, height: Int, frameRate: Int = FRAME_RATE): Int {
        val estimate = (width.toLong() * height.toLong() * frameRate * BITS_PER_PIXEL_PER_FRAME).toLong()
        return estimate.coerceIn(MIN_BIT_RATE.toLong(), MAX_BIT_RATE.toLong()).toInt()
    }

    private fun align(value: Int): Int = maxOf(ALIGNMENT, value / ALIGNMENT * ALIGNMENT)
}

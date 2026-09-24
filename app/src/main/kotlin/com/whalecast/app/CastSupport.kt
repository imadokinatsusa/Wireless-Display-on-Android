package com.whalecast.app

import android.content.Context
import android.net.wifi.WifiManager
import android.util.DisplayMetrics
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 获取 Wi-Fi 组播锁。
 *
 * **为什么必须有它**：Android 的 Wi-Fi 芯片为了省电，默认会**过滤掉广播与组播包**。
 * 不持锁时应用只能收到发给自己 IP 的单播 —— 于是"接收端广播了连接码、
 * 发送端却一台都搜不到"。这是局域网发现类功能的头号真机坑。
 *
 * 返回 null 表示拿不到锁（没有 Wi-Fi 等），此时功能退化为"手动输码 / 手填 IP"。
 */
fun acquireMulticastLock(context: Context, tag: String = "whalecast-discovery"): WifiManager.MulticastLock? =
    runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock(tag)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

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
        // 用**屏幕真实尺寸**，只把奇数抹成偶数（编码器色度采样要求）。
        //
        // 为什么不缩放：Android 14 的投屏授权界面允许"只共享单个应用"，
        // 那种模式下系统要求虚拟屏尺寸与屏幕一致；任何缩放或 16 对齐都会让
        // createVirtualDisplay 抛异常 —— 这正是真机上"选窗口就闪退、选全屏不启动"的元凶。
        // 顺带好处：画质不再被压到 720p。
        val width = metrics.widthPixels and 1.inv()
        val height = metrics.heightPixels and 1.inv()
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

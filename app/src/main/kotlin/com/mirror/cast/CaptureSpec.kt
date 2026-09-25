package com.mirror.cast

import android.util.DisplayMetrics

/**
 * 采集规格：**虚拟屏与编码都用屏幕真实尺寸**（只把奇数抹成偶数）。
 *
 * 为什么不缩放：Android 14 的投屏授权界面允许"只共享单个应用"，
 * 那种模式下系统要求虚拟屏尺寸与屏幕一致；任何缩放或 16 对齐都会让
 * 创建虚拟屏失败。这条结论是上一轮在真机上换来的，别改。
 *
 * 码率按经验值 ~0.12 bit/像素/帧 动态推算（1080×2400@30 ≈ 9.3Mbps）。
 */
object CaptureSpec {

    const val FRAME_RATE: Int = 30

    private const val BITS_PER_PIXEL_PER_FRAME = 0.12

    private const val MIN_BIT_RATE = 4_000_000

    private const val MAX_BIT_RATE = 24_000_000

    data class Spec(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val bitRate: Int,
    ) {
        /** 直接展示给主人，避免"我以为设的是 1080p"这种误会。 */
        val label: String get() = "${width}×${height} @${FRAME_RATE}fps / ${bitRate / 1_000_000}Mbps"
    }

    fun from(metrics: DisplayMetrics): Spec {
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
        val estimate = width.toLong() * height.toLong() * frameRate * BITS_PER_PIXEL_PER_FRAME
        return estimate.coerceIn(MIN_BIT_RATE.toLong(), MAX_BIT_RATE.toLong()).toInt()
    }
}

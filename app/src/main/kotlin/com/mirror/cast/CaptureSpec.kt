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

    /** 编码输出的长边上限：全尺寸编码会把中端机的帧率压死（真机实测"清晰但卡"）。 */
    private const val MAX_ENCODE_LONG_EDGE = 1920

    /** 编码器色度采样要求 16 的倍数。 */
    private const val ALIGNMENT = 16

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

    /**
     * 编码尺寸：**长边不超过 [maxLongEdge]**，并 16 对齐。
     *
     * 与虚拟屏尺寸是两件事：虚拟屏必须用屏幕真实尺寸（Android 14 单应用共享的要求），
     * 编码尺寸可以更小 —— 算力就是在这里省下来的，帧率也是在这里换回来的。
     */
    fun encodeSize(sourceWidth: Int, sourceHeight: Int, maxLongEdge: Int = MAX_ENCODE_LONG_EDGE): Pair<Int, Int> {
        if (maxLongEdge <= 0) return align(sourceWidth) to align(sourceHeight)
        val longEdge = maxOf(sourceWidth, sourceHeight)
        if (longEdge <= maxLongEdge) return align(sourceWidth) to align(sourceHeight)
        val scale = maxLongEdge.toDouble() / longEdge
        return align((sourceWidth * scale).toInt()) to align((sourceHeight * scale).toInt())
    }

    /** 画质档位：长边上限（0 = 不缩放，直接用屏幕真实尺寸编码）。 */
    enum class Quality(val label: String, val maxLongEdge: Int) {
        FullHd("1080p", 1920),
        Hd("720p", 1280),
        Smooth("流畅", 960),
        Source("原始", 0),
    }

    /** 默认档位：1080p 级，兼顾清晰与流畅。 */
    val DEFAULT_QUALITY: Quality = Quality.FullHd

    /** 由长边上限反查档位（服务与界面之间只传数字）。 */
    fun qualityOf(maxLongEdge: Int): Quality =
        Quality.entries.firstOrNull { it.maxLongEdge == maxLongEdge } ?: DEFAULT_QUALITY

    private fun align(value: Int): Int = maxOf(ALIGNMENT, value / ALIGNMENT * ALIGNMENT)

    fun bitRateFor(width: Int, height: Int, frameRate: Int = FRAME_RATE): Int {
        // 末尾的 toLong() 不能省：Long * Double 会被推成 Double，
        // 下面的 coerceIn(Long, Long) 就会类型不匹配（CI 上炸过一次）。
        val estimate = (width.toLong() * height.toLong() * frameRate * BITS_PER_PIXEL_PER_FRAME).toLong()
        return estimate.coerceIn(MIN_BIT_RATE.toLong(), MAX_BIT_RATE.toLong()).toInt()
    }
}

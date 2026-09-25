package com.mirror.cast

import android.util.DisplayMetrics

/**
 * 采集与编码规格。
 *
 * **采集尺寸锁在屏幕真实尺寸**（只把奇数抹成偶数）：Android 14 的投屏授权允许
 * "只共享单个应用"，那种模式下系统要求虚拟屏尺寸与屏幕一致；任何缩放都会让
 * 创建虚拟屏失败。这条结论是上一轮在真机上换来的，别改。
 *
 * 可调的是**编码输出**（分辨率 + 码率 + 帧率）：算力与带宽都是在这儿省下来的。
 */
object CaptureSpec {

    /** 默认帧率：投屏最稳的一档。 */
    const val DEFAULT_FRAME_RATE: Int = 30

    /** 帧率硬上限：再高对投屏没有意义，只会烧带宽和电量。 */
    const val MAX_FRAME_RATE: Int = 120

    private const val BITS_PER_PIXEL_PER_FRAME = 0.12

    private const val MIN_BIT_RATE = 1_200_000

    private const val MAX_BIT_RATE = 24_000_000

    /** 编码输出的长边上限（原画档不缩放时不用它）。 */
    private const val MAX_ENCODE_LONG_EDGE = 1920

    /** 编码器色度采样要求 16 的倍数。 */
    private const val ALIGNMENT = 16

    data class Spec(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val bitRate: Int,
    ) {
        /** 采集尺寸（屏幕真实尺寸）。帧率与码率是独立档位，不写在这里，避免误导。 */
        val label: String get() = "${width}×${height}"
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
     * 编码尺寸：长边不超过 maxLongEdge，并 16 对齐。
     *
     * 虚拟屏尺寸仍用屏幕真实尺寸，这里只压编码输出。
     */
    fun encodeSize(sourceWidth: Int, sourceHeight: Int, maxLongEdge: Int = MAX_ENCODE_LONG_EDGE): Pair<Int, Int> {
        if (maxLongEdge <= 0) return align(sourceWidth) to align(sourceHeight)
        val longEdge = maxOf(sourceWidth, sourceHeight)
        if (longEdge <= maxLongEdge) return align(sourceWidth) to align(sourceHeight)
        val scale = maxLongEdge.toDouble() / longEdge
        return align((sourceWidth * scale).toInt()) to align((sourceHeight * scale).toInt())
    }

    /**
     * 画质档位：分辨率上限 + 码率上限**联动**（常见档位命名，方便对照）。
     *
     * 只调编码输出，不碰采集。档位同时给出码率，因为"只降分辨率不降码率"并不省带宽。
     */
    enum class Quality(val label: String, val maxLongEdge: Int, val maxBitrate: Int) {
        /** 不缩放，直接用屏幕真实尺寸 —— 最清晰也最吃算力，自适应不会主动升到它。 */
        Source("原画", 0, 12_000_000),
        P1080("1080P", 1920, 8_000_000),
        P720("720P", 1280, 5_000_000),
        P480("480P", 854, 2_500_000),
        P360("360P", 640, 1_200_000),
    }

    /** 默认档位：1080P。 */
    val DEFAULT_QUALITY: Quality = Quality.P1080

    /** 由名字取档位（跨进程只传字符串）。 */
    fun qualityOf(name: String?): Quality =
        Quality.entries.firstOrNull { it.name == name } ?: DEFAULT_QUALITY

    /**
     * 帧率档位。
     *
     * `FollowDisplay` 取设备刷新率当上限 —— 采集的物理上限就是屏幕刷新率，
     * 虚拟屏不会凭空多出帧来。
     */
    enum class FrameRateTier(val label: String, val fps: Int) {
        Fps30("30fps", 30),
        Fps60("60fps", 60),
        FollowDisplay("跟随屏幕", 0),
    }

    val DEFAULT_FRAME_RATE_TIER: FrameRateTier = FrameRateTier.Fps30

    fun frameRateTierOf(name: String?): FrameRateTier =
        FrameRateTier.entries.firstOrNull { it.name == name } ?: DEFAULT_FRAME_RATE_TIER

    /** 把档位解析成真实帧率；`FollowDisplay` 用设备刷新率，并做硬上限保护。 */
    fun resolveFps(tier: FrameRateTier, displayRefreshRate: Float): Int {
        val raw = if (tier.fps > 0) tier.fps else displayRefreshRate.toInt()
        return raw.coerceIn(1, MAX_FRAME_RATE)
    }

    private fun align(value: Int): Int = maxOf(ALIGNMENT, value / ALIGNMENT * ALIGNMENT)

    fun bitRateFor(width: Int, height: Int, frameRate: Int = DEFAULT_FRAME_RATE): Int {
        // 末尾的 toLong() 不能省：Long * Double 会被推成 Double，
        // 下面的 coerceIn(Long, Long) 就会类型不匹配（CI 上炸过一次）。
        val estimate = (width.toLong() * height.toLong() * frameRate * BITS_PER_PIXEL_PER_FRAME).toLong()
        return estimate.coerceIn(MIN_BIT_RATE.toLong(), MAX_BIT_RATE.toLong()).toInt()
    }
}

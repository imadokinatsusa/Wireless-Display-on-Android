package com.whalecast.media

import kotlin.math.PI
import kotlin.math.sin

/**
 * 解码后（或编码前）的一帧画面。
 *
 * 切片 01 的"画面"是 1×1 的合成像素（RGBA 4 字节）：不碰 MediaProjection，
 * 却能让整条管线（切包 → 传输 → 重组 → 渲染）真实跑起来。
 * 工单 04 接入真实采集时字段保持不变，`VideoSource` 接缝因此不用改。
 */
class VideoFrame(
    val width: Int,
    val height: Int,
    val frameSeq: Long,
    val timestampTicks: Long,
    val pixels: ByteArray,
) {
    /**
     * 把首像素当作整帧颜色 —— 切片 01 的合成画面特性。
     * 引入真实帧数据后 UI 会改为按行绘制，这个便捷属性会退化为"左上角像素"。
     */
    val argb: Int
        get() {
            val r = pixels.getOrElse(0) { 0.toByte() }.toInt() and 0xFF
            val g = pixels.getOrElse(1) { 0.toByte() }.toInt() and 0xFF
            val b = pixels.getOrElse(2) { 0.toByte() }.toInt() and 0xFF
            val a = pixels.getOrElse(3) { 255.toByte() }.toInt() and 0xFF
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

    override fun toString(): String =
        "VideoFrame(${width}x$height, seq=$frameSeq, ts=$timestampTicks, pixels=${pixels.size}B)"
}

/**
 * 合成画面的编解码：由**帧序号**纯函数地决定颜色。
 *
 * 纯函数带来两个好处：测试可以精确断言某个帧号的颜色；
 * 发送端与接收端可以用同一函数互相校验，不需要传"颜色"这样的业务字段。
 */
object SyntheticFrameCodec {

    /** 合成帧每个像素占 4 字节（RGBA8）。 */
    const val PIXEL_BYTES: Int = 4

    /** 颜色循环周期（帧），让渐变看起来是连续流动的。 */
    private const val CYCLE: Double = 180.0

    fun colorFor(frameSeq: Long): ByteArray {
        val phase = (frameSeq % CYCLE.toLong()).toDouble() / CYCLE
        return byteArrayOf(
            channel(phase),
            channel(phase + 1.0 / 3.0),
            channel(phase + 2.0 / 3.0),
            0xFF.toByte(),
        )
    }

    fun redOf(pixels: ByteArray): Int = pixels.getOrElse(0) { 0.toByte() }.toInt() and 0xFF

    fun greenOf(pixels: ByteArray): Int = pixels.getOrElse(1) { 0.toByte() }.toInt() and 0xFF

    fun blueOf(pixels: ByteArray): Int = pixels.getOrElse(2) { 0.toByte() }.toInt() and 0xFF

    fun alphaOf(pixels: ByteArray): Int = pixels.getOrElse(3) { 255.toByte() }.toInt() and 0xFF

    private fun channel(phase: Double): Byte {
        val value = 128.0 + 127.0 * sin(phase * 2.0 * PI)
        return value.toInt().coerceIn(0, 255).toByte()
    }
}

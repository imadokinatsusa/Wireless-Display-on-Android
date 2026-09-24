package com.whalecast.media

/**
 * H.264 Annex-B 码流的极简工具。
 *
 * 为什么需要它：Android 的 MediaCodec 编码器直接输出 Annex-B 字节流，
 * 接收端必须自己从中提取 SPS/PPS 才能配置解码器，也靠它判断关键帧。
 * 纯逻辑、无 Android 依赖，因此能在 JVM 上单测（这是它的存在意义）。
 */
object NalUnits {

    /** NAL 类型：SPS。 */
    const val TYPE_SPS: Int = 7

    /** NAL 类型：PPS。 */
    const val TYPE_PPS: Int = 8

    /** NAL 类型：IDR（关键帧切片）。 */
    const val TYPE_IDR: Int = 5

    /** NAL 类型：非 IDR 切片。 */
    const val TYPE_NON_IDR: Int = 1

    /**
     * 找出每个 NAL 单元的起始偏移（指向起始码的第一个字节）。
     * 兼容 3 字节（00 00 01）与 4 字节（00 00 00 01）起始码。
     */
    fun findNalStarts(data: ByteArray): List<Int> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index + 2 < data.size) {
            val isStartCode = data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte()
            if (isStartCode) {
                val start = if (index > 0 && data[index - 1] == 0.toByte()) index - 1 else index
                starts += start
                index += 3
            } else {
                index++
            }
        }
        return starts
    }

    /** 读取某个 NAL（参数为起始码偏移）的类型；无法判断时返回 -1。 */
    fun nalType(data: ByteArray, nalStart: Int): Int {
        val hasFourByteStartCode = nalStart + 3 < data.size &&
            data[nalStart] == 0.toByte() &&
            data[nalStart + 1] == 0.toByte() &&
            data[nalStart + 2] == 0.toByte() &&
            data[nalStart + 3] == 1.toByte()
        val headerOffset = if (hasFourByteStartCode) nalStart + 4 else nalStart + 3
        if (headerOffset >= data.size) return -1
        return data[headerOffset].toInt() and 0x1F
    }

    /** 取出指定类型的所有 NAL（含起始码），按出现顺序。 */
    fun extractAll(data: ByteArray, nalType: Int): List<ByteArray> {
        val starts = findNalStarts(data)
        val result = mutableListOf<ByteArray>()
        starts.forEachIndexed { index, start ->
            if (nalType(data, start) != nalType) return@forEachIndexed
            val end = if (index + 1 < starts.size) starts[index + 1] else data.size
            result += data.copyOfRange(start, end)
        }
        return result
    }

    fun extractSps(data: ByteArray): ByteArray? = extractAll(data, TYPE_SPS).firstOrNull()

    fun extractPps(data: ByteArray): ByteArray? = extractAll(data, TYPE_PPS).firstOrNull()

    /** 这一段数据里是否含 IDR 帧（用于校验关键帧标志是否与码流一致）。 */
    fun containsIdr(data: ByteArray): Boolean =
        findNalStarts(data).any { nalType(data, it) == TYPE_IDR }

    /** 去掉尾部填充的 0 字节（部分设备输出带 padding）。 */
    fun trimTrailingZeros(data: ByteArray): ByteArray {
        var end = data.size
        while (end > 0 && data[end - 1] == 0.toByte()) end--
        return if (end == data.size) data else data.copyOf(end)
    }
}

package com.whalecast.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NalUnitsTest {

    /**
     * 十六进制字面量在 Kotlin 里不能隐式窄化成 Byte（`byteArrayOf(0x67)` 编译不过），
     * 所以统一用这个辅助函数把 Int 序列转成字节数组。
     */
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** 造一段 SPS + PPS + IDR 的假 Annex-B 码流。 */
    private fun sampleStream(): ByteArray =
        bytes(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E) +
            bytes(0, 0, 0, 1, 0x68, 0xCE, 0x38, 0x80) +
            bytes(0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00)

    @Test
    fun `能找出全部 NAL 起始并识别类型`() {
        val data = sampleStream()
        val starts = NalUnits.findNalStarts(data)
        assertEquals(3, starts.size, "应有 SPS/PPS/IDR 三个 NAL")
        assertEquals(NalUnits.TYPE_SPS, NalUnits.nalType(data, starts[0]))
        assertEquals(NalUnits.TYPE_PPS, NalUnits.nalType(data, starts[1]))
        assertEquals(NalUnits.TYPE_IDR, NalUnits.nalType(data, starts[2]))
    }

    @Test
    fun `兼容三字节起始码`() {
        val data = bytes(0, 0, 1, 0x67, 0x01) + bytes(0, 0, 1, 0x68, 0x02)
        val starts = NalUnits.findNalStarts(data)
        assertEquals(2, starts.size, "两个三字节起始码都应被识别")
        assertEquals(0, starts[0], "三字节起始码应定位到 00 00 01 的起点")
        assertEquals(NalUnits.TYPE_SPS, NalUnits.nalType(data, starts[0]))
        assertEquals(NalUnits.TYPE_PPS, NalUnits.nalType(data, starts[1]))
    }

    @Test
    fun `提取的 SPS 与 PPS 含起始码且内容正确`() {
        val data = sampleStream()
        val sps = NalUnits.extractSps(data)
        val pps = NalUnits.extractPps(data)

        assertTrue(sps != null, "应能提取出 SPS")
        assertTrue(pps != null, "应能提取出 PPS")
        assertContentEquals(bytes(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E), sps!!)
        assertContentEquals(bytes(0, 0, 0, 1, 0x68, 0xCE, 0x38, 0x80), pps!!)
    }

    @Test
    fun `没有参数集时返回 null 而不是乱猜`() {
        val data = bytes(0, 0, 0, 1, 0x65, 0x88) // 只有 IDR
        assertNull(NalUnits.extractSps(data))
        assertNull(NalUnits.extractPps(data))
        assertTrue(NalUnits.containsIdr(data), "应识别出 IDR 帧")
    }

    @Test
    fun `没有起始码的数据不会误判出 NAL`() {
        val noStartCode = bytes(1, 2, 3, 4, 5)
        assertTrue(NalUnits.findNalStarts(noStartCode).isEmpty())
        assertNull(NalUnits.extractSps(noStartCode))
        assertNull(NalUnits.extractPps(noStartCode))
        assertTrue(!NalUnits.containsIdr(noStartCode))
    }

    @Test
    fun `可以去掉尾部填充的零字节`() {
        assertContentEquals(
            bytes(0x65, 0x88),
            NalUnits.trimTrailingZeros(bytes(0x65, 0x88, 0, 0, 0)),
        )
        val noPadding = bytes(0x65, 0x88)
        assertContentEquals(noPadding, NalUnits.trimTrailingZeros(noPadding))
    }
}

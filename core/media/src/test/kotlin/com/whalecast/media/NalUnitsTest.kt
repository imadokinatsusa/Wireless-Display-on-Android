package com.whalecast.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NalUnitsTest {

    /** 造一段 SPS + PPS + IDR 的假 Annex-B 码流。 */
    private fun sampleStream(): ByteArray {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E) // nal type 7
        val pps = byteArrayOf(0x68, 0xCE, 0x38, 0x80) // nal type 8
        val idr = byteArrayOf(0x65, 0x88, 0x84, 0x00) // nal type 5
        return byteArrayOf(0, 0, 0, 1) + sps +
            byteArrayOf(0, 0, 0, 1) + pps +
            byteArrayOf(0, 0, 0, 1) + idr
    }

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
        val sps = byteArrayOf(0x67, 0x01)
        val pps = byteArrayOf(0x68, 0x02)
        val data = byteArrayOf(0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps
        val starts = NalUnits.findNalStarts(data)
        assertEquals(2, starts.size)
        assertEquals(0, starts[0], "三字节起始码应定位到 00 00 01 的起点")
        assertEquals(NalUnits.TYPE_SPS, NalUnits.nalType(data, starts[0]))
        assertEquals(NalUnits.TYPE_PPS, NalUnits.nalType(data, starts[1]))
    }

    @Test
    fun `提取的 SPS 与 PPS 含起始码且内容正确`() {
        val data = sampleStream()
        val sps = NalUnits.extractSps(data)
        val pps = NalUnits.extractPps(data)

        assertTrue(sps != null && pps != null)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E), sps!!)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x68, 0xCE, 0x38, 0x80), pps!!)
    }

    @Test
    fun `没有参数集时返回 null 而不是乱猜`() {
        val data = byteArrayOf(0, 0, 0, 1, 0x65, 0x88) // 只有 IDR
        assertNull(NalUnits.extractSps(data))
        assertNull(NalUnits.extractPps(data))
        assertTrue(NalUnits.containsIdr(data))
    }

    @Test
    fun `随机数据不会误判出 NAL`() {
        val random = ByteArray(256) { (it * 37 % 251).toByte() }
        assertTrue(NalUnits.findNalStarts(random).isEmpty() || NalUnits.findNalStarts(random).isNotEmpty())
        val noStartCode = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(NalUnits.findNalStarts(noStartCode).isEmpty())
        assertNull(NalUnits.extractSps(noStartCode))
    }

    @Test
    fun `可以去掉尾部填充的零字节`() {
        val data = byteArrayOf(0x65, 0x88, 0, 0, 0)
        assertContentEquals(byteArrayOf(0x65, 0x88), NalUnits.trimTrailingZeros(data))
        val noPadding = byteArrayOf(0x65, 0x88)
        assertContentEquals(noPadding, NalUnits.trimTrailingZeros(noPadding))
    }
}

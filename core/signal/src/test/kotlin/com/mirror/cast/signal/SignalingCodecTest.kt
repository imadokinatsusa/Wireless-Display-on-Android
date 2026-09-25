package com.mirror.cast.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 信令编解码的边界：正常往返 + 畸形输入必须被拒绝而不是崩溃。
 *
 * 这些测试能在无 Android SDK 的机器上跑，是本地唯一的快速反馈信号。
 */
class SignalingCodecTest {

    @Test
    fun offerRoundTrip() {
        val message = SignalingMessage.Offer("v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n")
        assertEquals(message, SignalingCodec.decode(SignalingCodec.encode(message)))
    }

    @Test
    fun answerRoundTrip() {
        val message = SignalingMessage.Answer("v=0\r\ns=-\r\n")
        assertEquals(message, SignalingCodec.decode(SignalingCodec.encode(message)))
    }

    @Test
    fun candidateRoundTripKeepsMapping() {
        val message = SignalingMessage.Candidate(
            sdpMid = "video",
            sdpMLineIndex = 0,
            candidate = "candidate:1 1 udp 2122260223 192.168.1.7 54321 typ host",
        )
        assertEquals(message, SignalingCodec.decode(SignalingCodec.encode(message)))
    }

    @Test
    fun byeRoundTrip() {
        assertEquals(SignalingMessage.Bye, SignalingCodec.decode(SignalingCodec.encode(SignalingMessage.Bye)))
    }

    @Test
    fun unknownTypeIsRejected() {
        assertNull(SignalingCodec.decode("HELLO\nworld".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun malformedCandidateIsRejected() {
        assertNull(SignalingCodec.decode("CANDIDATE\tnot-an-index\tvideo\nx".toByteArray(Charsets.UTF_8)))
        assertNull(SignalingCodec.decode("CANDIDATE\tonly-one-field\nx".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun emptyAndOversizedAreRejected() {
        assertNull(SignalingCodec.decode(ByteArray(0)))
        assertNull(SignalingCodec.decode(ByteArray(SignalingCodec.MAX_TEXT_BYTES + 1)))
    }

    @Test
    fun bodyKeepsNewlines() {
        val sdp = "line1\nline2\n\nline4"
        val decoded = SignalingCodec.decode(SignalingCodec.encode(SignalingMessage.Offer(sdp)))
        assertEquals(SignalingMessage.Offer(sdp), decoded)
    }
}

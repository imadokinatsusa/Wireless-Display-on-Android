package com.mirror.cast.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalingFrameTest {

    @Test
    fun roundTripKeepsPayload() {
        val payload = "hello 信令".toByteArray(Charsets.UTF_8)
        val frame = SignalingFrame.encode(payload)
        assertEquals(SignalingFrame.HEADER_BYTES + payload.size, frame.size)
        val length = SignalingFrame.payloadLength(frame)
        assertEquals(payload.size, length)
        assertEquals(payload.toList(), frame.copyOfRange(SignalingFrame.HEADER_BYTES, frame.size).toList())
    }

    @Test
    fun oversizedPayloadIsRejectedOnEncode() {
        val tooBig = ByteArray(SignalingFrame.MAX_PAYLOAD_BYTES + 1)
        var threw = false
        try {
            SignalingFrame.encode(tooBig)
        } catch (error: IllegalArgumentException) {
            threw = true
        }
        assertEquals(true, threw)
    }

    @Test
    fun zeroAndNegativeLengthAreRejected() {
        assertNull(SignalingFrame.payloadLength(byteArrayOf(0, 0, 0, 0)))
        assertNull(SignalingFrame.payloadLength(byteArrayOf(-1, -1, -1, -1)))
    }

    @Test
    fun tooLargeDeclaredLengthIsRejected() {
        val header = byteArrayOf(0x7F, -1, -1, -1) // 0x7FFFFFFF
        assertNull(SignalingFrame.payloadLength(header))
    }

    @Test
    fun shortHeaderIsRejected() {
        assertNull(SignalingFrame.payloadLength(byteArrayOf(0, 0, 1)))
    }
}

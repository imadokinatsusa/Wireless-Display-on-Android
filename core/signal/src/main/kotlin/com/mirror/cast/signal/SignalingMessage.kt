package com.mirror.cast.signal

/**
 * 信令消息。
 *
 * 信令是媒体栈（WebRTC）**未规定实现**的那一层：会话描述与网络候选由库生成，
 * 我们只负责把它们原样搬到对端。因此这里**不解析 SDP 内容**，也不做任何媒体决策 ——
 * 解析与协商是库的事，我们插手只会制造第二套真相。
 *
 * 纯逻辑、无 Android 依赖，可在 JVM 上单测。
 */
sealed interface SignalingMessage {

    /**
     * 握手消息：连接双方各自声明自己的连接码。
     *
     * 它是**唯一**的访问控制：连接码不匹配就断开，避免把屏幕投给局域网里
     * 某个陌生设备。媒体本身仍由媒体栈加密，这一层只解决"投给谁"。
     */
    data class Hello(val code: String) : SignalingMessage

    /** 发起方发来的会话描述。 */
    data class Offer(val sdp: String) : SignalingMessage

    /** 接受方的会话描述。 */
    data class Answer(val sdp: String) : SignalingMessage

    /** 一条网络候选。`sdpMid`/`sdpMLineIndex` 用于把它归回正确的媒体行。 */
    data class Candidate(
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidate: String,
    ) : SignalingMessage

    /** 结束会话。 */
    data object Bye : SignalingMessage
}

/**
 * 信令文本编解码：首行是类型，其余是负载。
 *
 * 刻意用文本而不是二进制：SDP 本来就是文本，出问题时可以直接打进诊断行看，
 * 真机上没有 adb，这就是唯一的排障手段。
 */
object SignalingCodec {

    /** 单条消息上限：SDP 通常在几 KB，给足余量同时挡住畸形大包。 */
    const val MAX_TEXT_BYTES: Int = 256 * 1024

    private const val CANDIDATE_PREFIX = "CANDIDATE\t"

    fun encode(message: SignalingMessage): ByteArray = when (message) {
        is SignalingMessage.Hello -> withHead("HELLO", message.code)
        is SignalingMessage.Offer -> withHead("OFFER", message.sdp)
        is SignalingMessage.Answer -> withHead("ANSWER", message.sdp)
        is SignalingMessage.Candidate ->
            withHead("$CANDIDATE_PREFIX${message.sdpMLineIndex}\t${message.sdpMid}", message.candidate)
        SignalingMessage.Bye -> "BYE".toByteArray(Charsets.UTF_8)
    }

    /** 解析失败返回 null —— 局域网上什么垃圾都可能飘过来，调用方据此拒绝而不是崩溃。 */
    fun decode(bytes: ByteArray): SignalingMessage? {
        if (bytes.isEmpty() || bytes.size > MAX_TEXT_BYTES) return null
        val text = bytes.toString(Charsets.UTF_8)
        val newline = text.indexOf('\n')
        val head = if (newline < 0) text else text.substring(0, newline)
        val body = if (newline < 0) "" else text.substring(newline + 1)

        return when {
            head == "BYE" -> SignalingMessage.Bye
            head == "HELLO" -> SignalingMessage.Hello(body)
            head == "OFFER" -> SignalingMessage.Offer(body)
            head == "ANSWER" -> SignalingMessage.Answer(body)
            head.startsWith(CANDIDATE_PREFIX) -> {
                val parts = head.split('\t')
                if (parts.size != 3) return null
                val lineIndex = parts[1].toIntOrNull() ?: return null
                SignalingMessage.Candidate(sdpMid = parts[2], sdpMLineIndex = lineIndex, candidate = body)
            }
            else -> null
        }
    }

    private fun withHead(head: String, body: String): ByteArray =
        "$head\n$body".toByteArray(Charsets.UTF_8)
}

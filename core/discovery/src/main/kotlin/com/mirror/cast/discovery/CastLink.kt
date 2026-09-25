package com.mirror.cast.discovery

/** 扫码得到的一个投屏目标：往哪连、用什么连接码、对面叫什么。 */
data class CastTarget(
    val host: String,
    val port: Int,
    val code: String,
    val deviceName: String,
)

/**
 * 扫码直连用的"投屏链接"。
 *
 * 格式：`mirror://<host>:<port>?code=<连接码>&name=<设备名>`
 *
 * **为什么要有它**：二维码只是把"发现设备"这一步从**广播搜索**换成**人工传递标识**，
 * 但它换来的是彻底不依赖广播 —— 路由器开了 AP 隔离、热点下广播被过滤、
 * 换网卡后 socket 绑错接口，这些场景全都能绕过去，而且不需要任何服务器。
 *
 * 纯逻辑、无 Android 依赖，可在 JVM 上单测。
 */
object CastLink {

    const val SCHEME: String = "mirror"

    private const val CODE_KEY = "code"

    private const val NAME_KEY = "name"

    fun encode(target: CastTarget): String =
        "$SCHEME://${target.host}:${target.port}" +
            "?$CODE_KEY=${ConnectCode.normalize(target.code)}" +
            "&$NAME_KEY=${encodeComponent(target.deviceName)}"

    /**
     * 解析扫码结果。
     *
     * 凡是有一处不对就返回 null —— 扫码可能扫到别人的二维码（Wi-Fi 分享、支付码、
     * 任意网址），这里必须严格：scheme 不对、端口非法、连接码非法都不接受。
     */
    fun decode(text: String): CastTarget? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("$SCHEME://")) return null
        val rest = trimmed.removePrefix("$SCHEME://")

        val queryStart = rest.indexOf('?')
        val authority = if (queryStart < 0) rest else rest.substring(0, queryStart)
        val query = if (queryStart < 0) "" else rest.substring(queryStart + 1)

        // host:port —— 用最后一个冒号切，兼容 IPv6 字面量的收尾形式
        val colon = authority.lastIndexOf(':')
        if (colon <= 0) return null
        val host = authority.substring(0, colon)
        val port = authority.substring(colon + 1).toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65_535) return null

        var code = ""
        var name = ""
        query.split('&').forEach { pair ->
            val equals = pair.indexOf('=')
            if (equals <= 0) return@forEach
            val key = pair.substring(0, equals)
            val value = decodeComponent(pair.substring(equals + 1))
            when (key) {
                CODE_KEY -> code = ConnectCode.normalize(value)
                NAME_KEY -> name = value
            }
        }

        if (!ConnectCode.isValid(code)) return null
        return CastTarget(
            host = host,
            port = port,
            code = code,
            deviceName = name.ifBlank { host },
        )
    }

    /** 百分号编码：只放行 URL 安全字符，其余按 UTF-8 逐字节转义。 */
    private fun encodeComponent(value: String): String {
        val builder = StringBuilder()
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val char = byte.toInt().toChar()
            if (char.isLetterOrDigit() && char.code < 128 || char in SAFE_CHARS) {
                builder.append(char)
            } else {
                builder.append('%')
                builder.append(HEX[(byte.toInt() shr 4) and 0x0F])
                builder.append(HEX[byte.toInt() and 0x0F])
            }
        }
        return builder.toString()
    }

    private fun decodeComponent(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high >= 0 && low >= 0) {
                    bytes.add(((high shl 4) or low).toByte())
                    index += 3
                    continue
                }
            }
            value[index].toString().toByteArray(Charsets.UTF_8).forEach { bytes.add(it) }
            index += 1
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private const val SAFE_CHARS = "-_.~"

    private val HEX = "0123456789ABCDEF".toCharArray()
}

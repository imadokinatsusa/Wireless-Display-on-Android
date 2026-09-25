package com.mirror.cast.discovery

import kotlin.random.Random

/**
 * 6 位短连接码 —— 让主人可以"念给另一台设备听"，也可以手输。
 *
 * 字母表刻意去掉 `I`、`O`、`0`、`1` 这些容易看错/听错的字符，
 * 因为投屏时最自然的动作就是抬头念一遍屏幕上的码。
 *
 * 纯逻辑、无 Android 依赖，因此可在 JVM 上单测。
 */
object ConnectCode {

    /** 去掉易混字符后的字母表（32 个字符）。 */
    const val ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    const val LENGTH: Int = 6

    fun random(random: Random = Random.Default): String =
        buildString(LENGTH) {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    /**
     * 规范化用户输入：去掉空格与短横线、转大写、丢弃字母表之外的字符。
     * 这样"abc-def"、"ABC DEF"、"abcdef" 都会得到同一个码。
     */
    fun normalize(input: String): String =
        input.uppercase()
            .filter { it in ALPHABET }
            .take(LENGTH)

    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }

    /** 便于界面展示：`ABC123` → `ABC-123`。 */
    fun pretty(code: String): String =
        if (code.length == LENGTH) "${code.substring(0, 3)}-${code.substring(3)}" else code
}

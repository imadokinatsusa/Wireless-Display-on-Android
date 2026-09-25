package com.mirror.cast.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题：走 **Apple 系统色 + Inset Grouped 卡片** 那一路。
 *
 * 为什么不用 Material 默认色：Material3 的 `surfaceVariant` 是灰紫调，
 * 配上大按钮会显得"很安卓、很重"。iOS 的做法是
 * **浅灰页面底 + 纯白分组卡片 + 系统蓝强调色 + 细分割线**，视觉上轻很多。
 *
 * 色值取自 iOS 系统色板（Light / Dark 各一套）。
 */
private val IOSBlue = Color(0xFF007AFF)
private val IOSBlueDark = Color(0xFF0A84FF)
private val IOSGreen = Color(0xFF34C759)
private val IOSOrange = Color(0xFFFF9500)
private val IOSPurple = Color(0xFFAF52DE)
private val IOSRed = Color(0xFFFF3B30)

/** 分组图标块的配色（iOS 设置里每个分组一个颜色）。 */
object IconTints {
    val blue = IOSBlue
    val green = IOSGreen
    val orange = IOSOrange
    val purple = IOSPurple
    val red = IOSRed
}

private val LightScheme = lightColorScheme(
    primary = IOSBlue,
    onPrimary = Color.White,
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF8A8A8E),
    outline = Color(0xFFE5E5EA),
    error = IOSRed,
)

private val DarkScheme = darkColorScheme(
    primary = IOSBlueDark,
    onPrimary = Color.White,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF38383A),
    error = Color(0xFFFF453A),
)

@Composable
fun MirrorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}

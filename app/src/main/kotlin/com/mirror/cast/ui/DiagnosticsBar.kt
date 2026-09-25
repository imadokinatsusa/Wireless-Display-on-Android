package com.mirror.cast.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily

/**
 * 诊断行：真机没有 adb，这一行是唯一的现场信息出口。
 *
 * 因此它的规矩是：**任何异常都必须出现在这里**，不许静默。
 */
@Composable
fun DiagnosticsBar(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

package com.mirror.cast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 首页：大标题 + 一张分组卡片（两个角色）。照 iOS 的 Inset Grouped 做。
 */
@Composable
fun HomeScreen(
    onSender: () -> Unit,
    onReceiver: () -> Unit,
    lastCrash: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(52.dp))
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                text = "Mirror",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "局域网投屏 · 无需服务器",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(22.dp))

        SettingsGroup {
            SettingsRow(
                icon = Icons.Filled.ScreenShare,
                iconTint = IconTints.blue,
                title = "发送屏幕",
                subtitle = "投出本机画面与声音",
                onClick = onSender,
            )
            SettingsRow(
                icon = Icons.Filled.Tv,
                iconTint = IconTints.green,
                title = "接收显示",
                subtitle = "接收另一台设备",
                showDivider = false,
                onClick = onReceiver,
            )
        }

        if (lastCrash != null) {
            Spacer(modifier = Modifier.height(20.dp))
            SettingsGroup("上次崩溃") {
                Text(
                    text = lastCrash.take(1200),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

/**
 * 顶部返回条（旧签名保留，供其他界面复用）。
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MirrorTopBar(title = title, onBack = onBack, modifier = modifier)
}

/** 大字展示连接码：主人要抬头念给另一台设备，所以必须够大、够清楚。 */
@Composable
fun ConnectCodeDisplay(
    code: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    Text(
        text = code,
        modifier = modifier,
        style = MaterialTheme.typography.displaySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = color,
    )
}

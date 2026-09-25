package com.mirror.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 首页：选角色。M1 只有两个入口，不做任何多余的东西。 */
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
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Mirror", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "局域网投屏：一台设备发送屏幕，另一台接收显示。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onSender, modifier = Modifier.fillMaxWidth()) {
            Text("我要发送屏幕")
        }
        Button(onClick = onReceiver, modifier = Modifier.fillMaxWidth()) {
            Text("我要接收显示")
        }
        if (lastCrash != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "上次崩溃：", style = MaterialTheme.typography.titleSmall)
            Text(
                text = lastCrash,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** 顶部返回 + 标题的通用条，避免每个界面各写一套。 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Button(onClick = onBack, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("← 返回")
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge)
    }
}

/** 大字展示连接码：主人要抬头念给另一台设备，所以必须够大、够清楚。 */
@Composable
fun ConnectCodeDisplay(code: String, modifier: Modifier = Modifier) {
    Text(
        text = code,
        modifier = modifier,
        style = MaterialTheme.typography.displayMedium,
        fontFamily = FontFamily.Monospace,
    )
}

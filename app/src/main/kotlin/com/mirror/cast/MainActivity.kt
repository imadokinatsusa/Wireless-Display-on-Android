package com.mirror.cast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 单 Activity + Compose。
 *
 * 切片 01 阶段这里只是占位：它证明"改包名 + 换模块 + 引入 WebRTC 依赖"之后，
 * APK 仍然能装、能启动。真正的三屏界面在切片 05 落地。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lastCrash = CrashReporter.read(this)
        CrashReporter.clear(this)
        setContent { SkeletonScreen(lastCrash) }
    }
}

@Composable
private fun SkeletonScreen(lastCrash: String?) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Mirror", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "切片 01：骨架就绪（模块已收敛，WebRTC 依赖已接入）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "诊断行：等待切片 03 接入屏幕采集与渲染",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (lastCrash != null) {
                    Text(text = "上次崩溃：", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = lastCrash,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

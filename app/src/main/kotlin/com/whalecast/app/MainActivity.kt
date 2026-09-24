package com.whalecast.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.whalecast.app.ui.WhaleCastApp

/**
 * 单 Activity + Compose。切片 01 的 demo 就展现在这一个界面上：
 * 上半是"发送端"产出的合成画面，下半是经协议层与环回通道后在"接收端"渲染出来的画面。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhaleCastApp()
        }
    }
}

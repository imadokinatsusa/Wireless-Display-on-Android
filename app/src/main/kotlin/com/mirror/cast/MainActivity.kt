package com.mirror.cast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mirror.cast.ui.MirrorApp

/**
 * 单 Activity + Compose。
 *
 * `enableEdgeToEdge()` 让内容铺到状态栏与导航栏之下（全面屏/挖孔屏不会被裁掉一半），
 * 具体留白交给 `Scaffold` 的 `innerPadding` —— 那是唯一可靠的系统栏内边距来源。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val lastCrash = CrashReporter.read(this)
        CrashReporter.clear(this)
        setContent { MirrorApp(lastCrash) }
    }
}

package com.mirror.cast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mirror.cast.ui.MirrorApp

/**
 * 单 Activity + Compose。
 *
 * 界面只做三件事：选角色、选设备、看画面与诊断行。
 * 真正的采集与推流在 [MirrorService] 里，切后台也不会被系统掐掉。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lastCrash = CrashReporter.read(this)
        CrashReporter.clear(this)
        setContent { MirrorApp(lastCrash) }
    }
}

package com.mirror.cast

import android.app.Application
import com.mirror.cast.web.WebRtcRuntime

class MirrorApplication : Application() {

    /** 媒体栈宿主：整个进程一个，避免反复重建工厂与 EGL 上下文。 */
    lateinit var runtime: WebRtcRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        // 越早安装越好：任何后续崩溃都会被记下来，下次启动显示在界面上
        CrashReporter.install(this)
        runtime = WebRtcRuntime(this)
    }
}

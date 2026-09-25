package com.mirror.cast

import android.app.Application

class MirrorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 越早安装越好：任何后续崩溃都会被记下来，下次启动显示在界面上
        CrashReporter.install(this)
    }
}

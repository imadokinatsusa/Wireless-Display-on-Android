package com.whalecast.app

import android.app.Application

class WhaleCastApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 越早安装越好：任何后续崩溃都会被记下来
        CrashReporter.install(this)
    }
}

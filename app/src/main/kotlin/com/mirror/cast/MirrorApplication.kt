package com.mirror.cast

import android.app.Application
import com.mirror.cast.p2p.WifiP2pLink
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
        // 收拾上次可能留下的残局：进程被强杀时 onDispose 没机会跑，
        // Wi-Fi Direct 组会留在系统里继续占着射频（网速变慢、别的投屏用不了）
        runCatching { WifiP2pLink(this).cleanupStaleGroup() }
    }
}

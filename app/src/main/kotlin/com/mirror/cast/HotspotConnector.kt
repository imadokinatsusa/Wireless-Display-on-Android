package com.mirror.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper

/**
 * 连接接收端开出来的热点。
 *
 * **为什么需要它**：两台设备连一个共同网络都没有时，接收端会自己开一个本地热点，
 * 而发送端必须**先连上它**才谈得上投屏。
 *
 * 用 `WifiNetworkSpecifier` 而不是让主人去设置里手动连：
 * - 热点名和密码是从**二维码**里带过来的，主人不用认、不用输；
 * - 系统会弹一次"连接该网络"的确认框，点允许即可。
 *
 * 连上之后还要 `bindProcessToNetwork` —— 让本进程的流量走这条链路，
 * 否则信令那条 TCP 还是走原来的默认网络，够不到 `192.168.43.1`。
 */
object HotspotConnector {

    private var registered: ConnectivityManager.NetworkCallback? = null

    /**
     * 请求连接 [ssid]。结果通过回调告知：成功就把本进程绑到这条链路上。
     *
     * 重复调用安全：会先把上一次的请求撤掉。
     */
    fun connect(
        context: Context,
        ssid: String,
        password: String,
        onConnected: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            onFailed("系统没有 ConnectivityManager")
            return
        }
        disconnect(context)

        val specifier = runCatching {
            WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                // 系统热点默认就是 WPA2；密码短于 8 位时这里会抛，交给下面兜住
                .setWpa2Passphrase(password)
                .build()
        }.getOrElse { error ->
            onFailed("热点信息不可用：${error.message}")
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // 本地热点不通外网，别让系统因为"没有互联网"而嫌弃它
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { manager.bindProcessToNetwork(network) }
                onConnected()
            }

            override fun onUnavailable() {
                onFailed("连接热点超时或被拒绝")
            }
        }
        registered = callback
        // 回调必须回到**主线程**：连上之后要拉录屏授权，那是 Activity 操作 ——
        // 在默认的 Binder 线程上做这件事会被系统直接拒掉。
        // （`requestNetwork` 只提供 Handler 版重载，没有 Executor 版。）
        runCatching { manager.requestNetwork(request, callback, Handler(Looper.getMainLooper())) }
            .onFailure { onFailed("发起连接失败：${it.message}") }
    }

    /** 撤销请求并把流量还给系统默认网络。 */
    fun disconnect(context: Context) {
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java)
        registered?.let { callback -> runCatching { manager?.unregisterNetworkCallback(callback) } }
        registered = null
        runCatching { manager?.bindProcessToNetwork(null) }
    }
}
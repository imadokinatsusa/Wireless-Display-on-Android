package com.mirror.cast

import android.content.Context
import android.net.wifi.WifiManager

/**
 * 获取 Wi-Fi 组播锁。
 *
 * **为什么必须有它**：Android 的 Wi-Fi 芯片为了省电，默认会**过滤掉广播与组播包**。
 * 不持锁时应用只能收到发给自己 IP 的单播 —— 于是"接收端广播了连接码、
 * 发送端却一台都搜不到"。这是局域网发现类功能的头号真机坑。
 *
 * 返回 null 表示拿不到锁（没有 Wi-Fi 等），此时功能退化为"手动输码 / 手填 IP"。
 */
fun acquireMulticastLock(context: Context, tag: String = "mirror-discovery"): WifiManager.MulticastLock? =
    runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock(tag)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

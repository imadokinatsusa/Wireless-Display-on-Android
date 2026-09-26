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

/**
 * 低延迟 Wi-Fi 锁：投屏期间**不让射频打盹**。
 *
 * **为什么必须有它**：Wi-Fi 芯片为了省电会周期性休眠（PSM），醒来要等 beacon / DTIM
 * 周期。在"持续小包 + 强实时"的投屏负载上，它的表现就是往返延迟从几毫秒飙到
 * **一两百毫秒**、丢包随之上升 —— 而媒体栈的拥塞控制看到这些数字只会做一件事：
 * **降码率**。于是画面又糊又卡，可链路上其实什么都没坏。
 *
 * 档位用 [WifiManager.WIFI_MODE_FULL_LOW_LATENCY]（Android 10 起）——
 * 它就是系统为实时音视频、串流这类场景准备的；`minSdk` 正好是 29，可以直接用。
 *
 * **幂等**：关掉引用计数并自己记状态，`acquire` / `release` 重复调用都安全 ——
 * 会话收尾可能被触发多次，多释放一次不该把别人的锁也吞掉。
 */
class WifiLowLatencyLock(context: Context) {

    private val manager: WifiManager? = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var lock: WifiManager.WifiLock? = null

    fun acquire() {
        val wifi = manager ?: return
        if (lock != null) return
        lock = runCatching {
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    fun release() {
        val current = lock ?: return
        lock = null
        runCatching { current.release() }
    }

    private companion object {
        const val LOCK_TAG = "mirror-cast-low-latency"
    }
}

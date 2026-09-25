# 0001 - 用 libwebrtc 替代自研传输协议

- 状态：已接受
- 日期：2026-09-25
- 相关：`docs/spec.md`、`docs/archive/REWRITE-PLAN.md`（已归档）

## 背景

上一轮实现自创了一套跨端协议：32 字节包头、编码帧切包、接收端重组、独立配置包，跑在自研 TCP 传输之上。
真机上连续踩坑（UDP 广播收不到、Beacon 长度算错、前台服务时序竞争、虚拟屏尺寸、异常静默），
而更根本的问题是：**传输可靠性、重传、抖动缓冲、拥塞控制、音画同步这些层，成熟实现早已解决**。
M1 还要求"含音频 + A-V 偏差 ≤100ms"，自己写同步是最大风险源。

## 决策

引入现成 libwebrtc AAR（`io.getstream:stream-webrtc-android:1.3.10`）作为**传输与媒体栈**：

- 加密（DTLS-SRTP）、重传/NACK、FEC、拥塞控制、抖动缓冲、音画同步 → 库负责；
- 我们只写：屏幕/声音**采集适配**、信令交换、渲染、UI 与诊断；
- 不再保留任何自研包头/切包/重组代码。

## 备选方案

| 方案 | 为什么不选 |
| --- | --- |
| 自研 12 字节头 + TCP（`docs/archive/REWRITE-PLAN.md` 原方案） | 传输可靠但无拥塞控制与音画同步；音频要自研同步与抖动缓冲 |
| UDP 分片 + Reed-Solomon FEC（本会话前期的方案） | 完全自创，约 1200~1500 行；真机无 adb，调试成本极高 |
| LiveKit Android SDK | 必须有 LiveKit 服务器（Cloud 或自托管），违背"两台手机直连" |
| SRT / MPEG-TS / GStreamer / webrtc-rs | 无官方 Android 绑定或需自写 JNI —— 等于自写传输栈 |

## 后果

**正面**：不再维护协议代码；音视频同步、重传、拥塞控制、加密免费获得；`getStats()` 提供完整可观测性。

**代价**：

- APK 增大约 17MB 原生库（arm64 + armeabi-v7a 两个 ABI）——已实测 AAR 内容确认；
- 必须遵守库的线程模型（工厂与 PeerConnection 建在 signaling thread，单线程 Executor 串行化）；
- 需要自己写信令（见 ADR 0002）；
- 放弃已跑通的 12 字节头与 MediaCodec 硬编代码（采集仍走 MediaProjection，但编码交由库）。

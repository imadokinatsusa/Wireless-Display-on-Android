# Mirror —— 规格（M1：真机镜像打通，含音频）

> 本文是 M1 的**唯一来源规格**。旧规格（32 字节包头 + 切包/重组 + 自研 TCP 传输）已归档到
> `docs/archive/android-cast/`，其结论已被 `docs/adr/0001` 取代。

## 一、问题

上一轮实现自创了一套传输协议（32 字节包头、切包/重组、独立配置包），在真机上连续踩坑：
UDP 广播收不到、Beacon 长度算错、前台服务时序竞争、虚拟屏尺寸不对、异常静默无现场信息。
根因不是"写得不够仔细"，而是**自创了成熟实现早已解决的层**。

## 二、方案（一句话）

**凡是成熟实现已经解决的层，一律复用；我们只写适配与产品逻辑。**

- 传输、加密、重传、FEC/NACK、拥塞控制、抖动缓冲、音画同步 → **libwebrtc**（现成 AAR）
- 屏幕采集 → libwebrtc 自带的 `ScreenCapturerAndroid`（内部走 MediaProjection 虚拟屏）
- 声音内录 → Android `AudioPlaybackCapture`，接入 libwebrtc 音频轨（细节见 §六 待定项 A）
- 设备发现 → 复用已修好的 UDP Beacon + 连接码（`core:discovery`，本地测试已通过）
- 信令 → 一条**临时 TCP** 交换会话描述与网络候选（无服务器、无 STUN/TURN）
- 渲染 → libwebrtc `SurfaceViewRenderer` + 本地视图变换（双指/比例缩放）

## 三、目标 / 非目标

**M1 目标**：两台安卓手机，一台发送、一台接收，在同一个 Wi-Fi 下看到画面并听到声音。

**验收标准**（客观、可在界面读到）：

| 项 | 标准 |
| --- | --- |
| 分辨率/帧率 | 1080p 级 @30fps（采集用屏幕真实尺寸，奇数抹偶） |
| 端到端延迟 | ≤150ms（由 RTP 时戳 + 媒体栈统计推算，显示在诊断行） |
| 音画偏差 | ≤100ms |
| 稳定性 | 连续 10 分钟不崩、延迟不持续增长 |
| 音频 | **做不通不发版**（失败即不发 APK，不做"静音版"发布） |

**非目标（M1 明确不做）**：ROI 高清放大、长期设备身份/免 PIN 重连、反向控制、录制截图、
一推多、公网/中继、Miracast/DLNA/AirPlay 兼容、H.265/4K/HDR、root 相关能力。

## 四、实现决策

| # | 决策 | 依据 |
| --- | --- | --- |
| D1 | 用 `io.getstream:stream-webrtc-android:1.3.10` 作为传输与媒体栈 | `docs/adr/0001` |
| D2 | 无服务器 P2P：`iceServers` 空列表 + 仅本机候选；信令走临时 TCP | `docs/adr/0002` |
| D3 | 采集用库自带 `ScreenCapturerAndroid(Intent, MediaProjection.Callback)` | AppRTC 官方示例同款；回调必填 |
| D4 | 先 `startForeground(type=mediaProjection)` 成功，再 `getMediaProjection` / 建采集 | AOSP：要求自 Android 10 / targetSdk≥29 起成立；Android 14 另需 `registerCallback` |
| D5 | 采集分辨率 = 屏幕真实尺寸（仅抹奇数） | 上一轮真机结论：Android 14 单应用共享对尺寸敏感 |
| D6 | 接收端等比完整显示 + 黑边；缩放是**本地** `ViewTransform`（双指/比例） | M1 不引入发送端取样 |
| D7 | 延迟与质量数字全部取自媒体栈 `getStats()` + RTP 时戳 | 不自造时钟 |
| D8 | 分辨冲突时以 receiver 的显示能力为准（能力协商交给 SDP） | 减少自研协商层 |
| D9 | 只打包 `arm64-v8a` + `armeabi-v7a` | AAR 验尸确认两个 ABI 均在；体积可控 |
| D10 | 诊断行是唯一现场信息出口，异常不得静默 | 无 adb 真机现实 |

**已纠正的上一轮错误**：`docs/archive/REWRITE-PLAN.md` 称"Android 14 起必须有 mediaProjection 前台服务"。
实际：该要求自 **Android 10 / targetSdk ≥ 29** 起成立（`requiresForegroundService()` = `mTargetSdkVersion >= Q`），
且异常在 `getMediaProjection()` 时就抛。Android 14 真正新增的是 `registerCallback` 必填
（抛 `IllegalStateException`，不是 `SecurityException`）与 consent token 不可复用。

## 五、测试决策

| 层 | 怎么测 |
| --- | --- |
| `core:discovery` | 已有：连接码编解码、Beacon 往返、非法报文拒绝、真实 UDP 环回 |
| `core:signal` | 新增：会话描述/候选的编解码往返、连接码校验（不匹配拒绝）、超时与重试、断线收尾；**纯 JVM，可本地跑** |
| 媒体栈胶水 | 以"可观察行为"为准：状态机（未授权/连接中/投屏中/断开）与诊断行文本 |
| 真机验收 | 两台手机 + 界面诊断行数字（延迟、帧率、丢包、A-V 偏差） |

纪律：时间与并发走可注入的虚拟时钟，禁止真实 `sleep`；优先手写 Fake；不 mock 媒体栈。
**本地验证路径**：`C:\mirror-verify`（源码副本）+ `C:\mirror-tc`（JDK/Gradle），
命令 `gradle -p C:\mirror-verify -c local.settings.gradle.kts :core:signal:test`。

## 六、风险与待定项

**风险**

| 风险 | 应对 |
| --- | --- |
| WebRTC 线程模型（工厂与 PeerConnection 必须建在 signaling thread） | 单线程 Executor 串行化全部 PeerConnection API（官方示例做法） |
| `SurfaceViewRenderer` 的 GL 生命周期（`release()` 时机、Compose 中 `ViewRootImpl` 线程问题） | 严格按官方示例：`init` → 渲染 → 退出前 `release()`；必要时 `post { requestLayout() }` |
| 首次接入 AAR 的构建风险（依赖解析、ABI、R8） | 先出"最小可构建切片"（01）让 CI 绿，再填功能 |
| 音频内录在某些应用上天然静音 | 界面上明确说明"该应用不允许被捕获"（不是静默失败） |
| 无 adb，问题定位慢 | 诊断行 + CI 日志；每个切片都要求"异常可见" |

**待定项**

- **A. 音频注入 libwebrtc 的具体路径**：`JavaAudioDeviceModule` 内部自建 `AudioRecord`，
  无法直接注入 `AudioPlaybackCaptureConfiguration` 的 `AudioRecord`。候选路线：
  (a) 自定义 `AudioDeviceModule` 包装；(b) 库内是否已有 `CustomAudioSource` 一类公开 API。
  → 由技术侦察定稿后写入本规格与对应工单；**这条不通，M1 不发版**（见验收标准）。
- **B. `arm64-v8a + armeabi-v7a` 下 APK 体积实测**（预估 25~35MB），若过大再回退单 ABI。
- **C. 会话描述长度与 TCP 信令超时参数**（实测后固化）。

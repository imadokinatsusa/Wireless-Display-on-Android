# CONTEXT —— 领域词汇表

项目：**WhaleCast（暂定名，可改）** —— Android ↔ Android 局域网双向投屏。
一个 APK，两个角色（发送端 / 接收端），自建协议 + MediaCodec 硬件编解码。

> 本文件只放词汇与关系。设计决策写在规格与 ADR 里，进度写在 issue 里。

## 角色与设备

| 词汇 | 含义 |
| --- | --- |
| **Sender（发送端）** | 采集本机屏幕与系统音频、编码、推流的角色。典型载体：手机。 |
| **Receiver（接收端）** | 接收流、解码、渲染、并可选地回传输入事件的角色。典型载体：平板 / 电视盒子。 |
| **Role（角色）** | 一个进程在当前会话中扮演的身份；同一 APK 可切换，一台设备可先后担任两种角色。 |
| **DeviceIdentity（设备身份）** | 本机长期身份：设备名 + 长期公钥 + 设备 ID。首次启动生成并持久化。 |
| **PairedDevice（已配对设备）** | 与本地完成过配对、被记住并可直接重连的 `DeviceIdentity`。 |
| **Session（会话）** | 一次投屏连接的完整生命周期：握手 → 传输 → 收尾。含 1 条控制通道 + 0..n 条媒体通道。 |
| **SessionId** | 一次会话的标识。为一推多 / 多收一留的架构余地，v1 只有一对一。 |

## 通道与协议

| 词汇 | 含义 |
| --- | --- |
| **Transport（传输层）** | 唯一跨端接缝：提供「消息发送 / 消息回调 / 连接生命周期」的抽象。所有跨端逻辑只能穿过它，不直接碰 Socket。 |
| **ReliableChannel（可靠通道）** | 有序可靠的字节/消息流，承载握手、控制、关键帧请求。v1 实现走 TCP。 |
| **MediaChannel（媒体通道）** | 承载视频/音频包，允许丢包、延迟优先。v1 后期实现走 UDP；v1 初期复用可靠通道以先跑通。 |
| **ControlChannel（控制通道）** | 双向控制面：Receiver → Sender 的输入事件、Sender → Receiver 的状态与关键帧请求。 |
| **Handshake（握手）** | 会话开场的协商：协议版本、能力（分辨率/帧率/编解码/音频/控制）、加密参数、SessionId。 |
| **CapabilitySet（能力集）** | 一方声明支持的分辨率、帧率、编码档位、采样率等，握手时取交集。 |
| **VideoPacket（视频包）** | 一个编码帧被切分后的传输单元：SessionId + 帧序号 + 包序号 + 包总数 + 标志位 + 时间戳 + 负载。 |
| **KeyframeRequest（关键帧请求）** | Receiver 发现丢包无法恢复时，要求 Sender 立刻编一个 I 帧的控制消息。 |
| **SessionStats（会话统计）** | 运行时可观测指标：端到端延迟、丢包率、当前码率、帧率、解码耗时。驱动 UI 与码率策略。 |

## 媒体管线

| 词汇 | 含义 |
| --- | --- |
| **VideoSource（视频源接缝）** | 产出待编码帧的边界。生产实现 = MediaProjection 虚拟屏；测试实现 = 合成画面（如跳动方块/时间戳图案）。 |
| **VideoEncoder / VideoDecoder** | MediaCodec 硬编硬解的封装。编码输入走 Surface（零拷贝），解码输出走 Surface。 |
| **VideoPacketizer / VideoReassembler** | 编码帧 ↔ `VideoPacket` 的切分与重组。纯逻辑，必须能在 JVM 单测里跑。 |
| **VideoSink（视频汇接缝）** | 消费解码后画面的边界。生产实现 = SurfaceView/TextureView；测试实现 = 记录帧元数据（序号、时间戳、完整性）的假汇。 |
| **AudioSource / AudioSink** | 系统音频内录（AudioPlaybackCapture）与接收端播放的边界。 |
| **MediaClock（媒体时钟）** | 音视频统一的时间基准：把采集时的 `nanoTime` 映射为媒体时间戳（90kHz），供 A/V 同步与抖动缓冲使用。 |
| **JitterBuffer（抖动缓冲）** | Receiver 侧按时间戳重排、平滑网络抖动的缓冲，有明确的目标延迟预算。 |
| **LatencyBudget（延迟预算）** | 端到端目标延迟的分解额度：采集 + 编码 + 网络 + 抖动缓冲 + 解码 + 显示。改造任何一环都要先看它。 |
| **NativeCapture（原生采集）** | 虚拟屏以屏幕物理分辨率采集，**编码前**才降采样或裁剪。它是高清放大的前提：全屏模式可以省带宽，ROI 模式才能拿到真实像素。 |
| **ViewTransform（视图变换）** | 接收端画面的缩放倍率与平移偏移。它是 ROI 计算与触摸坐标逆变换的**唯一来源**，必须是可观察状态，不能藏在 View 内部。 |
| **ROI（关注区域，Region of Interest）** | 源屏上被放大的归一化矩形，由 `ViewTransform` 推导而来。 |
| **RoiRequest（关注区域请求）** | 接收端 → 发送端的控制消息：归一化取样矩形 + 目标档位（`SameResolution` 零重配 / `Native1to1` 需冻结）+ 请求序号。 |
| **SamplingRect（取样矩形）** | 发送端编码前从源纹理取的归一化矩形。整屏 = 全屏观看，局部 = ROI 放大。改变它只是 GPU 采样参数变化，**不重配编码器**。 |
| **EncodeResolution（编码尺寸档）** | 编码输出的固定像素尺寸，全程不变（16 倍数对齐，通常取源屏的一半）。所有取样矩形都被映射到它。改它才叫重配。 |
| **Freeze（冻结画面）** | 接收端本地保留当前帧继续显示的状态。它是**唯一允许重配编码尺寸的安全窗口**，用于 `Native1to1` 档放大。 |

## 控制与质量

| 词汇 | 含义 |
| --- | --- |
| **InputEvent（输入事件）** | 由 Receiver 回传的触摸/按键/文本事件，带坐标归一化与时间戳。 |
| **InputInjector（注入器）** | Sender 侧把 `InputEvent` 变成真实系统输入的适配器，生产实现走 AccessibilityService。 |
| **BitratePolicy（码率策略）** | 依据 `SessionStats` 决定目标码率、分辨率、帧率的决策逻辑。纯逻辑，可单测。 |
| **PairingGrant（配对凭证）** | 配对成功后双方的共享密钥材料与信任关系，支撑后续免 PIN 重连。 |
| **TrustedReconnect（可信重连）** | 已配对设备在不重新输入 PIN 的情况下建立会话的路径。 |

## 关系

```
Sender ──(Session)──▶ Receiver
  │                      │
  │ MediaChannel ───────▶│   视频/音频：单向，丢包容忍，延迟优先
  │◀────── ControlChannel │   输入事件回传、关键帧请求、状态上报：双向，可靠
  │                      │
VideoSource→Encoder→Packetizer→[Transport]→Reassembler→Decoder→VideoSink
AudioSource→Encoder→Packetizer→[Transport]→JitterBuffer→AudioSink
                                     │
                        BitratePolicy ◀── SessionStats
```

- 仅有 `Transport` 一个跨端接缝；媒体另有 `VideoSource` / `VideoSink` 两个端内接缝。
- 端内逻辑（切包、重组、码率决策、握手状态机、加密封装）**不得依赖 Android SDK**，以便 JVM 单测。
- 采集、编解码、注入、渲染是藏在接缝背后的**适配器**，是唯一允许碰 Android API 的地方。

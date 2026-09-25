# 01: 环回垂直切片 —— 合成画面从"发送端"流到"接收端"

**What to build:** 一个能跑起来的骨架，端到端演示价值为：同一 APK 里，发送端角色把一块**合成画面**（比如随时间移动的色块 + 帧号）经由 `Transport` 送到接收端角色并渲染出来。传输用进程内环回实现，不碰网络、不碰 MediaCodec。同时立起工程规范（模块划分、测试先例、`CONTEXT.md` 词汇落地）。
**Blocked by:** 无（可立即开始）
**Status:** ready-for-agent

这张工单是全项目的 tracer bullet：它建立了后续所有切片要照抄的结构与测试先例。

- [ ] 单 Gradle 工程建立，模块 `:app` / `:core:protocol` / `:core:transport` / `:core:media` / `:core:control` / `:core:discovery` 存在，依赖方向单向（`:app` → `:core:*`，只有 `:core:protocol` 被依赖且不依赖任何 Android API）
- [ ] `:core:protocol` 是纯 Kotlin/JVM 模块，单测可 `./gradlew :core:protocol:test` 直接跑通，无需 Android SDK
- [ ] 定义 `Transport` 接缝：建立连接、发送消息、接收消息回调、连接状态回调（面向上层，不泄漏 Socket/线程细节）
- [ ] 实现 `LoopbackTransport`：同进程内两个端可互发消息，带可控延迟/丢包注入开关（供后续测试用）
- [ ] 定义 `VideoSource` / `VideoSink` 接缝与假实现：`SyntheticVideoSource` 产出自增帧号的合成帧，`RecordingVideoSink` 记录收到的帧序号与时间戳
- [ ] 定义帧消息的包格式与切包/重组纯逻辑：帧序号、包序号、包总数、I 帧标志、时间戳、负载；截断包与乱序包有明确处理
- [ ] Compose 最小 UI：角色选择（发送端/接收端）+ 一个"开始"按钮 + 显示收到的帧号与累计帧数的接收画面区域
- [ ] 手工验收：一台设备上切到发送端开始，切到接收端能看到帧号递增、画面动起来
- [ ] 单测先例建立并被后续照抄：包编解码往返测试、非法包拒绝测试、环回会话集成测试（用可控时钟推动，禁止 `Thread.sleep`）
- [ ] `CONTEXT.md` 中的词汇与代码命名一致（Sender/Receiver/Session/Transport/VideoSource/VideoSink 等）

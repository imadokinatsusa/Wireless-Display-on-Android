# WhaleCast

**Android ↔ Android 局域网双向投屏** —— 单 APK、双角色、自建协议 + MediaCodec 硬件编解码。

> 当前进度：**切片 01（环回 demo）**。
> 真机屏幕采集、局域网传输、配对加密、反向控制等属于后续切片，规划见 [`.scratch/android-cast/`](.scratch/android-cast/)。

---

## 切片 01 是什么

一条**贯穿所有层的垂直切片**：合成画面 → 切成带包头的包 → 走 `Transport` 环回通道 → 接收端重组 → 解码 → 渲染。

它刻意不碰 `MediaProjection` / `MediaCodec`，却能验证整条管线与所有接缝的形状 —— 这是后续每个切片都会照抄的结构。

**在手机上看到的效果**：上半屏是"发送端"产出的彩色画面，下半屏是经过协议层后"接收端"渲染出的画面；两块颜色同步流动。拖动**丢包率**滑块，能看到接收端丢帧计数上升而画面不脏；拖动**延迟**滑块，能看到画面滞后但不丢帧。

---

## 模块结构

```
app                     Compose 双角色 UI（切片 01 的环回 demo 界面）
core/protocol           包头格式、切包、重组、协议常量        ← 零 Android 依赖
core/transport          Transport 接缝 + 进程内环回实现        ← 零 Android 依赖
core/media              VideoSource/VideoSink/编解码接缝 + 合成源/记录汇
core/session            发送管线 + 接收管线 + SessionStats     ← 端到端验收所在地
core/control            控制通道（工单 11）
core/discovery          设备发现（工单 09）
```

依赖方向单向：`app → core:*`；`core:protocol` 不依赖任何人。

**唯一跨端接缝是 `Transport`**，媒体另有 `VideoSource` / `VideoSink` 两个端内接缝。端内逻辑（切包、重组、码率决策、握手状态机、加密封装）都不依赖 Android SDK，因此能在 JVM 上直接单测。

---

## 怎么拿到 APK（推荐）

推送到 GitHub 后，Actions 会自动跑测试并构建 debug APK：

1. 打开仓库的 **Actions** 标签页，确认 `Android CI` 通过；
2. 打开 **Releases** 页面，下载 `app-debug.apk`（公开仓库可直接用手机浏览器下载）；
3. 手机上允许"安装未知来源应用"后安装。

推送方式（本机无需 git，只要有 Node）：

```bash
node tools/push-to-github.mjs --repo <你的账号>/<仓库名> --token <你的 PAT>
```

- token 需要 `repo` + `workflow` 权限（推送 `.github/workflows/` 必须带 workflow 权限）；
- 仓库还没建？加 `--create` 让脚本创建公开仓库；
- 脚本会自己等待构建结束：成功就给出 Release 链接，失败会打印失败步骤与日志末尾。

---

## 怎么在本机构建

用 Android Studio 直接打开仓库根目录即可（IDE 会自行生成 Gradle wrapper）。

命令行方式（需要 JDK 17 + Gradle 8.11+ + Android SDK 35）：

```bash
gradle :core:protocol:test :core:transport:test :core:media:test :core:session:test
gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 测试策略

| 模块 | 测什么 |
| --- | --- |
| `core:protocol` | 包头往返、非法/截断包拒绝、乱序与重复、缺包丢弃、内存守护、90kHz 时间戳换算 |
| `core:transport` | 双向收发、未连接失败语义、延迟注入、丢包注入、seed 可复现 |
| `core:media` | 合成颜色的纯函数性、帧率与时间戳递增、直通编解码一致性、关键帧节奏 |
| `core:session` | **端到端**：合成画面经环回后完整重组并渲染；丢包时丢帧而非脏帧；畸形包只计数不中断 |

纪律：时间与并发全部走可注入的虚拟时钟（`runTest`），**禁止 `Thread.sleep`**；断言写在可观察行为上；优先手写 Fake 而不是 mock 框架。

---

## 规划文档

- [`CONTEXT.md`](CONTEXT.md) —— 领域词汇表（Sender/Receiver/Session/Transport/VideoSource/…）
- [`.scratch/android-cast/spec.md`](.scratch/android-cast/spec.md) —— 规格：问题、方案、用户故事、实现决策、测试决策、范围外、风险
- [`.scratch/android-cast/issues/`](.scratch/android-cast/issues/) —— 16 张垂直切片工单 + [索引与依赖图](.scratch/android-cast/issues/00-INDEX.md)

路线：**M1 打通**（01→02→03→04/05→06 真机看到手机屏幕）→ **M2 能用**（07/08/09/10/15 不累积延迟、有声音、自动发现、加密配对、放大看细节不糊）→ **M3 好用**（11/12/13/14 反向控制、自适应码率、本地录制、体验收尾）。

---

## 已知限制（切片 01）

- 投的是**合成画面**，不是真屏幕（真采集在工单 04）；
- 传输走**进程内环回**，不是局域网（TCP 在工单 02）；
- 没有握手、没有加密、没有配对（工单 03/10）；
- 画面是 1×1 的合成像素，仅用于验证链路与渲染路径。

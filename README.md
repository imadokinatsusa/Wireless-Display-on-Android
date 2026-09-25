# Mirror

**Android ↔ Android 局域网投屏** —— 单 APK、双角色；传输与媒体栈复用现成 **WebRTC**，不自创协议。

> 为什么不再自研：上一轮实现自创了 32 字节包头 + 切包/重组 + 自研 TCP 传输，
> 真机连续踩坑。结论写进了 [`docs/adr/0001`](docs/adr/0001-use-libwebrtc-instead-of-custom-protocol.md)：
> **凡是成熟实现已经解决的层（加密、重传、FEC/NACK、拥塞控制、抖动缓冲、音画同步），一律复用。**

---

## 当前进度

**M1（真机镜像打通，含音频）进行中**，切片 01「骨架切换」已完成：工程更名为 `mirror`、
模块收敛为三个、引入 `io.getstream:stream-webrtc-android`、旧协议代码全部删除。
后续切片见 [`docs/issues/`](docs/issues/)。

## M1 验收标准

| 项 | 标准 |
| --- | --- |
| 分辨率/帧率 | 1080p 级 @30fps（采集用屏幕真实尺寸，奇数抹偶） |
| 端到端延迟 | ≤150ms（由 RTP 时戳 + 媒体栈统计推算，显示在诊断行） |
| 音画偏差 | ≤100ms |
| 稳定性 | 连续 10 分钟不崩、延迟不持续增长 |
| 音频 | 做不通不发版 |

## 模块结构

```
app              Compose 界面、前台服务、WebRTC 胶水（唯一碰 Android 平台 API 的地方）
core/discovery   UDP 广播发现 + 6 位连接码 + Beacon（纯 JVM，可本地单测）
core/signal      信令消息编解码与信道（纯 JVM，可本地单测）
```

依赖方向单向：`app → core:*`。纯 JVM 模块不依赖 Android SDK，因此能在没有 Android SDK 的机器上直接跑测试。

## 怎么拿到 APK（推荐）

推送到 GitHub 后 Actions 会自动跑测试并构建 debug APK：

1. 打开仓库 **Actions** 标签页，确认 `Android CI` 通过；
2. 打开 **Releases** 页面，下载 `app-debug.apk`（公开仓库可直接用手机浏览器下载）；
3. 手机上允许"安装未知来源应用"后安装。

推送方式（本机无需 git，只要有 Node）：

```bash
node tools/push-to-github.mjs --repo imadokinatsusa/Wireless-Display-on-Android --no-watch
```

- token 走 `GITHUB_TOKEN` 环境变量（用完请撤销）；
- 查构建：`node tools/ci-log.mjs --repo imadokinatsusa/Wireless-Display-on-Android --key-only`。

## 怎么在本机跑测试

**注意：项目路径含中文会让 JDK 路径解析失败**，所以先把源码同步到 ASCII 路径再跑：

```powershell
# 1) 同步源码（不含构建产物）
Copy-Item <项目>\app,<项目>\core,<项目>\gradle -Destination C:\mirror-verify -Recurse -Force
Copy-Item <项目>\*.gradle.kts -Destination C:\mirror-verify -Force

# 2) 用 .toolchain 里的 JDK + Gradle 跑纯 JVM 测试
$env:JAVA_HOME = "C:\mirror-tc\jdk-17.0.2"
$env:GRADLE_USER_HOME = "C:\mirror-tc\gradle-home"
& "C:\mirror-tc\gradle-8.11.1\bin\gradle.bat" -p C:\mirror-verify -c local.settings.gradle.kts `
    :core:discovery:test :core:signal:test --no-daemon
```

`-c local.settings.gradle.kts` 只包含纯 JVM 模块，因此**不需要 Android SDK、不连 google() 仓库**。
APK 只能由 CI 构建（本机没有 Android SDK）。

## 文档

- [`CONTEXT.md`](CONTEXT.md) —— 领域词汇表（唯一事实源）
- [`docs/spec.md`](docs/spec.md) —— M1 规格：目标、决策、测试、风险、待定项
- [`docs/issues/`](docs/issues/) —— 6 张垂直切片工单 + 依赖图
- [`docs/adr/`](docs/adr/) —— 架构决策记录（libwebrtc 取代自研协议；无服务器 P2P + 临时 TCP 信令）
- [`.scratch/legacy/`](.scratch/legacy/) —— 旧规格、旧工单与旧源码备份（历史，不再维护）

## 已知限制（M1 范围外）

ROI 高清放大、免 PIN 重连、反向控制、录制截图、一推多、公网/中继、跨网段，
以及 Miracast/DLNA/AirPlay 兼容 —— 全部不在 M1。

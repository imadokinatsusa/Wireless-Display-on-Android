# WhaleCast 重写方案（参考 scrcpy 等开源实现）

> 写这份文档的原因：现有实现自创了一套 32 字节包头 + 切包/重组 + 独立配置包的协议，
> 复杂度远高于成熟方案，且真机上连续踩坑。**重写应照抄成熟做法，不要自创。**

---

## 一、参考来源（成熟实现的做法）

| 来源 | 关键做法 | 我们该照抄什么 |
| --- | --- | --- |
| **scrcpy**（Genymobile） | 编码器 `createInputSurface()` + MediaProjection 虚拟屏；H.264 输出直接按 **12 字节头（pts 8 + size 4）+ 数据** 流式发送；**不切包** | 传输格式与编码配置 |
| **Android 官方 MediaProjection 示例** | `getMediaProjection` → `registerCallback` → `createVirtualDisplay`；虚拟屏尺寸用**屏幕真实尺寸** | 调用顺序与参数 |
| **Android 14 行为变更文档** | targetSdk 34+ 时，`createVirtualDisplay` 之前**必须有 `mediaProjection` 类型的前台服务在运行** | 前台服务的正确姿势 |

### scrcpy 编码器配置（照抄）
```
MediaFormat.createVideoFormat("video/avc", width, height)
  KEY_COLOR_FORMAT        = COLOR_FormatSurface
  KEY_BIT_RATE            = 8_000_000（可按分辨率动态算）
  KEY_FRAME_RATE          = 30（或 60）
  KEY_I_FRAME_INTERVAL    = 2 秒
  KEY_PREPEND_HEADER_TO_SYNC_FRAMES = 1   // ★ 每个关键帧自带 SPS/PPS，接收端随时可起播
  KEY_PROFILE             = AVCProfileBaseline（无 B 帧，低延迟）
```

### 传输格式（照抄，替代现有 32 字节协议）
```
[ 8 字节 presentationTimeUs（大端 long）][ 4 字节 payload 长度][ payload ]
```
- TCP 上**不需要**切包/重组：读 12 字节头 → 读 payload → 喂解码器
- SPS/PPS 靠 `PREPEND_HEADER_TO_SYNC_FRAMES` 随关键帧到达，**不需要独立配置包**
- 连接开始时先发一个**握手/版本字节**（可选）

### 接收端（照抄）
- `MediaCodec.createDecoderByType("video/avc")` → `configure(format, surfaceView.surface, null, 0)`
- **未遇到关键帧之前丢弃所有非关键帧**（否则解码器报错/花屏）
- 丢包或花屏时 → 向发送端请求关键帧（scrcpy 用控制通道发消息）

---

## 二、新架构（比现在简单）

```
app
 ├─ CastService（前台服务，type=mediaProjection）★核心改动
 │    ├─ 持有 MediaProjection / MediaCodec / VirtualDisplay 的完整生命周期
 │    ├─ onStartCommand: startForeground(type=mediaProjection) → 然后再创建虚拟屏
 │    │   （这样根本不存在"服务还没就绪"的时序竞争 —— 这是现有实现最大的坑）
 │    └─ 编码帧 → 12 字节头 → Socket
 └─ UI（Compose）
      ├─ 发送端：自动扫设备列表 → 点击 → startService(附带 host/port)
      └─ 接收端：SurfaceView + 12 字节头读取循环 → MediaCodec 解码

core/discovery（保留，已修好）
 ├─ ConnectCode / Beacon（UDP 广播发现，已通过本地测试）
 └─ BeaconScanner / BeaconBroadcaster
```

**删除**：`core/protocol` 的切包/重组/配置包（保留 `ConnectCode`/`Beacon` 到 discovery）。
**保留**：`core/discovery`、`CastForegroundService`（改造为持有投影的服务）、UI 骨架。

---

## 三、实施步骤（每步都可编译 + 可验证）

1. **新 `CastService`**：把 `ScreenCaptureSource` 的采集逻辑搬进去；
   `onStartCommand` 里 `startForeground(MEDIA_PROJECTION)` **成功之后**再 `createVirtualDisplay`。
   服务通过 `Intent` 接收 host/port，自行连 TCP。
2. **新流格式**：`FrameWriter`（12 字节头）/ `FrameReader`，替换切包/重组。
3. **接收端重写**：`FrameReader` → `MediaCodec` → `SurfaceView`；等关键帧逻辑保留。
4. **UI 对接服务**：发送端点击设备 → `startForegroundService(intent(host, port))`；状态用 `Flow` 回传。
5. **清理**：删除 `core/protocol` 的包格式（或标记 deprecated）、删除 `SenderPipeline`/`ReceiverPipeline`。

---

## 四、务必避开的坑（真机上已经踩过，都是真 bug）

| # | 坑 | 正确做法 |
| --- | --- | --- |
| 1 | **UDP 广播收不到** | Wi-Fi 芯片默认丢弃广播/组播：必须 `WifiManager.MulticastLock`（权限 `CHANGE_WIFI_MULTICAST_STATE`） |
| 2 | **Beacon 报文长度算错** | 固定字段 = magic2 + ver1 + codeLen1 + port2 + tokenLen1 + nameLen1 = **8 字节** |
| 3 | **自己猜空闲端口** | 用 `port = 0` 让系统分配，再读回 `localPort`；并提供 `awaitReady()` |
| 4 | **虚拟屏尺寸** | 必须用**屏幕真实尺寸**（只把奇数抹成偶数）；Android 14 单应用共享对尺寸敏感 |
| 5 | **前台服务时序** | `startForegroundService` 是异步的；**在 Service 内部**先 `startForeground` 再 `createVirtualDisplay`，不要跨组件赌时序 |
| 6 | **Activity 刚从授权页返回时启动 FGS** | 可能被系统视为"后台"而拒绝；等界面恢复（约 600ms）或用服务自身生命周期 |
| 7 | **异常静默** | 真机没有 adb：把异常写进界面状态（已有 `CrashReporter` + 诊断行） |

---

## 五、本地验证（已打通，不必再靠 CI 猜）

本机已装好工具链，且项目路径含中文会让 JDK 路径解析失败 —— **必须复制到 ASCII 路径**：

```powershell
robocopy "C:\Users\Administrator\Desktop\投屏" "C:\whalecast-verify" /E /XD build .gradle .scratch
robocopy "C:\Users\Administrator\Desktop\投屏\.toolchain" "C:\whalecast-tc" /E
$env:JAVA_HOME = "C:\whalecast-tc\jdk-17.0.2"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME = "C:\whalecast-tc\gradle-home"
& "C:\whalecast-tc\gradle-8.11.1\bin\gradle.bat" -p "C:\whalecast-verify" -c local.settings.gradle.kts :core:discovery:test
```
（`local.settings.gradle.kts` 只含纯 JVM 模块，因此不需要 Android SDK、不连 google 仓库。）

**纪律：先本地跑穿纯逻辑测试，再推 CI。** 每次推送都会触发 GitHub Actions：
- 单测 job（`core:*`）+ APK job（`app`）
- APK 自动发布到 Release，tag = `demo-build-<run号>`
- 推送脚本：`node tools/push-to-github.mjs --repo imadokinatsusa/Wireless-Display-on-Android --no-watch`
  （token 走 `GITHUB_TOKEN` 环境变量；用完请撤销）
- 查构建：`node tools/ci-log.mjs --repo ... --key-only`

---

## 六、当前进展快照

- ✅ 局域网 UDP 发现（连接码）：本地测试通过
- ✅ 发送端采集 + 硬编：代码就位，**但被前台服务时序卡住**
- ✅ 接收端硬解 + 渲染：代码就位，未在真机验证
- ⬜ **待解决**：`Media projections require a foreground service of type ...`
  → 按本方案第 1 步（把采集搬进 Service）应可根治
- ⬜ 音频内录与音画同步、反向控制、自适应码率、录制、ROI 高清放大

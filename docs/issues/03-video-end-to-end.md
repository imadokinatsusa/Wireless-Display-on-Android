# 03: 视频端到端 —— 屏幕采集发布 → 接收端渲染

**What to build:** M1 的 tracer bullet。发送端授权后采集屏幕、交给媒体栈发布；接收端通过信令建立会话、订阅视频轨并渲染。两台手机在同一个 Wi-Fi 下，接收端能看到发送端屏幕。
**Blocked by:** 02
**Status:** ready-for-agent

- [ ] `MirrorService`（前台服务）：`onStartCommand` 内**先** `startForeground(type=mediaProjection)` 成功，**再** `getMediaProjection(...)`；失败原因写入界面诊断
- [ ] 采集：库自带 `ScreenCapturerAndroid(Intent, MediaProjection.Callback)`；分辨率取屏幕真实尺寸（奇数抹偶）
- [ ] 发送端：`PeerConnectionFactory` + `VideoSource` + `VideoTrack` → `PeerConnection.addTrack`；采集源接上 `VideoSource`
- [ ] 线程模型：单线程 Executor 串行化全部 PeerConnection API（工厂与 PeerConnection 都建在该线程）
- [ ] 接收端：`PeerConnection` + `SurfaceViewRenderer`，`SCALE_ASPECT_FIT`（等比完整显示 + 黑边）；退出时在主线程、EGL 仍有效时 `release()`
- [ ] 会话状态机：未授权 / 连接中 / 投屏中 / 已断开；每个失败分支都有可显示原因
- [ ] 诊断行（最小版）：状态、已解码帧数、当前帧率
- [ ] 手工验收：两台手机，一台发送（授权屏幕）→ 另一台接收，看到画面；断开后重连仍可用
- [ ] 异常路径可见：拒绝授权、连接码错误、对端中途退出、切后台再回来

---

## 状态更新（2026-09-25）

**代码完成，CI 编译通过**（run #33：JVM 单测 + debug APK 全绿），**真机验证待主人执行**。

只能真机确认的风险点：ICE 直连是否成功、SurfaceViewRenderer 的 GL 生命周期、
首次握手时序、虚拟屏是否被系统接受、系统声音是否真的被捕获。

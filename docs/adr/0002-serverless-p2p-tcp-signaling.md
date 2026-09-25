# 0002 - 无服务器 P2P：空 iceServers + 临时 TCP 信令

- 状态：已接受
- 日期：2026-09-25
- 相关：`docs/adr/0001-use-libwebrtc-instead-of-custom-protocol.md`、`docs/spec.md`

## 背景

采用 libwebrtc 后必须选择信令方式（交换 SDP 与 ICE candidate）与网络穿透方案。
约束：**两台手机在同一 Wi-Fi 下直连，不引入任何服务器或外网依赖**；没有 adb，也没有可用的 TURN 基础设施。
同时本项目已有可用资产：UDP 广播发现（Beacon + 6 位连接码），本地测试已通过。

## 决策

- **网络穿透**：`RTCConfiguration` 的 `iceServers` 传**空列表**，只依赖本机（host）候选。
- **信令通道**：发现仍走现有 UDP Beacon；建立会话时开一条**临时 TCP 连接**，交换会话描述与候选，交换完成后可关闭或保留复用。
- **访问控制**：信令阶段校验连接码，不匹配直接拒绝（防止投屏到陌生设备）。

## 备选方案

| 方案 | 为什么不选 |
| --- | --- |
| LiveKit / 任意 SFU 服务器 | 需要部署服务器，违背"无服务器直连"前提 |
| 公共 STUN / 自建 TURN | 局域网内不需要穿透；引入外网依赖，且国内可达性不稳定 |
| 手动交换 SDP（二维码 / 复制粘贴） | 零代码但体验差，不符合"手机上点一下就投" |
| 信令也走 UDP | SDP 可达数 KB，需要在 UDP 上自建分片与重传 —— 等于把自创协议请回来 |

## 依据

- W3C WebRTC 规范：`RTCConfiguration.iceServers` 默认即空序列；规范不规定信令必须走服务器。
- libwebrtc 源码：`BasicPortAllocator` 在无 STUN 配置时跳过 STUN 端口创建，但仍无条件创建 UDP host 候选。
- 官方示例 `examples/androidapp/.../DirectRTCClient.java` 即"用 TCP 直连做信令 + 空 iceServers"的现成范式。

## 后果

- 零服务器、零配置，两台手机同一个 Wi-Fi 即可用；
- 只支持同一局域网（跨网段、跨 NAT 不可用）——本项目 M1 目标即如此，属预期范围；
- TCP 信令需要一个可用的临时端口与超时/重试策略（写入 `core:signal` 的测试范围）；
- 连接码是**唯一**的访问控制，M1 不提供加密身份（媒体本身仍由 WebRTC 加密）。

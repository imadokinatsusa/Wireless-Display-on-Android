# 0003 - 离线链路不用热点：LocalOnlyHotspot 拿不到 5GHz

- 状态：已接受
- 日期：2026-09-26
- 相关：`docs/adr/0002-serverless-p2p-tcp-signaling.md`、`CONTEXT.md`、`docs/spec.md`

## 背景

M1 要求"两台设备不连任何共同网络也能投屏" —— 在本项目的实际用法里，**离线是常态而非兜底**。
离线造链路只有两条路：**接收端开 `LocalOnlyHotspot`**，或 **Wi-Fi Direct（P2P）**。

真机实测把瓶颈指了出来：P2P 链路跑在 **2.4GHz** 上（诊断行 `ActualBand = 2.4GHz`），
丢包 >5%、RTT >200ms、端到端延迟 150-300ms、画面既糊又卡。
M1 的硬指标是 `1080p@30fps / 端到端 ≤150ms / 丢包 <1%` —— 2.4GHz 带不动。

于是必须回答一个问题：**能不能用热点把 5GHz 拿到手？** 本 ADR 记录答案。

## 决策

**离线链路继续走 Wi-Fi Direct，不用 `LocalOnlyHotspot`。**

Wi-Fi Direct 一侧改为"点名 5GHz 信道"：按 `LinkCandidate` 依次尝试
`setGroupOperatingFrequency(5180)` → `(5745)` → `setGroupOperatingBand(BAND_5GHZ)` → 系统默认，
并用 `WifiP2pGroup.getFrequency()` 回读 `ActualBand`，显示在诊断行上。

## 备选方案

| 方案 | 为什么不选 |
| --- | --- |
| **接收端开 `LocalOnlyHotspot`，并指定 5GHz** | **普通 App 根本无法为它指定频段**：`SoftApConfiguration.Builder` 在 API 30~35 期间是 `@SystemApi`（Android 16 / API 36 才进公开绑定），带 `SoftApConfiguration` 的 `startLocalOnlyHotspot` 重载同样不可达。频段只能由系统自选，实测与预期多为 2.4GHz —— 换了个壳，瓶颈一模一样 |
| 接收端开热点，但接受频段由系统自选 | 拿不到 5GHz 之外，还要多扛一个结构性坑：**接收端作为 AP 时，softap 接口不是 `ConnectivityManager` 的网络**，libwebrtc 枚举不到它、也不为它创建 socket → 接收端既不产出候选、也发不出连通性检查。普通 App 层没有可靠 workaround：`bindProcessToNetwork` / `Network.bindSocket` 都需要一个拿不到的 `Network` 对象，而手塞 SDP 候选会被 ICE agent 的 gathering 结果覆盖。作者已实测踩过："组能建、信令能通，画面却永远起不来" |
| 把角色反过来（发送端开热点） | 坑是对称的 —— AP 侧依旧无候选、无 socket，只是换了哪一端踩 |
| Wi-Fi Aware（NAN） | 吞吐不足以承载 1080p 视频 |
| 要求两台设备连同一个 5GHz 路由器 | 与"离线是硬需求"冲突。但它保留为**提示**：当 `ActualBand` 落在 2.4GHz 时，诊断行会写明"连同一个 Wi-Fi 会快得多" |

## 依据

- `SoftApConfiguration` 类 ApiSince=30；`SoftApConfiguration.Builder` 在 Android 16（API 36）之前为 `@SystemApi`，第三方 App 不可用。
- `WifiManager.startLocalOnlyHotspot` 的公开绑定只有 `(LocalOnlyHotspotCallback, Handler)` 形态（ApiSince=26），**不接受任何频段参数**。
- 本项目 `HotspotController.kt` 的原始注释已记录同一结论（"改名要用的 `SoftApConfiguration.Builder` 是隐藏 API，普通 App 调不到"）—— 本 ADR 只是把它从"命名问题"升级为"频段问题"。
- 反过来看 Wi-Fi Direct：`WifiP2pConfig.Builder.setGroupOperatingFrequency(int)` 的 ApiSince=29、`WifiP2pGroup.getFrequency()` 同为 ApiSince=29，二者都是公开 API，而本项目 `minSdk = 29` 正好够用 —— **这是离线场景里唯一能合法点名 5GHz 的手段**。
- AP 侧候选盲区：`WifiP2pLink` 与 `HotspotController` 的类注释记录了同一次真机实验的结论。

## 后果

- 离线场景**只剩 Wi-Fi Direct 一条路**，因此必须把它做到位：点名信道、回读 `ActualBand`、把诊断行当作唯一现场出口。
- **`ActualBand` 从"可选信息"升级为必需品** —— 没有它，任何关于丢包、RTT、延迟的讨论都可能在错误的频段上进行（本轮就是这样绕了一圈：先按 2.4GHz 的假设查链路，实际连频段都没确认）。
- `HotspotController` / `HotspotConnector` / `CastLink` 的热点凭证链路成为**保留但不启用**的资产：代码留在仓库，等 Android 16 的公开 API 或产品策略变化时可再评估；**`ReceiverScreen` 不得调用 `hotspot.start()`**。
- 若这两台设备的 P2P Group Owner 硬件仅支持 2.4GHz，则 `1080p@30` 在该场景下不可达；届时按既定策略**降质保流畅（720p@30）**，并在诊断行给出"连同一个 Wi-Fi 会更快"的提示。

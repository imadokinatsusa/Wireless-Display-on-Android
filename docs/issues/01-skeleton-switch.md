# 01: 骨架切换 —— mirror 命名、引入 WebRTC、删除自研协议

**What to build:** 把工程从 WhaleCast（自研协议）切成 `mirror`（WebRTC 路线）的骨架：改名、换模块、引入 AAR、删掉不再需要的一切。跑完后 CI 必须两个 job 全绿，且 APK 能装、能启动、显示占位界面。
**Blocked by:** 无（可立即开始）
**Status:** ✅ 完成（CI run #30 全绿，2026-09-25）

## 结果

- **CI**：[run #30](https://github.com/imadokinatsusa/Wireless-Display-on-Android/actions/runs/36127972464) 两个 job 全绿（JVM 单测 + debug APK），耗时 **86 秒**。
- **产物**：Release `mirror-build-30` 的 `app-debug.apk`，**41.8MB**（arm64-v8a + armeabi-v7a 两个原生库）。
- **本地**：`gradle -p C:\mirror-verify -c local.settings.gradle.kts :core:discovery:test :core:signal:test` 通过。

## 清单

- [x] 命名统一：`rootProject.name = "mirror"`、所有 Kotlin 包 `com.mirror.cast.*`、Manifest label `Mirror`
- [x] 模块收敛为三块：`:app`、`:core:discovery`（保留 + 改包名）、`:core:signal`（新建，纯 JVM）
- [x] 删除六个别旧模块：`:core:protocol`、`:core:transport`、`:core:media`、`:core:media-android`、`:core:session`、`:core:control`
- [x] `:app` 依赖 WebRTC AAR，`abiFilters = arm64-v8a, armeabi-v7a`
- [x] 权限补齐：`INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`WAKE_LOCK`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS`、`RECORD_AUDIO`
- [x] 前台服务类型声明保留（服务本体在切片 03 落地）
- [x] `local.settings.gradle.kts` 只含两个纯 JVM 模块
- [x] `.github/workflows/android.yml`：单测 job 跑 `:core:discovery:test :core:signal:test`；artifact `mirror-debug-apk`；tag `mirror-build-*`
- [x] 占位 UI：MainActivity 显示 Mirror + 诊断行 + 上次崩溃堆栈
- [x] 本地验收通过
- [x] CI 验收通过

## 踩到的坑（已在代码/工具里修掉）

1. **`Long * Double` 被推成 `Double`**：`CaptureSpec.bitRateFor` 末尾漏了 `.toLong()`，CI 上 `coerceIn(Long, Long)` 类型不匹配。已修并留注释。
2. **`push-to-github.mjs` 只增不减**：Git Data API 是增量覆盖语义，本地删掉的文件**不会**从远端消失 —— 于是 CI 编译到了整棵已删除的旧源码树（53 个文件）。已给脚本加"远端有、本地无 → `sha: null` 删除"的逻辑。
3. **库选型中途更换**：最初选 `io.getstream:stream-webrtc-android:1.3.10`（体积小、Apache-2.0），但**系统声音内录**需要 `setAudioRecordEnabled(false)` + `setAudioBufferCallback(...)`，stream 版没有前者；最终换成 `io.github.webrtc-sdk:android:150.7871.01`（LiveKit 维护的 m150）。理由见 `docs/adr/0001` 与 `docs/spec.md` 待定项 A。

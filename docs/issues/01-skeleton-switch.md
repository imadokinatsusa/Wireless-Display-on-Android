# 01: 骨架切换 —— mirror 命名、引入 WebRTC、删除自研协议

**What to build:** 把工程从 WhaleCast（自研协议）切成 `mirror`（WebRTC 路线）的骨架：改名、换模块、引入 AAR、删掉不再需要的一切。跑完后 CI 必须两个 job 全绿，且 APK 能装、能启动、显示占位界面。
**Blocked by:** 无（可立即开始）
**Status:** ready-for-agent

这张工单的价值是"先把地基换掉再动功能"——它单独可验证，且能立刻暴露 AAR 引入的构建风险。

- [ ] 命名统一：`settings.gradle.kts` 的 `rootProject.name = "mirror"`；所有 Kotlin 包改为 `com.mirror.cast.*`；`AndroidManifest.xml` 的 label 改为 `Mirror`
- [ ] 模块收敛为三块：`:app`、`:core:discovery`（保留并改包名）、`:core:signal`（新建空壳，纯 JVM）
- [ ] 删除：`:core:protocol`、`:core:transport`、`:core:media`、`:core:media-android`、`:core:session`、`:core:control` 及其全部源码与测试
- [ ] `:app` 依赖 `io.getstream:stream-webrtc-android:1.3.10`（Maven Central），并设 `abiFilters += listOf("arm64-v8a", "armeabi-v7a")`
- [ ] 权限清单补齐：`INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS`、`RECORD_AUDIO`
- [ ] 前台服务声明保留 `foregroundServiceType="mediaProjection"`
- [ ] `local.settings.gradle.kts` 更新为 `:core:discovery` + `:core:signal`（纯 JVM，本地可跑）
- [ ] `.github/workflows/android.yml` 更新：单测 job 跑 `:core:discovery:test :core:signal:test`；APK artifact 名 `mirror-debug-apk`；Release tag 前缀 `mirror-build-`
- [ ] 占位 UI：应用启动后显示 "Mirror" 与一行诊断文字（证明能装能起）
- [ ] 手工验收：CI 双 job 绿；从 Release 下载 APK 安装到手机能启动
- [ ] 本地验收：`gradle -p C:\mirror-verify -c local.settings.gradle.kts :core:discovery:test` 通过

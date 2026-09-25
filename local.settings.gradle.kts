// 本地验证专用 settings：**只包含纯 JVM 模块**。
//
// 因此它不需要 Android SDK，也不需要访问 google() 仓库（国内常连不通），
// 配上 .toolchain 里的 JDK + Gradle 就能在本机跑测试。
//
// 注意：路径含中文会让 JDK 解析失败，所以本地验证要先把源码同步到 ASCII 路径
// （例如 C:\mirror-verify），再在那边执行：
//   gradle -p C:\mirror-verify -c local.settings.gradle.kts :core:signal:test
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mirrorLocal"

include(":core:discovery")
include(":core:signal")

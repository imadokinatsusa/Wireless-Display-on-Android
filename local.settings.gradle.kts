// 本地验证专用 settings：**只包含纯 JVM 模块**。
//
// 因此它不需要 Android SDK，也不需要访问 google() 仓库（国内常连不通），
// 配上 .toolchain 里的 JDK + Gradle 就能在本机秒级跑测试。
//
// 用法：
//   .toolchain\gradle-8.11.1\bin\gradle.bat -c local.settings.gradle.kts :core:discovery:test
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

rootProject.name = "WhaleCastLocal"

include(":core:protocol")
include(":core:transport")
include(":core:media")
include(":core:session")
include(":core:discovery")

// 根构建脚本：只放跨模块通用配置。
//
// 插件声明刻意下放到各模块自己的 build.gradle.kts —— 这样
// "只验证纯 JVM 模块"的本地构建（local.settings.gradle.kts）就不会因为
// 要解析 Android 插件、访问 google() 仓库（国内常不通）而失败。

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// 统一测试日志：失败时把完整断言消息（expected/actual）打进日志，
// 这样本地与 CI 都能直接看到"哪个断言、期望什么、实际什么"。
subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }
}

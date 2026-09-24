// WhaleCast —— Android ↔ Android 局域网双向投屏
// 模块划分见 CONTEXT.md 与 .scratch/android-cast/spec.md
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WhaleCast"

include(":app")
include(":core:protocol")
include(":core:transport")
include(":core:media")
include(":core:media-android")
include(":core:session")
include(":core:control")
include(":core:discovery")

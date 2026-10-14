// App 侧 SDK：运行时 AAR + Gradle 插件 + 示例 App。
//
// 这是一个独立的 Gradle build，和 ide-plugin/ 分开：AGP 和 IntelliJ Platform Gradle Plugin
// 放进同一个 build 会互相拖累版本选择，分开之后各自升级互不影响。
//
// gradle-plugin 以 includeBuild 的方式组合进来，sample 可以直接 `id("kakacat.android.helper")`，
// 改插件不用发版。
pluginManagement {
    includeBuild("gradle-plugin")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-helper-sdk"
include(":runtime-core")
include(":runtime-network")
include(":runtime-layout")
include(":sample")

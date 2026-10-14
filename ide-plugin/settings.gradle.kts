// Android Studio 插件。独立的 Gradle build，见 ../sdk/settings.gradle.kts 里的说明。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "android-helper-ide"

// adb / 协议 / 数据模型，和 mcp/ 共用
includeBuild("../client")

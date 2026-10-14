// MCP 服务：让 agent（Claude Code、Cursor……）直接读写运行中 App 的 View 树和抓包数据，
// 以文本为主，不用截图。独立于 Android Studio 运行，直接走 adb。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "android-helper-mcp"
includeBuild("../client")

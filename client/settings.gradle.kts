// 和 IDE 无关的客户端代码：adb、帧协议、连接、抓包 / 布局数据模型。
// IDE 插件（ide-plugin/）和 MCP 服务（mcp/）都通过 includeBuild 用它。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "client"

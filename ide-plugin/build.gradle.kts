plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "kakacat.android.helper"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // 直接针对本机安装的 Android Studio 编译：不用下载几个 GB 的 IDE，
        // 而且编译时看到的 API 就是运行时的 API。
        local(providers.gradleProperty("androidStudioPath"))
    }
    testImplementation("junit:junit:4.13.2")
}

// 产物针对 JBR 21（AS 2024.3 起），用当前 JDK 编译即可，不强制装 21 的 toolchain。
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            // 不设上限。CodeLocator 在新版 AS 上启动就崩，很大一部分原因是绑死了内部 API
            // 和版本区间；这个插件只用平台的公开 API + 外部 adb 进程，不跟 AS 版本绑定。
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

// 本地调试：./gradlew runIde 会启动一个带本插件的沙箱 Android Studio
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    providers.gradleProperty("demoProject").orNull?.let { args(listOf(it)) }
    jvmArgs("-Dandroidhelper.openOnStartup=true", "-Didea.trust.all.projects=true")
    providers.gradleProperty("demoCycle").orNull?.let { jvmArgs("-Dandroidhelper.demoCycle=$it") }
    providers.gradleProperty("demoTab").orNull?.let { jvmArgs("-Dandroidhelper.demoTab=$it") }
    providers.gradleProperty("demoSelect").orNull?.let { jvmArgs("-Dandroidhelper.demoSelect=$it") }
    providers.gradleProperty("demoJump").orNull?.let { jvmArgs("-Dandroidhelper.demoJump=$it") }
    providers.gradleProperty("autoConnect").orNull?.let { jvmArgs("-Dandroidhelper.autoConnect=$it") }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "kakacat.android.helper"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // 本机：直接针对安装的 Android Studio 编译，不用下载几个 GB 的 IDE。
        // CI（没有本机 AS）：-PandroidStudioVersion=2026.1.4.7 改为下载这个版本。
        val asVersion = providers.gradleProperty("androidStudioVersion").orNull
        if (asVersion != null) {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.AndroidStudio, asVersion)
        } else {
            local(providers.gradleProperty("androidStudioPath"))
        }
    }
    implementation("kakacat.android.helper:client")
    testImplementation("junit:junit:4.13.2")
}

// 产物针对 JBR 21（AS 2024.3 起），用当前 JDK 编译即可，不强制装 21 的 toolchain。
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // 插件用的是 IDE 自带的 Kotlin 标准库；最老支持的 AS 2024.3 带的是 2.0.x，
        // 按 2.0 的 API 编译，避免用到老 IDE 里不存在的标准库函数（运行时才会 NoSuchMethodError）
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        // 实现平台的 Kotlin 接口（ToolWindowFactory 等）时不生成兼容桥接方法；否则那些桥接方法
        // 会"覆盖"接口里已废弃 / 实验性的默认方法，Plugin Verifier 会把它们报成我们的问题
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        // 商店页面上的介绍和更新说明放在单独的文件里，方便编辑
        description = providers.fileContents(layout.projectDirectory.file("marketplace/description.html")).asText
        changeNotes = providers.fileContents(layout.projectDirectory.file("marketplace/change-notes.html")).asText
        ideaVersion {
            sinceBuild = "243"
            // 不设上限。CodeLocator 在新版 AS 上启动就崩，很大一部分原因是绑死了内部 API
            // 和版本区间；这个插件只用平台的公开 API + 外部 adb 进程，不跟 AS 版本绑定。
            untilBuild = provider { null }
        }
    }

    // 签名和发布用的密钥只从环境变量读，绝不写进仓库。生成方法见 marketplace/PUBLISHING.md
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // ./gradlew verifyPlugin：用 JetBrains Plugin Verifier 检查 API 兼容性（商店上传后也会跑同样的检查）。
    // 每个版本要下载 1–1.5 GB，可以用 -PverifyAndroidStudio=2024.3.2.14,2026.2.1.8 只验一部分，
    // -PverifyLocal=true 额外验证本机安装的那个。
    pluginVerification {
        ides {
            val versions = providers.gradleProperty("verifyAndroidStudio").orNull?.split(',')?.map { it.trim() }
                ?: listOf(
                    "2024.3.2.14", // Meerkat Feature Drop, build 243 = sinceBuild
                    "2025.1.4.8",  // Narwhal 4 Feature Drop, 251
                    "2025.2.3.9",  // Otter 3 Feature Drop, 252
                    "2025.3.4.6",  // Panda 4, 253
                    "2026.2.1.8",  // Rabbit 1, 262（CodeLocator 在这一版上启动就崩）
                )
            versions.filter { it.isNotEmpty() }.forEach {
                create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.AndroidStudio, it)
            }
            if (providers.gradleProperty("verifyLocal").orNull == "true" && providers.gradleProperty("androidStudioVersion").orNull == null) {
                local(providers.gradleProperty("androidStudioPath").get())
            }
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
    providers.gradleProperty("demoNetSelect").orNull?.let { jvmArgs("-Dandroidhelper.demoNetSelect=$it") }
    providers.gradleProperty("demoDetailTab").orNull?.let { jvmArgs("-Dandroidhelper.demoDetailTab=$it") }
    providers.gradleProperty("demoLocale").orNull?.let { jvmArgs("-Dandroidhelper.locale=$it") }
    providers.gradleProperty("autoConnect").orNull?.let { jvmArgs("-Dandroidhelper.autoConnect=$it") }
}

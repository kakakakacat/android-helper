import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("java-gradle-plugin")
    id("maven-publish")
}

// 作为 included build 读不到外层的 gradle.properties，直接读文件 —— 版本号仍然只有一个来源。
val sdkProps = Properties().apply {
    layout.projectDirectory.file("../gradle.properties").asFile.inputStream().use { load(it) }
}
val helperVersion: String = sdkProps.getProperty("helper.version")

group = "kakacat.android.helper"
version = helperVersion

// 生成 SdkVersion.kt，插件运行时用它拼默认的运行时 AAR 坐标
val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val v = helperVersion
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val f = out.get().file("kakacat/android/helper/gradle/SdkVersion.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package kakacat.android.helper.gradle\n\ninternal const val SDK_VERSION = \"$v\"\n")
    }
}
sourceSets.main { kotlin.srcDir(generateVersion) }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // gradle-api（AGP 的稳定 API 面）而不是整个 AGP：我们只用 Variant / Instrumentation。
    compileOnly("com.android.tools.build:gradle-api:9.2.1")
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
}

gradlePlugin {
    plugins {
        create("androidHelper") {
            id = "kakacat.android.helper"
            implementationClass = "kakacat.android.helper.gradle.HelperPlugin"
            displayName = "Android Helper"
            description = "按 build type 接入 Android Helper 运行时，并在构建期插桩 OkHttp / gRPC / WebSocket 调用点"
        }
    }
}

val githubRepo: String = sdkProps.getProperty("helper.githubRepo")

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Android Helper Gradle plugin")
            description.set("Adds the Android Helper runtime to debug builds and instruments network / view call sites")
            url.set("https://github.com/$githubRepo")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            scm { url.set("https://github.com/$githubRepo") }
        }
    }
    repositories {
        maven {
            name = "mvnRepoDir"
            url = uri(layout.projectDirectory.dir("../../mvn-repo"))
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("java-gradle-plugin")
    id("maven-publish")
}

group = "kakacat.android.helper"
version = "0.1.0"

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

publishing {
    repositories {
        maven {
            name = "mvnRepoDir"
            url = uri(layout.projectDirectory.dir("../../mvn-repo"))
        }
    }
}

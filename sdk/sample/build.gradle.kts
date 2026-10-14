plugins {
    id("com.android.application") version "9.2.1"
    // AGP 9 内置的 Kotlin 是 2.2.10，Compose 编译器插件版本必须和它一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("kakacat.android.helper")
}

android {
    namespace = "kakacat.android.helper.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "kakacat.android.helper.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
}

// 插件按 maven 坐标加运行时依赖；在本仓库里联调时换成本地模块，改了 runtime 不用发版。
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("kakacat.android.helper:runtime-core")).using(project(":runtime-core"))
        substitute(module("kakacat.android.helper:runtime-network")).using(project(":runtime-network"))
        substitute(module("kakacat.android.helper:runtime-layout")).using(project(":runtime-layout"))
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Compose 页面：验证布局检查对 Compose 的支持
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
}

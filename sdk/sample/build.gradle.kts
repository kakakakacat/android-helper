plugins {
    id("com.android.application") version "9.2.1"
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
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
}

// 插件按 maven 坐标加运行时依赖；在本仓库里联调时换成本地模块，改了 runtime 不用发版。
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("kakacat.android.helper:runtime-core")).using(project(":runtime-core"))
        substitute(module("kakacat.android.helper:runtime-network")).using(project(":runtime-network"))
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

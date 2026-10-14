plugins {
    // AGP 9 自带 Kotlin 支持，单独再上 kotlin.android 会被 AGP 直接拒绝。
    id("com.android.library") version "9.2.1"
    id("maven-publish")
}

android {
    namespace = "kakacat.android.helper"
    compileSdk = 36

    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "SDK_VERSION", "\"${providers.gradleProperty("helper.version").get()}\"")
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    publishing { singleVariant("release") }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "runtime-core"
        }
    }
}

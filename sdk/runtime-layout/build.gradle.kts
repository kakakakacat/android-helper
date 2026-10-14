plugins {
    id("com.android.library") version "9.2.1"
    id("maven-publish")
}

android {
    namespace = "kakacat.android.helper.layout"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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

dependencies {
    api(project(":runtime-core"))
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "runtime-layout"
        }
    }
}

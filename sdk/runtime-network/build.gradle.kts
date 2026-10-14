plugins {
    id("com.android.library") version "9.2.1"
    id("maven-publish")
}

android {
    namespace = "kakacat.android.helper.network"
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

    // 全部 compileOnly。这个 AAR 不能给使用方强加任何网络库版本 —— 它只在使用方
    // *已经有* 某个库时才会被插桩调用到，而 JVM 的类加载是懒的：没有 grpc 的项目
    // 永远不会加载 GrpcCaptureInterceptor，因此也不会因为缺类而崩。
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("io.grpc:grpc-api:1.62.2")
    compileOnly("io.grpc:grpc-stub:1.62.2")
    compileOnly("com.google.protobuf:protobuf-javalite:3.25.3")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "runtime-network"
        }
    }
}

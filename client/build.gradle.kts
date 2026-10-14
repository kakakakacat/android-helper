plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
}

group = "kakacat.android.helper"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // 跑在 IDE 里时用的是 IDE 自带的标准库（最老 2.0.x），见 ide-plugin/build.gradle.kts
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    // 全部 compileOnly：IDE 里用 IDE 自带的 gson / protobuf / kotlin-stdlib（插件不能自带一份，
    // 否则同一个类被两个 ClassLoader 加载，传来传去就是 LinkageError）；MCP 服务自己声明运行时依赖。
    compileOnly(kotlin("stdlib"))
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("com.google.protobuf:protobuf-java:3.25.5")

    testImplementation(kotlin("stdlib"))
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.protobuf:protobuf-java:3.25.5")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // 真机端到端测试需要显式打开：ANDROIDHELPER_E2E=1
    // providers.environmentVariable 读的是这次执行 gradlew 时的环境；System.getenv 读的是
    // 常驻 daemon 启动时的环境，换个终端开关这个变量就不生效了
    val e2e = providers.environmentVariable("ANDROIDHELPER_E2E").orElse("").get()
    environment("ANDROIDHELPER_E2E", e2e)
    // 声明成输入：开关变了测试必须重跑，不能拿上次（跳过了真机测试的）结果当 up-to-date
    inputs.property("androidhelperE2e", e2e)
    // 真机测试依赖设备当前状态，结果不能缓存
    if (e2e == "1") outputs.upToDateWhen { false }
}

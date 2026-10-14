plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    application
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
    implementation("kakacat.android.helper:client")
    implementation(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("kakacat.android.helper.mcp.MainKt")
}

// 单个可执行 jar：agent 的配置里只需要 `java -jar android-helper-mcp.jar`
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds build/libs/android-helper-mcp.jar with all dependencies"
    archiveFileName.set("android-helper-mcp.jar")
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
    }
}
tasks.assemble { dependsOn(fatJar) }

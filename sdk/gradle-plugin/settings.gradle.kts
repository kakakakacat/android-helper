// 作为 included build 被 ../settings.gradle.kts 的 pluginManagement 引用。
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gradle-plugin"

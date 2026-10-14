// 版本号只在 gradle.properties 的 helper.version 里定义一次：runtime-core 生成到 BuildConfig，
// gradle-plugin 生成到 SdkVersion.kt（插件靠它拼出默认的运行时坐标）。
allprojects {
    group = "kakacat.android.helper"
    version = providers.gradleProperty("helper.version").get()
}

val githubRepo = providers.gradleProperty("helper.githubRepo").get()

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            // 发布到仓库根目录的 mvn-repo/，随 main 分支一起推到 GitHub。
            // 使用方读 raw.githubusercontent.com 下的静态文件，纯目录、零认证（见 scripts/publish-sdk.sh）
            repositories {
                maven {
                    name = "mvnRepoDir"
                    url = uri(rootProject.layout.projectDirectory.dir("../mvn-repo"))
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("Android Helper ${project.name}")
                    description.set("App-side runtime for the Android Helper Android Studio plugin")
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
        }
    }
}

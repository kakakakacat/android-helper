// 版本号在这里定义一次。改动后同步 gradle-plugin 里的 HelperPlugin.SDK_VERSION 和
// runtime-core 里的 Helper.VERSION —— 插件靠它拼出默认的运行时坐标。
allprojects {
    group = "kakacat.android.helper"
    version = "0.1.0"
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                maven {
                    name = "mvnRepoDir"
                    url = uri(rootProject.layout.projectDirectory.dir("../mvn-repo"))
                }
            }
        }
    }
}

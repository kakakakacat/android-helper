package kakacat.android.helper.ide.setup

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.io.File

/**
 * 不改用户项目、自动给 debug 构建接入 SDK。
 *
 * 做法是 Gradle 的 init script：写到 `<Gradle 用户目录>/init.d/droidlens.gradle`，Gradle 每次构建
 * （Android Studio 里、命令行里都一样）都会执行它。脚本只处理 `droidlens/projects.txt` 里登记的
 * 项目根目录 —— 也就是在装了插件的 Android Studio 里打开过的 Android 项目。
 *
 * 插件包里自带对应版本的 SDK（Gradle 插件 jar + 运行时 AAR），解压到 `droidlens/repo`：
 * 构建时不联网，SDK 版本也永远和 IDE 插件一致。
 *
 * 只认环境变量 GRADLE_USER_HOME 或默认的 ~/.gradle；在 Android Studio 设置里改过
 * Gradle 用户目录的话不会生效（这时用手动接入）。
 */
object SdkAutoSetup {

    private val LOG = Logger.getInstance(SdkAutoSetup::class.java)
    private const val ENABLED_KEY = "kakacat.droidlens.autoSetupSdk"
    private const val RESOURCES = "/droidlens"

    private val gradleHome = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".gradle")
    private val home = File(gradleHome, "droidlens")
    private val repo = File(home, "repo")
    private val projectsFile = File(home, "projects.txt")
    val initScript = File(gradleHome, "init.d/droidlens.gradle")

    /** 插件包里自带的 SDK 版本（构建时从 sdk/gradle.properties 写进来）。 */
    val sdkVersion: String by lazy { resource("sdk/version.txt").trim() }

    /** 开关；默认开。关掉时删掉 init script，项目登记和解压的 SDK 留着（无害，下次打开不用重来）。 */
    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(ENABLED_KEY, true)
        set(value) {
            PropertiesComponent.getInstance().setValue(ENABLED_KEY, value, true)
            runCatching {
                if (value) ProjectManager.getInstance().openProjects.forEach { register(it) } else uninstall()
            }.onFailure { LOG.warn("DroidLens auto setup: ${if (value) "enable" else "disable"} failed", it) }
        }

    /**
     * 打开项目时调用（后台线程）。返回 true 表示这个项目是第一次登记 —— 调用方据此提示用户一次。
     * 不是 Android 项目、或者项目自己已经接入了 SDK 时什么都不做。
     */
    @Synchronized
    fun register(project: Project): Boolean {
        if (!enabled) return false
        val root = project.basePath?.let { File(it).canonicalFile } ?: return false
        if (!isAndroidApp(root) || configuredManually(root)) return false
        install()
        val listed = if (projectsFile.isFile) projectsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        if (root.path in listed) return false
        projectsFile.writeText((listed + root.path).joinToString("\n", postfix = "\n"))
        return true
    }

    /** 当前项目会不会被自动接入（给接入引导页用）。 */
    fun coversProject(project: Project): Boolean {
        if (!enabled || !initScript.isFile) return false
        val root = project.basePath?.let { File(it).canonicalFile } ?: return false
        return projectsFile.isFile && projectsFile.readLines().any { it.trim() == root.path }
    }

    /** 解压自带的 SDK、写 init script。已经是最新的就不动。 */
    private fun install() {
        for (path in resource("sdk/index.txt").lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val target = File(repo, path)
            if (target.isFile) continue // 已发布的版本不会变，存在即可
            target.parentFile.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            SdkAutoSetup::class.java.getResourceAsStream("$RESOURCES/sdk/$path")!!.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(target)) tmp.delete()
        }
        val script = resource("droidlens-init.gradle").replace("@VERSION@", sdkVersion)
        if (!initScript.isFile || initScript.readText() != script) {
            initScript.parentFile.mkdirs()
            initScript.writeText(script)
        }
    }

    private fun uninstall() {
        if (initScript.isFile) initScript.delete()
    }

    private fun resource(path: String): String =
        SdkAutoSetup::class.java.getResource("$RESOURCES/$path")!!.readText()

    /**
     * 根目录和下两层的 Gradle 文件、版本目录里出现了 Android application 插件
     * （`com.android.application`，或版本目录别名 `libs.plugins.android.application`）。
     */
    private fun isAndroidApp(root: File) = gradleFiles(root).any { it.readText().contains("android.application") }

    /** 项目自己已经写了 kakacat.android.helper：两份插件会冲突，init script 也会跳过它。 */
    private fun configuredManually(root: File) = gradleFiles(root).any { it.readText().contains("kakacat.android.helper") }

    private fun gradleFiles(root: File): Sequence<File> {
        val names = setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        val dirs = sequenceOf(root) + childDirs(root) + childDirs(root).flatMap { childDirs(it) }
        return dirs.flatMap { d -> names.asSequence().map { File(d, it) } }
            .plus(File(root, "gradle").listFiles()?.filter { it.name.endsWith(".toml") }.orEmpty())
            .filter { it.isFile && it.length() < 1_000_000 }
    }

    private fun childDirs(d: File): Sequence<File> =
        d.listFiles()?.asSequence()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" }.orEmpty()
}

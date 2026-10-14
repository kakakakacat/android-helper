package kakacat.android.helper.gradle

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * 使用方要做的全部事情：
 *
 * ```kotlin
 * plugins { id("kakacat.android.helper") }
 * ```
 *
 * 插件负责三件事：
 *
 *  1. 只给白名单里的 build type（默认 debug）**加运行时 AAR 依赖** ——
 *     release 里连这个 AAR 都不存在，这是比任何运行时开关都硬的隔离；
 *  2. 注册 ASM 插桩：网络调用点改写成对 `Hooks` 的调用（抓包）；View 的创建 / 监听 /
 *     inflate 调用点记下源码位置（布局检查里一键跳转）；
 *  3. 输出插桩报告，说明命中了哪些调用点 —— 抓不到数据时第一件事就是看它。
 */
class HelperPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("androidHelper", HelperExtension::class.java).apply {
            enabled.convention(true)
            buildTypes.convention(setOf("debug", "internal"))
            scope.convention("all")
            excludes.convention(emptyList())
            network.convention(true)
            layout.convention(true)
            runtimeVersion.convention(SDK_VERSION)
        }

        val components = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: throw IllegalStateException(
                "kakacat.android.helper 必须应用在 Android 模块上（com.android.application " +
                    "或 com.android.library 之后）"
            )

        val report = project.tasks.register(
            "androidHelperReport", HelperReportTask::class.java
        ) { task ->
            task.group = "verification"
            task.description = "打印 Android Helper 插桩命中的调用点"
            task.reportDir.set(project.layout.buildDirectory.dir("reports/androidhelper"))
        }

        components.onVariants { variant ->
            if (!ext.enabled.get()) return@onVariants
            // 按 **build type** 而不是 variant 名匹配：带 flavor 的模块 variant 叫
            // fooDebug / barDebug，按名字比对会全部漏掉，而且是静默漏掉。
            if (variant.buildType !in ext.buildTypes.get()) return@onVariants

            addRuntimeDependency(project, variant, "runtime-core", ext)
            if (ext.network.get()) {
                addRuntimeDependency(project, variant, "runtime-network", ext)
                instrumentNetwork(project, variant, ext)
            }
            if (ext.layout.get()) {
                addRuntimeDependency(project, variant, "runtime-layout", ext)
                instrumentLayout(project, variant, ext)
            }
            if (ext.network.get() || ext.layout.get()) {
                // 我们只替换 / 追加 invoke 指令。COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS 只对
                // 被改过的方法重算 frame 和 maxStack（layout 插桩会多压栈），代价很小。
                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }
            report.configure { it.dependsOn("assemble${variant.name.capitalizeAscii()}") }
        }
    }

    /**
     * 依赖加在 variant 自己的配置上，不是全局 `implementation`。
     *
     * 这是 release 隔离的根：release variant 的编译和运行时 classpath 上都没有这个 AAR，
     * 也就没有它的清单（provider / receiver 都不会合并进去），更没有采集代码。
     */
    private fun addRuntimeDependency(
        project: Project,
        variant: Variant,
        artifact: String,
        ext: HelperExtension,
    ) {
        val coordinates = "$GROUP:$artifact:${ext.runtimeVersion.get()}"
        val configuration = "${variant.name}Implementation"
        if (project.configurations.findByName(configuration) == null) {
            project.logger.warn("androidHelper: 找不到配置 $configuration，跳过 ${variant.name}")
            return
        }
        project.dependencies.add(configuration, coordinates)
        project.logger.info("androidHelper: ${variant.name} += $coordinates")
    }

    private fun instrumentNetwork(project: Project, variant: Variant, ext: HelperExtension) {
        val scope = scope(ext)
        variant.instrumentation.transformClassesWith(
            NetworkClassVisitorFactory::class.java,
            scope
        ) { params ->
            params.hitFilePath.set(
                project.layout.buildDirectory
                    .file("reports/androidhelper/${variant.name}-hits.txt")
                    .get().asFile.absolutePath
            )
            params.excludes.set(ext.excludes.get())
        }

        project.logger.info("androidHelper: 网络插桩 ${variant.name}（scope=$scope）")
    }

    private fun instrumentLayout(project: Project, variant: Variant, ext: HelperExtension) {
        variant.instrumentation.transformClassesWith(
            LayoutClassVisitorFactory::class.java,
            scope(ext)
        ) { params ->
            params.hitFilePath.set(
                project.layout.buildDirectory
                    .file("reports/androidhelper/${variant.name}-layout-hits.txt")
                    .get().asFile.absolutePath
            )
            params.excludes.set(ext.excludes.get())
        }
        project.logger.info("androidHelper: 布局插桩 ${variant.name}")
    }

    private fun scope(ext: HelperExtension) = when (ext.scope.get().lowercase()) {
        "all" -> InstrumentationScope.ALL
        else -> InstrumentationScope.PROJECT
    }

    private fun String.capitalizeAscii(): String =
        if (isEmpty()) this else this[0].uppercaseChar() + substring(1)

    companion object {
        const val GROUP = "kakacat.android.helper"
    }
}

/** `androidHelper { … }` 配置块。 */
interface HelperExtension {
    /** 总开关。 */
    val enabled: Property<Boolean>

    /**
     * 哪些 **build type** 接入。默认 debug + internal，**绝不含 release**。
     *
     * 是 build type 而不是 variant 名：带 flavor 的模块 variant 叫 `fooDebug`，
     * 按 variant 名配白名单会静默漏掉这类模块。
     */
    val buildTypes: SetProperty<String>

    /**
     * `all`（默认）连依赖一起插桩，`project` 只处理本模块的 class。
     *
     * 默认必须是 `all`：OkHttp / gRPC 客户端通常是在库模块或三方 AAR 里创建的，
     * `project` 只看得见 app 模块自己的 class。代价是构建更慢。
     */
    val scope: Property<String>

    /** 额外排除的类名前缀，用斜杠形式，如 `com/example/legacy/`。 */
    val excludes: ListProperty<String>

    /** 抓包能力。关掉后不加 runtime-network、也不插桩网络调用点。 */
    val network: Property<Boolean>

    /**
     * 布局检查能力：加 runtime-layout，并插桩记录 View 的创建位置、点击监听、布局文件、
     * Dialog 弹出位置，供 IDE 一键跳转。
     */
    val layout: Property<Boolean>

    /** 运行时 AAR 版本。默认跟随插件版本，一般不用改。 */
    val runtimeVersion: Property<String>
}

package kakacat.android.helper.ide

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 新 UI 下，第三方插件的工具窗口默认藏在侧边栏的 "⋯" 里，装完插件找不到入口。
 * 这里在第一次打开项目时把按钮放到侧边栏上；之后用户自己移走/隐藏的话尊重用户，不再干预。
 */
class HelperStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val props = PropertiesComponent.getInstance()
        val firstRun = !props.getBoolean(SHOWN_KEY, false)
        // 开发用：./gradlew runIde 时自动展开工具窗口，见 build.gradle.kts
        val autoOpen = System.getProperty("androidhelper.openOnStartup") == "true"
        if (!firstRun && !autoOpen) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@invokeLater
            tw.isShowStripeButton = true
            props.setValue(SHOWN_KEY, true)
            if (autoOpen) {
                tw.show()
                ToolWindowManager.getInstance(project).setMaximized(tw, true)
            }
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "DroidLens"
        private const val SHOWN_KEY = "kakacat.androidhelper.stripeShown"
    }
}

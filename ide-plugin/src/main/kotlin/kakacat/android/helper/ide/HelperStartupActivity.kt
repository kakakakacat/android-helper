package kakacat.android.helper.ide

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.ToolWindowManager
import kakacat.android.helper.ide.report.ErrorReportConfigurable
import kakacat.android.helper.ide.report.ErrorReporter
import kakacat.android.helper.ide.setup.SdkAutoSetup

/**
 * 新 UI 下，第三方插件的工具窗口默认藏在侧边栏的 "⋯" 里，装完插件找不到入口。
 * 这里在第一次打开项目时把按钮放到侧边栏上；之后用户自己移走/隐藏的话尊重用户，不再干预。
 */
class HelperStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val props = PropertiesComponent.getInstance()
        noticeErrorReporting(project, props)
        setUpSdk(project)
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

    /** 错误上报默认开启：第一次告诉用户一声，给出关掉的入口。 */
    private fun noticeErrorReporting(project: Project, props: PropertiesComponent) {
        if (props.getBoolean(REPORT_NOTICE_KEY, false)) return
        props.setValue(REPORT_NOTICE_KEY, true)
        NotificationGroupManager.getInstance().getNotificationGroup("DroidLens")
            .createNotification(HelperBundle.message("report.notice"), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(HelperBundle.message("report.notice.disable")) {
                ErrorReporter.enabled = false
            })
            .addAction(NotificationAction.createSimple(HelperBundle.message("report.notice.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ErrorReportConfigurable::class.java)
            })
            .notify(project)
    }

    /** 自动接入 SDK：项目第一次登记时告诉用户一声（改的是什么、怎么关）。 */
    private fun setUpSdk(project: Project) {
        val added = runCatching { SdkAutoSetup.register(project) }
            .onFailure { LOG.warn("DroidLens auto setup failed", it) }
            .getOrDefault(false)
        if (!added) return
        NotificationGroupManager.getInstance().getNotificationGroup("DroidLens")
            .createNotification(HelperBundle.message("autoSetup.notice"), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(HelperBundle.message("autoSetup.notice.disable")) {
                SdkAutoSetup.enabled = false
            })
            .addAction(NotificationAction.createSimple(HelperBundle.message("report.notice.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ErrorReportConfigurable::class.java)
            })
            .notify(project)
    }

    companion object {
        private val LOG = Logger.getInstance(HelperStartupActivity::class.java)
        private const val REPORT_NOTICE_KEY = "kakacat.droidlens.reportNoticeShown"

        const val TOOL_WINDOW_ID = "DroidLens"
        private const val SHOWN_KEY = "kakacat.androidhelper.stripeShown"
    }
}

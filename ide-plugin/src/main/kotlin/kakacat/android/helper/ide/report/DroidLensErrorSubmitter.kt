package kakacat.android.helper.ide.report

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import kakacat.android.helper.ide.HelperBundle
import java.awt.Component

/**
 * IDE 错误对话框（右下角红色感叹号）里 DroidLens 抛出的异常，点"报告"时走这里。
 * 和 SDK 错误同一条通道：脱敏后发给 Worker，在 GitHub 上开 issue 或追加到已有 issue。
 */
class DroidLensErrorSubmitter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = HelperBundle.message("report.action")

    override fun getPrivacyNoticeText(): String = HelperBundle.message("report.privacy")

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val text = events.firstOrNull()?.throwableText ?: return false
        ApplicationManager.getApplication().executeOnPooledThread {
            val url = ErrorReporter.reportIde(text, additionalInfo)
            val info = if (url != null) {
                SubmittedReportInfo(url, HelperBundle.message("report.sent"), SubmittedReportInfo.SubmissionStatus.NEW_ISSUE)
            } else {
                SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED)
            }
            ApplicationManager.getApplication().invokeLater { consumer.consume(info) }
        }
        return true
    }
}

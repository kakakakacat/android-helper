package kakacat.android.helper.ide.report

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import kakacat.android.helper.ide.HelperBundle
import kakacat.android.helper.ide.setup.SdkAutoSetup

/** Settings → Tools → DroidLens */
class ErrorReportConfigurable : BoundConfigurable(HelperBundle.message("settings.title")) {
    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox(HelperBundle.message("settings.autoSetup"))
                .bindSelected(SdkAutoSetup::enabled)
        }
        row {
            comment(HelperBundle.message("settings.autoSetup.comment", SdkAutoSetup.initScript.path))
        }
        row {
            checkBox(HelperBundle.message("settings.reportErrors"))
                .bindSelected(ErrorReporter::enabled)
        }
        row {
            comment(HelperBundle.message("settings.reportErrors.comment"))
        }
    }
}

package kakacat.android.helper.ide

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * 设备上没有接了 SDK 的 App 时，工具窗口中间显示的接入步骤。
 *
 * 装了插件却不知道 App 里还要接 SDK 是最常见的困惑 —— 状态栏那一行字没人看，
 * 所以直接把步骤和可复制的 Gradle 配置摆在中间。
 */
class SetupPanel : JPanel(BorderLayout()) {

    init {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16, 24)
        }
        content.add(left(JBLabel(HelperBundle.message("setup.title")).apply {
            font = JBUI.Fonts.label().biggerOn(3f).asBold()
        }))
        content.add(left(JBLabel(HelperBundle.message("setup.intro")).apply { border = JBUI.Borders.empty(6, 0, 12, 0) }))

        content.add(left(JBLabel(HelperBundle.message("setup.step1"))))
        content.add(snippet(SETTINGS_SNIPPET, HelperBundle.message("setup.copy")))
        content.add(left(JBLabel(HelperBundle.message("setup.step2"))))
        content.add(snippet(APP_SNIPPET, HelperBundle.message("setup.copy")))
        content.add(left(JBLabel(HelperBundle.message("setup.step3")).apply { border = JBUI.Borders.emptyBottom(12) }))

        content.add(left(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JButton(HelperBundle.message("setup.docs")).apply {
                addActionListener { BrowserUtil.browse(DOCS_URL) }
            })
        }))
        add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    private fun snippet(code: String, copyText: String): JPanel {
        val area = JTextArea(code).apply {
            isEditable = false
            font = JBUI.Fonts.create("JetBrains Mono", UIUtil.getLabelFont().size)
            background = UIUtil.getTextFieldBackground()
            border = JBUI.Borders.empty(8)
        }
        val copy = JButton(copyText).apply {
            addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(code)) }
        }
        return left(JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 12, 0)
            add(area, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply { isOpaque = false; add(copy, BorderLayout.NORTH) }, BorderLayout.EAST)
            maximumSize = java.awt.Dimension(JBUI.scale(760), preferredSize.height)
        })
    }

    private fun <T : javax.swing.JComponent> left(c: T): T = c.apply { alignmentX = LEFT_ALIGNMENT }

    private companion object {
        // 发新版 SDK 时同步改这里（和 marketplace/description.html、README 一起）
        const val SDK_VERSION = "0.1.2"
        const val REPO = "https://raw.githubusercontent.com/kakakakacat/android-helper/main/mvn-repo/"
        const val DOCS_URL = "https://github.com/kakakakacat/android-helper#在项目里接入"

        val SETTINGS_SNIPPET = """
            // settings.gradle(.kts): add the repository in BOTH blocks
            pluginManagement {
                repositories {
                    maven { url = uri("$REPO") }
                    gradlePluginPortal()
                    google()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("$REPO") }
                    google()
                    mavenCentral()
                }
            }
        """.trimIndent()

        val APP_SNIPPET = """
            // app/build.gradle(.kts)
            plugins {
                id("kakacat.android.helper") version "$SDK_VERSION"
            }
        """.trimIndent()
    }
}

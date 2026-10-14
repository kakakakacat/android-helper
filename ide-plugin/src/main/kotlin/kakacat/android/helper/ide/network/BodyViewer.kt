package kakacat.android.helper.ide.network

import kakacat.android.helper.ide.HelperBundle
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 显示一段 body：顶部是视图切换 + 大小信息 + 复制，下面是文本或图片。
 *
 * 用户手动选的视图在切换选中的请求时保留（看一串 gRPC 消息时不用每条都重选）。
 */
class BodyViewer : JPanel(BorderLayout()) {

    private val viewBox = ComboBox(BodyView.entries.toTypedArray())
    private val info = JBLabel()
    private val copy = JButton(HelperBundle.message("body.copy"))
    private val text = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        border = JBUI.Borders.empty(4, 6)
    }
    private val image = JBLabel().apply { horizontalAlignment = SwingConstants.CENTER }
    private val cards = CardLayout()
    private val center = JPanel(cards)

    private var bytes: ByteArray? = null
    private var contentType: String? = null
    private var protoDecode: ((ByteArray) -> String?)? = null
    private var isProto = false
    private var emptyHint = ""

    init {
        val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        top.add(viewBox)
        top.add(copy)
        top.add(info)
        add(top, BorderLayout.NORTH)
        center.add(JBScrollPane(text), "text")
        center.add(JBScrollPane(image), "image")
        add(center, BorderLayout.CENTER)

        viewBox.addActionListener { render() }
        copy.addActionListener {
            val content = if (currentView() == BodyView.IMAGE) null else text.text
            content?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
        }
    }

    /**
     * @param protoDecode 有 descriptor 时用它解 protobuf（gRPC 消息）；返回 null 则退回启发式。
     */
    fun show(
        body: Body?,
        contentType: String? = null,
        isProto: Boolean = false,
        protoDecode: ((ByteArray) -> String?)? = null,
        emptyHint: String = HelperBundle.message("body.none"),
    ) {
        this.bytes = body?.bytes
        this.contentType = contentType
        this.isProto = isProto
        this.protoDecode = protoDecode
        this.emptyHint = emptyHint
        info.text = when {
            body == null -> ""
            body.truncated -> HelperBundle.message("body.truncated", BodyFormat.size(body.bytes.size.toLong()), BodyFormat.size(body.truncatedFrom.toLong()))
            else -> BodyFormat.size(body.bytes.size.toLong())
        }
        render()
    }

    private fun currentView(): BodyView {
        val b = bytes ?: return BodyView.TEXT
        val chosen = viewBox.selectedItem as BodyView
        return if (chosen == BodyView.AUTO) BodyFormat.detect(b, contentType, isProto) else chosen
    }

    private fun render() {
        val b = bytes
        copy.isEnabled = b != null
        if (b == null) {
            showText(emptyHint)
            return
        }
        when (currentView()) {
            BodyView.IMAGE -> {
                val img = runCatching { ImageIO.read(b.inputStream()) }.getOrNull()
                if (img != null) {
                    image.icon = ImageIcon(img)
                    image.text = null
                    cards.show(center, "image")
                } else {
                    showText(BodyFormat.hex(b))
                }
            }
            BodyView.JSON -> showText(BodyFormat.json(b) ?: BodyFormat.text(b))
            BodyView.TEXT -> showText(BodyFormat.text(b))
            BodyView.HEX -> showText(BodyFormat.hex(b))
            BodyView.PROTOBUF -> showText(
                protoDecode?.invoke(b)
                    ?: ProtoRegistry.heuristic(b)?.let { HelperBundle.message("body.noDescriptor") + "\n" + it }
                    ?: BodyFormat.hex(b)
            )
            BodyView.AUTO -> Unit // currentView() 不会返回 AUTO
        }
    }

    private fun showText(s: String) {
        text.text = s
        text.caretPosition = 0
        cards.show(center, "text")
    }
}

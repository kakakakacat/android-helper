package kakacat.android.helper.ide.network

import kakacat.android.helper.client.network.*
import kakacat.android.helper.ide.HelperBundle
import com.google.gson.GsonBuilder
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * 右侧详情。HTTP：Overview / Request / Response / Raw；gRPC 和 WS：Overview / Messages / Raw。
 */
class CallDetailPanel(private val protoRegistry: () -> ProtoRegistry?) : JPanel(BorderLayout()) {

    private val tabs = JBTabbedPane()
    private val overview = monoText()
    private val raw = monoText()
    private val request = BodyViewer()
    private val response = BodyViewer()

    private val messageModel = MessageTableModel()
    private val messageTable = JBTable(messageModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        columnModel.getColumn(0).preferredWidth = 40
        columnModel.getColumn(1).preferredWidth = 50
        columnModel.getColumn(2).preferredWidth = 92
        columnModel.getColumn(3).preferredWidth = 70
        columnModel.getColumn(4).preferredWidth = 300
    }
    private val messageBody = BodyViewer()
    private val messages = JBSplitter(true, 0.35f).apply {
        firstComponent = JBScrollPane(messageTable)
        secondComponent = messageBody
    }

    private var call: NetCall? = null
    private var tabKind: Kind? = null

    init {
        add(tabs, BorderLayout.CENTER)
        messageTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) showSelectedMessage()
        }
        showEmpty()
    }

    /** 切到指定标题的标签（不存在就不动）。 */
    fun selectTab(title: String) {
        (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == title }?.let { tabs.selectedIndex = it }
    }

    fun show(c: NetCall?) {
        call = c
        if (c == null) {
            showEmpty()
            return
        }
        if (tabKind != c.kind) rebuildTabs(c.kind)
        refresh()
    }

    /** 选中的调用有新数据到达（比如响应回来了、多了几条流式消息）。 */
    fun refresh() {
        val c = call ?: return
        overview.text = overviewText(c)
        overview.caretPosition = 0
        raw.text = RAW_GSON.toJson(c.rawEvents)
        raw.caretPosition = 0
        when (c.kind) {
            Kind.HTTP -> {
                request.show(c.reqBody, c.reqType ?: header(c.reqHeaders, "content-type"),
                    emptyHint = if (c.reqLen > 0) HelperBundle.message("body.notCapturedReq") else HelperBundle.message("body.none"))
                response.show(c.respBody, c.respType ?: header(c.respHeaders, "content-type"),
                    emptyHint = when {
                        c.state == CallState.PENDING -> HelperBundle.message("body.waiting")
                        c.respLen > 0 -> HelperBundle.message("body.notCapturedResp")
                        else -> HelperBundle.message("body.none")
                    })
            }
            else -> {
                val selected = messageTable.selectedRow
                messageModel.setMessages(c.messages)
                if (selected >= 0 && selected < messageModel.rowCount) {
                    messageTable.setRowSelectionInterval(selected, selected)
                }
                showSelectedMessage()
            }
        }
    }

    private fun showSelectedMessage() {
        val c = call ?: return
        val m = messageModel.messages.getOrNull(messageTable.selectedRow)
        if (m == null) {
            messageBody.show(null, emptyHint = if (c.messages.isEmpty()) HelperBundle.message("msg.none") else HelperBundle.message("msg.select"))
            return
        }
        val isProto = c.kind == Kind.GRPC
        val decode: ((ByteArray) -> String?)? = if (isProto) {
            val method = c.grpcMethod
            val registry = protoRegistry()
            if (method != null && registry != null) { bytes -> registry.decode(method, m.dir, bytes) } else null
        } else null
        val ct = if (c.kind == Kind.WS && m.type == "text") "text/plain" else null
        messageBody.show(m.body, ct, isProto, decode,
            emptyHint = if (m.len > 0) HelperBundle.message("msg.notCaptured") else HelperBundle.message("msg.empty"))
    }

    private fun rebuildTabs(kind: Kind) {
        val selectedTitle = tabs.selectedIndex.takeIf { it >= 0 }?.let { tabs.getTitleAt(it) }
        tabs.removeAll()
        tabs.addTab("Overview", JBScrollPane(overview))
        if (kind == Kind.HTTP) {
            tabs.addTab("Request", request)
            tabs.addTab("Response", response)
        } else {
            tabs.addTab("Messages", messages)
        }
        tabs.addTab("Raw", JBScrollPane(raw))
        tabKind = kind
        // 从 HTTP 切到 gRPC 时尽量停在同一个标签上；Response ↔ Messages 视为同一位置
        val target = when (selectedTitle) {
            "Response", "Request" -> if (kind == Kind.HTTP) selectedTitle else "Messages"
            "Messages" -> if (kind == Kind.HTTP) "Response" else "Messages"
            else -> selectedTitle
        }
        (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == target }?.let { tabs.selectedIndex = it }
    }

    private fun showEmpty() {
        tabs.removeAll()
        tabKind = null
        overview.text = HelperBundle.message("detail.empty")
        tabs.addTab("Overview", JBScrollPane(overview))
    }

    private fun overviewText(c: NetCall): String = buildString {
        fun line(k: String, v: Any?) {
            if (v == null || v.toString().isEmpty()) return
            append("  ").append(k.padEnd(16)).append(v).append('\n')
        }

        fun section(title: String, rows: List<Pair<String, String>>) {
            append('\n').append(title).append(if (rows.isEmpty()) "  " + HelperBundle.message("detail.none") else "").append('\n')
            for ((k, v) in rows) append("  ").append(k).append(": ").append(v).append('\n')
        }

        append("General\n")
        when (c.kind) {
            Kind.HTTP -> {
                line("URL", c.url)
                line("Method", c.method)
                line("Status", listOfNotNull(c.status?.toString(), c.statusText?.takeIf { it.isNotEmpty() }).joinToString(" "))
                line("Protocol", c.protocol)
                line("TLS", listOfNotNull(c.tlsVersion, c.tlsCipher).joinToString("  ").ifEmpty { null })
                if (c.redirects > 0) line("Redirects", c.redirects)
                if (c.fromCache) line("From cache", "yes")
            }
            Kind.GRPC -> {
                line("Method", c.grpcMethod)
                line("Call type", c.callType)
                line("Authority", c.authority)
                line("Status", listOfNotNull(c.grpcCode, c.grpcDescription).joinToString("  "))
                line("Deadline", c.deadlineMs?.let { "$it ms" })
                line("Messages", "↑ ${c.messages.count { it.dir == "up" }}   ↓ ${c.messages.count { it.dir == "down" }}")
            }
            Kind.WS -> {
                line("URL", c.url)
                line("Status", c.status)
                line("Protocol", c.protocol)
                line("State", c.closeReason ?: if (c.state == CallState.PENDING) "open" else "")
                line("Frames", "↑ ${c.messages.count { it.dir == "up" }}   ↓ ${c.messages.count { it.dir == "down" }}")
            }
        }
        line("Error", c.error)
        line("Started", TIME.format(Date(c.startTime)))
        line("Duration", c.durationMs?.let { "$it ms" } ?: if (c.state == CallState.PENDING) "…" else null)
        line("Thread", c.thread)
        line("Process", "pid ${c.pid}")

        when (c.kind) {
            Kind.HTTP -> {
                section("Request Headers", c.reqHeaders)
                section("Response Headers", c.respHeaders)
            }
            Kind.GRPC -> {
                section("Request Metadata", c.metadata)
                section("Response Headers", c.respMetadata)
                section("Trailers", c.trailers)
            }
            Kind.WS -> section("Handshake Response Headers", c.respHeaders)
        }
    }

    private fun header(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun monoText() = JBTextArea().apply {
        isEditable = false
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        border = JBUI.Borders.empty(6)
    }

    private class MessageTableModel : AbstractTableModel() {
        var messages: List<NetMessage> = emptyList()
            private set
        private val names = arrayOf("#", "Dir", "Time", "Size", "Type")

        fun setMessages(list: List<NetMessage>) {
            val sizeChanged = list.size != messages.size
            messages = list.toList()
            if (sizeChanged) fireTableDataChanged() else fireTableRowsUpdated(0, maxOf(0, messages.size - 1))
        }

        override fun getRowCount() = messages.size
        override fun getColumnCount() = names.size
        override fun getColumnName(column: Int) = names[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val m = messages[rowIndex]
            return when (columnIndex) {
                0 -> m.seq
                1 -> if (m.dir == "up") "↑ up" else "↓ down"
                2 -> TIME_SHORT.format(Date(m.time))
                3 -> BodyFormat.size(m.len.toLong())
                else -> m.type?.substringAfterLast('.')?.substringAfterLast('$') ?: ""
            }
        }
    }

    private companion object {
        val RAW_GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
        val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        val TIME_SHORT = SimpleDateFormat("HH:mm:ss.SSS")
    }
}

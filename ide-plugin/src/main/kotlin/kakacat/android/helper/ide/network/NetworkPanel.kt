package kakacat.android.helper.ide.network

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.JBUI
import kakacat.android.helper.ide.HelperSession
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.TableRowSorter

/**
 * Network 标签页：过滤栏 + 调用列表 + 详情。
 *
 * 连接相关的控件（设备 / App / 连接按钮）在工具窗口顶部，所有标签页共用，见 HelperToolWindow。
 */
class NetworkPanel(private val session: HelperSession) : JPanel(BorderLayout()), HelperSession.Listener {

    private val store = session.netStore
    private val model = CallTableModel(store)
    private val sorter = TableRowSorter(model)
    private val table = JBTable(model)
    private val filter = SearchTextField(false).apply { textEditor.emptyText.text = "过滤 URL / 方法 / 状态码" }
    private val kindFilter = ComboBox(arrayOf("All", "HTTP", "gRPC", "WS"))
    private val counter = JBLabel()

    private var registry: ProtoRegistry? = null
    private val detail = CallDetailPanel { registry }

    /** 有新行时滚到底；用户往上翻看的时候不打扰。 */
    private var followTail = true

    init {
        table.rowSorter = sorter
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(false)
        table.autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        val renderer = model.Renderer()
        CallTableModel.Column.entries.forEachIndexed { i, col ->
            val tc = table.columnModel.getColumn(i)
            tc.preferredWidth = JBUI.scale(col.width)
            tc.cellRenderer = renderer
            // 只让 Host / Name 伸缩，其余列固定宽度，窄窗口里时间和状态码不会被挤成省略号
            if (col != CallTableModel.Column.HOST && col != CallTableModel.Column.NAME) {
                tc.minWidth = JBUI.scale(col.width)
                tc.maxWidth = JBUI.scale(col.width + 40)
            }
        }

        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) detail.show(selectedCall())
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
        })

        val scroll = JBScrollPane(table)
        scroll.verticalScrollBar.addAdjustmentListener {
            if (!it.valueIsAdjusting) {
                val bar = scroll.verticalScrollBar
                followTail = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(8)
            }
        }

        val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(filter)
            add(kindFilter)
            add(counter)
        }
        filter.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        kindFilter.addActionListener { applyFilter() }

        val left = JPanel(BorderLayout()).apply {
            add(filterBar, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
        add(JBSplitter(false, 0.55f).apply {
            firstComponent = left
            secondComponent = detail
            splitterProportionKey = "AndroidHelper.Network.split"
        }, BorderLayout.CENTER)

        session.addListener(this)
        updateCounter()
        startDemoCycle()
    }

    /** 开发用：`-Dandroidhelper.demoCycle=true` 时每 5 秒自动选下一行，方便无人值守地截图检查详情页。 */
    private fun startDemoCycle() {
        if (System.getProperty("androidhelper.demoCycle") != "true") return
        javax.swing.Timer(5000) {
            if (table.rowCount == 0) return@Timer
            val next = (table.selectedRow + 1) % table.rowCount
            table.setRowSelectionInterval(next, next)
        }.start()
    }

    override fun onData(changes: NetStore.Changes) {
        if (changes.descriptorsChanged) {
            registry = store.descriptors?.let { ProtoRegistry.from(it) }
        }
        val selected = selectedCall()
        if (changes.removed > 0) {
            model.fireTableDataChanged()
        } else {
            val size = store.calls.size
            if (changes.added > 0) model.fireTableRowsInserted(size - changes.added, size - 1)
            for (c in changes.updated) {
                val row = model.rowOf(c)
                if (row >= 0) model.fireTableRowsUpdated(row, row)
            }
        }
        // fireTableDataChanged 会清掉选择，按对象找回来
        if (selected != null) {
            val row = model.rowOf(selected)
            if (row >= 0) {
                val viewRow = table.convertRowIndexToView(row)
                if (viewRow >= 0 && table.selectedRow != viewRow) table.setRowSelectionInterval(viewRow, viewRow)
                if (selected in changes.updated) detail.refresh()
            } else {
                detail.show(null)
            }
        } else if (changes.removed > 0) {
            detail.show(null)
        }
        if (changes.added > 0 && followTail && table.rowCount > 0) {
            SwingUtilities.invokeLater {
                table.scrollRectToVisible(table.getCellRect(table.rowCount - 1, 0, true))
            }
        }
        updateCounter()
    }

    private fun selectedCall(): NetCall? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        return model.callAt(table.convertRowIndexToModel(viewRow))
    }

    private fun applyFilter() {
        val text = filter.text.trim().lowercase()
        val kind = when (kindFilter.selectedItem) {
            "HTTP" -> Kind.HTTP
            "gRPC" -> Kind.GRPC
            "WS" -> Kind.WS
            else -> null
        }
        sorter.rowFilter = if (text.isEmpty() && kind == null) null else object : RowFilter<CallTableModel, Int>() {
            override fun include(entry: Entry<out CallTableModel, out Int>): Boolean {
                val c = model.callAt(entry.identifier) ?: return false
                if (kind != null && c.kind != kind) return false
                if (text.isEmpty()) return true
                return listOf(c.url, c.grpcMethod, c.displayHost, c.displayMethod, c.displayStatus)
                    .any { it?.lowercase()?.contains(text) == true }
            }
        }
        updateCounter()
    }

    private fun updateCounter() {
        val total = store.calls.size
        val shown = table.rowCount
        counter.text = buildString {
            append(if (shown == total) "$total 条" else "$shown / $total 条")
            if (store.dropped > 0) append("   ⚠ 设备侧丢弃 ${store.dropped} 条（队列满）")
        }
    }

    private fun maybePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        if (row >= 0) table.setRowSelectionInterval(row, row)
        val call = selectedCall() ?: return
        val menu = JPopupMenu()
        fun item(title: String, enabled: Boolean = true, text: () -> String?) {
            menu.add(JMenuItem(title).apply {
                isEnabled = enabled
                addActionListener { text()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) } }
            })
        }
        item("复制 URL", call.url != null || call.grpcMethod != null) { call.url ?: call.grpcMethod }
        item("复制为 cURL", call.kind == Kind.HTTP) { Curl.of(call) }
        item("复制响应 Body", call.respBody != null) { call.respBody?.let { BodyFormat.json(it.bytes) ?: BodyFormat.text(it.bytes) } }
        menu.show(table, e.x, e.y)
    }
}

/** 把一个 HTTP 调用还原成 cURL 命令。被设备端脱敏过的字段保持 `<redacted>`，需要手动补。 */
object Curl {
    fun of(c: NetCall): String = buildString {
        append("curl")
        if (c.method != null && c.method != "GET") append(" -X ").append(c.method)
        append(' ').append(quote(c.url ?: ""))
        for ((k, v) in c.reqHeaders) append(" \\\n  -H ").append(quote("$k: $v"))
        val ct = c.reqType
        if (ct != null && c.reqHeaders.none { it.first.equals("content-type", true) }) {
            append(" \\\n  -H ").append(quote("Content-Type: $ct"))
        }
        c.reqBody?.let { body ->
            if (BodyFormat.looksTextual(body.bytes)) {
                append(" \\\n  --data-raw ").append(quote(String(body.bytes, Charsets.UTF_8)))
            } else {
                append(" \\\n  # 二进制请求体（${body.bytes.size} B）没有包含在命令里")
            }
        }
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

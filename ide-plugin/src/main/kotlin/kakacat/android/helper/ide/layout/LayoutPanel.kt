package kakacat.android.helper.ide.layout

import kakacat.android.helper.client.layout.*
import kakacat.android.helper.ide.HelperBundle
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kakacat.android.helper.ide.HelperSession
import kakacat.android.helper.client.network.str
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Layout 标签页：截图 | View 树 + 跳转按钮 + 属性表。
 *
 * 只在点 ↻（或第一次切到这个标签）时抓取一次 —— 不做持续刷新，这是它在弱机器上
 * 比 Layout Inspector 轻的根本原因。
 */
class LayoutPanel(private val session: HelperSession) : JPanel(BorderLayout()), HelperSession.Listener {

    private val navigator = SourceNavigator(session.project)
    private val screen = ScreenView()
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = HelperBundle.message("layout.search") }
    private val showHidden = JBCheckBox(HelperBundle.message("layout.showHidden"))
    private val grabButton = JButton(HelperBundle.message("layout.grab"))
    private val status = JBLabel()
    private val jumpBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    private val props = PropertyModel()
    private val propTable = JBTable(props)

    private var snapshot: LayoutSnapshot? = null
    private var grabbing = false

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(grabButton)
            add(showHidden)
            add(search)
            add(status)
        }
        grabButton.toolTipText = HelperBundle.message("layout.grab.tip")
        grabButton.addActionListener { grab() }
        showHidden.addActionListener {
            screen.showHidden = showHidden.isSelected
            screen.repaint()
        }
        search.addKeyboardListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) searchNext(fromCurrent = true)
            }
        })
        search.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = searchNext(fromCurrent = false)
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) {}
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) {}
        })

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        tree.addTreeSelectionListener {
            val n = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? LNode
            if (n != null) showNode(n, fromTree = true)
        }
        screen.onPick = { showNode(it, fromTree = false) }

        propTable.setShowGrid(false)
        propTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(120)
        propTable.columnModel.getColumn(0).maxWidth = JBUI.scale(170)
        propTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(260)
        propTable.putClientProperty("terminateEditOnFocusLost", true)
        props.onEdit = { key, value -> edit(key, value) }

        val detail = JPanel(BorderLayout()).apply {
            add(jumpBar, BorderLayout.NORTH)
            add(JBScrollPane(propTable), BorderLayout.CENTER)
        }
        val right = JBSplitter(true, 0.55f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = detail
            splitterProportionKey = "AndroidHelper.Layout.treeSplit"
        }
        val main = JBSplitter(false, 0.38f).apply {
            firstComponent = screen
            secondComponent = right
            splitterProportionKey = "AndroidHelper.Layout.split"
        }
        add(toolbar, BorderLayout.NORTH)
        add(main, BorderLayout.CENTER)

        session.addListener(this)
        updateJumpBar(null)
        updateStatus()
    }

    /** 标签页被切到前台时调用：还没抓过就自动抓一次。 */
    fun onShown() {
        if (snapshot == null && canGrab()) grab()
    }

    override fun onStateChanged(state: HelperSession.State, message: String?) {
        updateStatus()
        if (state == HelperSession.State.CONNECTED && isShowing && snapshot == null) grab()
    }

    private fun canGrab() = session.state == HelperSession.State.CONNECTED && session.supports("layout")

    fun grab() {
        if (grabbing) return
        if (!canGrab()) {
            updateStatus()
            return
        }
        grabbing = true
        updateStatus()
        val keep = selectedNode()?.let { n -> snapshot?.windowOf(n)?.index?.let { it to n.path } }
        val req = JsonObject().apply {
            addProperty("type", "layout.snapshot")
            addProperty("maxWidth", 1440)
            addProperty("quality", 80)
        }
        val t0 = System.currentTimeMillis()
        session.request(req) { result ->
            result.onFailure {
                grabbing = false
                updateStatus()
                status.text = HelperBundle.message("layout.grabFailed", it.message ?: "")
            }
            result.onSuccess { resp ->
                if (!resp.ok) {
                    grabbing = false
                    updateStatus()
                    status.text = HelperBundle.message("layout.grabFailed", resp.error ?: "")
                    return@onSuccess
                }
                val snap = LayoutSnapshot.parse(resp.json)
                // JPEG 解码放后台，几张全屏图在 EDT 上解也要几十毫秒
                ApplicationManager.getApplication().executeOnPooledThread {
                    for (w in snap.windows) {
                        val bytes = resp.blobs.entries.firstOrNull { it.key.endsWith(":${w.index}") }?.value ?: continue
                        w.image = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()
                    }
                    SwingUtilities.invokeLater {
                        grabbing = false
                        updateStatus()
                        apply(snap, keep, System.currentTimeMillis() - t0)
                    }
                }
            }
        }
    }

    private fun apply(snap: LayoutSnapshot, keep: Pair<Int, List<Int>>?, totalMs: Long) {
        snapshot = snap
        screen.snapshot = snap
        val root = DefaultMutableTreeNode()
        for (w in snap.windows) {
            val wn = DefaultMutableTreeNode(w)
            wn.add(treeNode(w.root))
            root.add(wn)
        }
        treeModel.setRoot(root)
        expandTo(depth = 5)
        status.text = HelperBundle.message("layout.summary", snap.windows.size.toString(), snap.nodeCount.toString(), (snap.treeMs + snap.imageMs).toString(), totalMs.toString())

        val restore = keep?.let { (w, path) -> snap.findByPath(w, path) }
            ?: System.getProperty("androidhelper.demoSelect")?.let { text -> findFirst { it.text == text } }
        if (restore != null) showNode(restore, fromTree = false) else showNode(null, fromTree = false)
        // 开发用：-Dandroidhelper.demoJump=click 自动跳一次，验证源码跳转
        if (keep == null && restore != null) {
            System.getProperty("androidhelper.demoJump")?.let { k -> restore.sources[k]?.let(navigator::openSite) }
        }
    }

    /**
     * 同步展开到指定深度（窗口节点算第 1 层）。不用 TreeUtil.expand：它在新版平台上是异步的，
     * 展开完成时会把我们刚设置的选中项冲掉。
     */
    private fun expandTo(depth: Int) {
        var row = 0
        while (row < tree.rowCount) {
            val path = tree.getPathForRow(row)
            if (path.pathCount - 1 < depth) tree.expandRow(row)
            row++
        }
    }

    private fun treeNode(n: LNode): DefaultMutableTreeNode {
        val t = DefaultMutableTreeNode(n)
        n.children.forEach { t.add(treeNode(it)) }
        return t
    }

    private fun selectedNode(): LNode? = screen.selected

    private fun showNode(n: LNode?, fromTree: Boolean) {
        screen.selected = n
        props.show(n, snapshot?.density ?: 1f)
        updateJumpBar(n)
        if (!fromTree && n != null) {
            val path = findTreePath(n)
            if (path != null && tree.selectionPath != path) {
                tree.selectionPath = path
                tree.scrollPathToVisible(path)
            }
        }
    }

    private fun findTreePath(n: LNode): TreePath? {
        val root = treeModel.root as DefaultMutableTreeNode
        val e = root.depthFirstEnumeration()
        while (e.hasMoreElements()) {
            val t = e.nextElement() as DefaultMutableTreeNode
            if (t.userObject === n) return TreePath(t.path)
        }
        return null
    }

    private fun findFirst(pred: (LNode) -> Boolean): LNode? {
        val snap = snapshot ?: return null
        for (w in snap.windows) {
            var found: LNode? = null
            w.root.walk { if (found == null && pred(it)) found = it }
            if (found != null) return found
        }
        return null
    }

    private fun searchNext(fromCurrent: Boolean) {
        val q = search.text.trim().lowercase()
        val snap = snapshot ?: return
        if (q.isEmpty()) return
        val all = ArrayList<LNode>()
        snap.windows.forEach { w -> w.root.walk { all.add(it) } }
        val start = if (fromCurrent) all.indexOf(selectedNode()) + 1 else 0
        val match = (all.drop(start) + all.take(start)).firstOrNull { n ->
            n.cls.lowercase().contains(q) || n.rid?.lowercase()?.contains(q) == true || n.text?.lowercase()?.contains(q) == true
        }
        if (match != null) showNode(match, fromTree = false)
    }

    /** 跳转按钮：只显示这个节点有的那几个。 */
    private fun updateJumpBar(n: LNode?) {
        jumpBar.removeAll()
        if (n == null) {
            jumpBar.add(JBLabel(HelperBundle.message("layout.selectHint")).apply { foreground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor })
        } else {
            val src = n.sources
            fun jump(label: String, tip: String?, action: () -> Unit) {
                jumpBar.add(JButton(label).apply {
                    toolTipText = tip
                    addActionListener { action() }
                })
            }
            src["click"]?.let { s -> jump(HelperBundle.message("jump.click"), s) { navigator.openSite(s) } }
            src["longClick"]?.let { s -> jump(HelperBundle.message("jump.longClick"), s) { navigator.openSite(s) } }
            src["touch"]?.let { s -> jump(HelperBundle.message("jump.touch"), s) { navigator.openSite(s) } }
            src["created"]?.let { s -> jump(HelperBundle.message("jump.created"), s) { navigator.openSite(s) } }
            // 布局文件和 inflate 位置可能在祖先上（xml 里的子 View 没有单独记录）
            val inflatedFrom = generateSequence(n) { it.parent }.firstOrNull { it.sources["layout"] != null }
            inflatedFrom?.sources?.get("layout")?.let { name -> jump(HelperBundle.message("jump.layout", name), null) { navigator.openLayout(name) } }
            inflatedFrom?.sources?.get("inflated")?.let { s -> jump(HelperBundle.message("jump.inflated"), s) { navigator.openSite(s) } }
            generateSequence(n) { it.parent }.firstNotNullOfOrNull { it.sources["shown"] }
                ?.let { s -> jump(HelperBundle.message("jump.shown"), s) { navigator.openSite(s) } }
            generateSequence(n) { it.parent }.firstNotNullOfOrNull { it.fragment }
                ?.let { f -> jump(HelperBundle.message("jump.fragment"), f) { navigator.openClass(f) } }
            snapshot?.windowOf(n)?.activity?.let { a -> jump(HelperBundle.message("jump.activity"), a) { navigator.openClass(a) } }
            if (!n.compose && !n.cls.startsWith("android.") && !n.cls.startsWith("androidx.") && !n.cls.startsWith("com.android.")) {
                jump(HelperBundle.message("jump.viewClass"), n.cls) { navigator.openClass(n.cls) }
            }
        }
        jumpBar.revalidate()
        jumpBar.repaint()
    }

    private fun edit(key: String, value: String) {
        val node = selectedNode() ?: return
        val req = JsonObject().apply {
            addProperty("type", "layout.set")
            addProperty("node", node.id)
            addProperty("key", key)
            addProperty("value", value)
        }
        session.request(req) { result ->
            val error = result.exceptionOrNull()?.message ?: result.getOrNull()?.takeIf { !it.ok }?.error
            if (error != null) {
                NotificationGroupManager.getInstance().getNotificationGroup("DroidLens")
                    .createNotification(HelperBundle.message("layout.editFailed", key, error), NotificationType.WARNING).notify(session.project)
            }
            grab() // 不管成没成功都重新抓一次，界面以设备上的真实状态为准
        }
    }

    private fun updateStatus() {
        grabButton.isEnabled = canGrab() && !grabbing
        when {
            grabbing -> status.text = HelperBundle.message("layout.grabbing")
            session.state != HelperSession.State.CONNECTED -> status.text = HelperBundle.message("layout.connectFirst")
            !session.supports("layout") -> status.text = HelperBundle.message("layout.unsupported")
            snapshot == null -> status.text = ""
        }
    }

    private class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                is LWindow -> {
                    append(o.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    o.activity?.let { append("  ${it.substringAfterLast('.')}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
                is LNode -> {
                    val base = if (o.isShown) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES
                    // Compose 语义节点用斜体，和真正的 View 区分开
                    val name = if (o.cls.startsWith("Compose.")) SimpleTextAttributes(base.style or SimpleTextAttributes.STYLE_ITALIC, base.fgColor) else base
                    append(o.simpleName, name)
                    o.rid?.let { append("  #${it.substringAfter(":id/")}", SimpleTextAttributes.GRAY_ATTRIBUTES) }
                    o.text?.takeIf { it.isNotBlank() }?.let {
                        append("  \"${if (it.length > 24) it.take(24) + "…" else it}\"", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
                    }
                    if (!o.isVisible) append("  ${if (o.visibility == 'G') "gone" else "invisible"}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    if (o.sources.isNotEmpty()) append("  ◆", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, com.intellij.ui.JBColor.ORANGE))
                }
            }
        }
    }
}

/**
 * 属性表。第二列对可修改的属性可编辑，回车后发 `layout.set` 到设备。
 * 值的格式和设备端 PropertyEditor 接受的一致。
 */
private class PropertyModel : AbstractTableModel() {

    class Row(val label: String, val value: String, val editKey: String? = null)

    private var rows: List<Row> = emptyList()
    var onEdit: (String, String) -> Unit = { _, _ -> }

    fun show(n: LNode?, density: Float) {
        rows = if (n == null) emptyList() else build(n, density)
        fireTableDataChanged()
    }

    private fun build(n: LNode, density: Float): List<Row> {
        val j = n.json
        val editable = !n.cls.startsWith("Compose.")
        fun key(k: String) = if (editable) k else null
        fun dp(px: Number) = "%.1f".format(px.toFloat() / density).removeSuffix(".0")
        fun arr(name: String): String? = (j.get(name) as? JsonArray)?.joinToString(", ") { e ->
            val d = e.asDouble
            if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(d)
        }
        fun lp(v: Int) = when (v) { -1 -> "match_parent"; -2 -> "wrap_content"; else -> "$v px (${dp(v)} dp)" }

        val out = ArrayList<Row>()
        out.add(Row("class", n.cls))
        n.rid?.let { out.add(Row(if (n.compose) "testTag" else "id", it)) }
        val b = n.bounds
        out.add(Row("bounds", "[${b.x}, ${b.y}]  ${b.width}×${b.height} px  (${dp(b.width)}×${dp(b.height)} dp)"))
        out.add(Row("visibility", n.visibility.toString(), key("visibility")))
        j.get("alpha")?.let { out.add(Row("alpha", it.asString, key("alpha"))) } ?: if (editable) out.add(Row("alpha", "1", "alpha")) else Unit
        if (editable) out.add(Row("enabled", (j.get("enabled")?.asBoolean ?: true).toString(), "enabled"))
        val flags = listOf("clickable", "longClickable", "focusable", "focused", "selected").filter { j.get(it)?.asBoolean == true }
        if (flags.isNotEmpty()) out.add(Row("flags", flags.joinToString(", ")))
        j.get("lp")?.asJsonArray?.let { out.add(Row("layout w, h", "${lp(it[0].asInt)}, ${lp(it[1].asInt)}", key("lp"))) }
        arr("padding")?.let { out.add(Row("padding", it, key("padding"))) }
        arr("margin")?.let { out.add(Row("margin", it, key("margin"))) }
        arr("translation")?.let { out.add(Row("translation", it, key("translation"))) }
        arr("scale")?.let { out.add(Row("scale", it, key("scale"))) }
        arr("scroll")?.let { out.add(Row("scroll", it)) }
        j.str("elevation")?.let { out.add(Row("elevation", it, key("elevation"))) }
        j.str("bg")?.let { out.add(Row("background", it, key("bg"))) }
        j.str("bgDrawable")?.let { out.add(Row("background", it, key("bg"))) }
        if (j.has("text")) out.add(Row("text", j.str("text") ?: "", key("text")))
        j.str("hint")?.let { out.add(Row("hint", it, key("hint"))) }
        j.get("textSize")?.let { out.add(Row("textSize", "%.1f".format(it.asFloat).removeSuffix(".0"), key("textSize"))) }
        j.str("textColor")?.let { out.add(Row("textColor", it, key("textColor"))) }
        j.str("desc")?.let { out.add(Row("contentDescription", it)) }
        j.str("scaleType")?.let { out.add(Row("scaleType", it)) }
        j.str("drawable")?.let { out.add(Row("drawable", it)) }
        j.str("fragment")?.let { out.add(Row("fragment", it)) }
        j.getAsJsonObject("semantics")?.entrySet()?.forEach { (k, v) -> out.add(Row("semantics.$k", v.asString)) }
        for ((k, v) in n.sources) out.add(Row("source.$k", v))
        return out
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 2
    override fun getColumnName(column: Int) = if (column == 0) HelperBundle.message("layout.col.property") else HelperBundle.message("layout.col.value")
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].let { if (columnIndex == 0) it.label else it.value }
    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 1 && rows[rowIndex].editKey != null

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val row = rows[rowIndex]
        val key = row.editKey ?: return
        val v = aValue?.toString() ?: return
        if (v == row.value) return
        onEdit(key, v)
    }
}

package kakacat.android.helper.client.layout

import com.google.gson.JsonObject
import kakacat.android.helper.client.network.bool
import kakacat.android.helper.client.network.int
import kakacat.android.helper.client.network.long
import kakacat.android.helper.client.network.str
import java.awt.Rectangle
import java.awt.image.BufferedImage

/** 一个 View（或 Compose 语义节点）。坐标是屏幕坐标。 */
class LNode(
    val id: Int,
    val cls: String,
    val rid: String?,
    val bounds: Rectangle,
    val visibility: Char,
    val compose: Boolean,
    /** 原始 JSON，属性表直接从这里取。 */
    val json: JsonObject,
    val parent: LNode?,
) {
    val children = mutableListOf<LNode>()

    val simpleName: String get() = cls.substringAfterLast('.').substringAfterLast('$')
    val text: String? get() = json.str("text")
    val isVisible: Boolean get() = visibility == 'V'

    /** 自己和所有祖先都可见。 */
    val isShown: Boolean get() = isVisible && (parent?.isShown ?: true)

    /** 插桩记下的源码位置：created / click / longClick / touch / layout / inflated / shown。 */
    val sources: Map<String, String> =
        json.getAsJsonObject("src")?.entrySet()?.associate { (k, v) -> k to v.asString } ?: emptyMap()

    val fragment: String? get() = json.str("fragment")

    /** 从窗口根到自己的子节点下标路径。重新抓取后靠它找回"同一个"节点。 */
    val path: List<Int>
        get() {
            val out = ArrayList<Int>()
            var n: LNode = this
            while (true) {
                val p = n.parent ?: break
                out.add(p.children.indexOf(n))
                n = p
            }
            return out.asReversed()
        }

    fun walk(visit: (LNode) -> Unit) {
        visit(this)
        children.forEach { it.walk(visit) }
    }

    override fun toString(): String = buildString {
        append(simpleName)
        rid?.let { append("  #").append(it.substringAfter(":id/")) }
        text?.takeIf { it.isNotBlank() }?.let { append("  \"").append(if (it.length > 30) it.take(30) + "…" else it).append('"') }
    }
}

class LWindow(
    val index: Int,
    /** Activity / Dialog / Popup / Window(type)。 */
    val kind: String?,
    val title: String?,
    val activity: String?,
    val bounds: Rectangle,
    val root: LNode,
) {
    var image: BufferedImage? = null

    override fun toString(): String = "#$index ${kind ?: title?.substringAfterLast('/')?.substringAfterLast('.') ?: "Window"}"
}

class LayoutSnapshot(
    val windows: List<LWindow>,
    val screenWidth: Int,
    val screenHeight: Int,
    val density: Float,
    val treeMs: Long,
    val imageMs: Long,
    val nodeCount: Int,
) {
    /** 所有窗口合起来的范围，截图面板按它缩放。 */
    val canvas: Rectangle
        get() {
            val r = Rectangle(0, 0, screenWidth, screenHeight)
            windows.forEach { r.add(it.bounds) }
            return r
        }

    fun findById(id: Int): LNode? {
        windows.forEach { w -> find(w.root) { it.id == id }?.let { return it } }
        return null
    }

    /** 按窗口序号 + 子节点路径找节点（重新抓取后恢复选中用）。 */
    fun findByPath(window: Int, path: List<Int>): LNode? {
        var n = windows.firstOrNull { it.index == window }?.root ?: return null
        for (i in path) n = n.children.getOrNull(i) ?: return null
        return n
    }

    fun windowOf(node: LNode): LWindow? {
        var root = node
        while (root.parent != null) root = root.parent!!
        return windows.firstOrNull { it.root === root }
    }

    /**
     * 点击截图选 View：在最上层包含该点的窗口里，找包含该点、面积最小的可见节点。
     * [skip] 个更小的候选会被跳过 —— 在同一点连续点击时逐层往外选。
     */
    fun pick(x: Int, y: Int, includeHidden: Boolean, skip: Int = 0): LNode? {
        for (w in windows.asReversed()) {
            if (!w.bounds.contains(x, y)) continue
            val hits = ArrayList<LNode>()
            w.root.walk { n ->
                if ((includeHidden || n.isShown) && n.bounds.width > 0 && n.bounds.height > 0 && n.bounds.contains(x, y)) hits.add(n)
            }
            if (hits.isEmpty()) continue
            val sorted = hits.sortedWith(compareBy<LNode> { it.bounds.width.toLong() * it.bounds.height }.thenByDescending { depth(it) })
            return sorted[skip % sorted.size]
        }
        return null
    }

    private fun depth(n: LNode): Int {
        var d = 0
        var p = n.parent
        while (p != null) { d++; p = p.parent }
        return d
    }

    private fun find(n: LNode, pred: (LNode) -> Boolean): LNode? {
        if (pred(n)) return n
        n.children.forEach { c -> find(c, pred)?.let { return it } }
        return null
    }

    companion object {
        fun parse(json: JsonObject): LayoutSnapshot {
            val windows = json.getAsJsonArray("windows").map { it.asJsonObject }.map { w ->
                LWindow(
                    index = w.int("index") ?: 0,
                    kind = w.str("kind"),
                    title = w.str("title"),
                    activity = w.str("activity"),
                    bounds = rect(w),
                    root = node(w.getAsJsonObject("root"), null),
                )
            }
            val screen = json.getAsJsonObject("screen")
            val cost = json.getAsJsonObject("costMs")
            return LayoutSnapshot(
                windows = windows,
                screenWidth = screen?.int("w") ?: 0,
                screenHeight = screen?.int("h") ?: 0,
                density = screen?.get("density")?.asFloat ?: 1f,
                treeMs = cost?.long("tree") ?: 0,
                imageMs = cost?.long("image") ?: 0,
                nodeCount = cost?.int("nodes") ?: 0,
            )
        }

        private fun node(o: JsonObject, parent: LNode?): LNode {
            val n = LNode(
                id = o.int("id") ?: -1,
                cls = o.str("cls") ?: "?",
                rid = o.str("rid"),
                bounds = rect(o),
                visibility = o.str("vis")?.firstOrNull() ?: 'V',
                compose = o.bool("compose"),
                json = o,
                parent = parent,
            )
            o.getAsJsonArray("children")?.forEach { n.children.add(node(it.asJsonObject, n)) }
            return n
        }

        private fun rect(o: JsonObject) = Rectangle(o.int("x") ?: 0, o.int("y") ?: 0, o.int("w") ?: 0, o.int("h") ?: 0)
    }
}

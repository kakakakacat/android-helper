package kakacat.android.helper.mcp

import kakacat.android.helper.client.layout.LNode
import kakacat.android.helper.client.layout.LWindow
import kakacat.android.helper.client.layout.LayoutSnapshot
import kakacat.android.helper.client.network.CallState
import kakacat.android.helper.client.network.Kind
import kakacat.android.helper.client.network.NetCall
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 给 agent 看的文本格式。设计目标是**省 token 又不丢关键信息**：
 * 一个 View 一行，缩进表示层级，id 用来在后续调用里引用它。
 *
 * ```
 * [6] Button "GET json" (48,355 544x96) clickable click=MainActivity.kt:66
 * ```
 */
object Format {

    /** `pkg.Class.method(File.kt:42)` → `File.kt:42`。 */
    fun shortSite(site: String): String = site.substringAfterLast('(').removeSuffix(")")

    fun nodeLine(n: LNode): String = buildString {
        append('[').append(n.id).append("] ").append(n.simpleName)
        n.rid?.let { append('#').append(it.substringAfter(":id/")) }
        n.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(clip(it, 60)).append('"') }
        n.json.get("desc")?.asString?.takeIf { it.isNotBlank() }?.let { append(" desc=\"").append(clip(it, 40)).append('"') }
        val b = n.bounds
        append(" (").append(b.x).append(',').append(b.y).append(' ').append(b.width).append('x').append(b.height).append(')')
        when (n.visibility) {
            'G' -> append(" gone")
            'I' -> append(" invisible")
        }
        if (n.json.get("clickable")?.asBoolean == true) append(" clickable")
        if (n.json.get("enabled")?.asBoolean == false) append(" disabled")
        if (n.json.get("selected")?.asBoolean == true) append(" selected")
        if (n.json.get("focused")?.asBoolean == true) append(" focused")
        n.json.get("alpha")?.asFloat?.let { append(" alpha=").append(it) }
        n.fragment?.let { append(" fragment=").append(it.substringAfterLast('.')) }
        for ((k, v) in n.sources) {
            append(' ').append(k).append('=').append(if (k == "layout") v else shortSite(v))
        }
    }

    /**
     * 渲染一个窗口。默认从 `android:id/content` 开始（跳过 DecorView、ActionBar 这些框架外壳），
     * 隐藏的子树不展开。返回渲染了多少个节点。
     */
    fun window(
        out: StringBuilder, snap: LayoutSnapshot, w: LWindow, from: LNode?,
        maxDepth: Int, includeHidden: Boolean, includeSystem: Boolean, budget: IntArray,
    ) {
        out.append("== window ").append(w.index).append(' ').append(w.kind ?: "")
        w.activity?.let { out.append(' ').append(it.substringAfterLast('.')) }
        out.append(" [").append(w.bounds.x).append(',').append(w.bounds.y).append(' ')
            .append(w.bounds.width).append('x').append(w.bounds.height).append("]\n")
        val start = from ?: if (includeSystem) w.root else contentRoot(w.root) ?: w.root
        val roots = if (from == null && !includeSystem && start !== w.root) start.children else listOf(start)
        var hidden = 0
        fun walk(n: LNode, depth: Int) {
            if (!includeHidden && !n.isVisible) {
                hidden++
                return
            }
            if (budget[0] <= 0) return
            budget[0]--
            repeat(depth) { out.append("  ") }
            out.append(nodeLine(n)).append('\n')
            if (depth + 1 > maxDepth) {
                if (n.children.isNotEmpty()) {
                    repeat(depth + 1) { out.append("  ") }
                    out.append("… ").append(n.children.size).append(" children (use root_id=").append(n.id).append(")\n")
                }
                return
            }
            n.children.forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        if (hidden > 0) out.append("(").append(hidden).append(" hidden views omitted; include_hidden=true to show)\n")
    }

    private fun contentRoot(root: LNode): LNode? {
        var found: LNode? = null
        root.walk { if (found == null && it.rid == "android:id/content") found = it }
        return found
    }

    /** 节点的全部属性，给 get_view 用。 */
    fun nodeDetail(snap: LayoutSnapshot, n: LNode): String = buildString {
        appendLine(nodeLine(n))
        appendLine("class: ${n.cls}")
        val d = snap.density.takeIf { it > 0 } ?: 1f
        val b = n.bounds
        appendLine("bounds: x=${b.x} y=${b.y} w=${b.width} h=${b.height} px (${dp(b.width, d)}x${dp(b.height, d)} dp, density $d)")
        snap.windowOf(n)?.let { w -> appendLine("window: ${w.index} ${w.kind ?: ""} ${w.activity ?: ""}".trimEnd()) }
        val skip = setOf("id", "cls", "x", "y", "w", "h", "children", "src", "semantics")
        for ((k, v) in n.json.entrySet()) {
            if (k in skip) continue
            appendLine("$k: ${if (v.isJsonPrimitive) v.asString else v.toString()}")
        }
        n.json.getAsJsonObject("semantics")?.entrySet()?.forEach { (k, v) -> appendLine("semantics.$k: ${v.asString}") }
        if (n.sources.isNotEmpty()) {
            appendLine("sources:")
            for ((k, v) in n.sources) appendLine("  $k: $v")
        }
        // xml 里的子 View 没有单独记录，给出最近的祖先的布局文件
        if (n.sources["layout"] == null) {
            generateSequence(n.parent) { it.parent }.firstOrNull { it.sources["layout"] != null }?.let {
                appendLine("inside layout: ${it.sources["layout"]}.xml (inflated at ${it.sources["inflated"]?.let(::shortSite)}, node [${it.id}])")
            }
        }
        val path = generateSequence(n.parent) { it.parent }.toList().asReversed()
        if (path.isNotEmpty()) appendLine("parents: " + path.joinToString(" > ") { "[${it.id}] ${it.simpleName}${it.rid?.let { r -> "#" + r.substringAfter(":id/") } ?: ""}" })
        if (n.children.isNotEmpty()) {
            appendLine("children (${n.children.size}):")
            n.children.take(30).forEach { appendLine("  " + nodeLine(it)) }
            if (n.children.size > 30) appendLine("  …")
        }
        if (!n.cls.startsWith("Compose.")) {
            appendLine("editable keys: visibility(V/I/G) alpha enabled text hint textSize(px) textColor(#AARRGGBB) bg(#AARRGGBB) padding(l,t,r,b px) margin(l,t,r,b px) lp(w,h: px|match_parent|wrap_content) translation(x,y) scale(x,y) elevation")
        }
    }

    private fun dp(px: Int, d: Float): String {
        val v = px / d
        return if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
    }

    private val TIME = SimpleDateFormat("HH:mm:ss")

    fun time(ms: Long): String = TIME.format(Date(ms))

    fun callLine(c: NetCall): String = buildString {
        append('#').append(c.index).append(' ').append(time(c.startTime)).append(' ')
        append(c.kind.label).append(' ')
        if (c.kind == Kind.HTTP) append(c.method ?: "?").append(' ')
        append(c.displayStatus).append(' ')
        when (c.kind) {
            Kind.GRPC -> append(c.grpcMethod ?: "?").append(" @").append(c.authority ?: "")
            else -> append(clip(c.url ?: c.name, 140))
        }
        c.durationMs?.let { append(' ').append(it).append("ms") }
        val size = c.displaySize
        if (size > 0) append(' ').append(size).append('B')
        if (c.messages.isNotEmpty()) append(" msgs=").append(c.messages.size)
        if (c.error != null) append(" error=").append(clip(c.error!!, 80))
        if (c.state == CallState.PENDING && c.error == null) append(" (pending)")
    }

    fun clip(s: String, max: Int): String {
        val one = s.replace('\n', ' ')
        return if (one.length > max) one.take(max) + "…" else one
    }
}

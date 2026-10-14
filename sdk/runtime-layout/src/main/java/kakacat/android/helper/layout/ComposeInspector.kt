package kakacat.android.helper.layout

import android.view.View
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * Compose 页面：从 `AndroidComposeView` 的语义树（semantics）里还原结构。
 *
 * 全部走反射，不依赖 compose 库 —— 没有 Compose 的 App 永远走不到这里。语义树是公开 API
 * （测试框架和无障碍都靠它），跨版本稳定；能拿到 bounds、文本、contentDescription、testTag、
 * Role、是否可点击，拿不到 Composable 函数名（那需要 ui-tooling 的 slot table）。
 *
 * 用**未合并**的树：合并树会把一个按钮里的文字和图标压成一个节点，看布局时反而不直观。
 */
internal object ComposeInspector {

    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"

    private val USEFUL_ACTIONS = setOf("OnClick", "OnLongClick", "ScrollBy", "ScrollToIndex", "SetText", "SetProgress", "Expand", "Collapse", "Dismiss")

    fun isComposeView(v: View): Boolean = v.javaClass.name == COMPOSE_VIEW

    /** 返回语义树根节点的 JSON；拿不到时返回 null（整个 ComposeView 仍然作为一个普通 View 显示）。 */
    fun semantics(view: View, nodes: MutableList<WeakReference<View>?>): JSONObject? = try {
        val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view)
        val root = owner.javaClass.getMethod("getUnmergedRootSemanticsNode").invoke(owner)
        val origin = IntArray(2)
        view.rootView.getLocationOnScreen(origin)
        node(root, origin, nodes, 0)
    } catch (t: Throwable) {
        null
    }

    private fun node(n: Any, origin: IntArray, nodes: MutableList<WeakReference<View>?>, depth: Int): JSONObject {
        val o = JSONObject()
        o.put("id", nodes.size)
        nodes.add(null) // Compose 节点不支持改属性，占个位让 id 保持连续
        o.put("compose", true)

        // boundsInWindow 是相对窗口的，加上窗口在屏幕上的位置
        call(n, "getBoundsInWindow")?.let { r ->
            val l = (call(r, "getLeft") as Float).toInt()
            val t = (call(r, "getTop") as Float).toInt()
            val rr = (call(r, "getRight") as Float).toInt()
            val b = (call(r, "getBottom") as Float).toInt()
            o.put("x", origin[0] + l); o.put("y", origin[1] + t)
            o.put("w", rr - l); o.put("h", b - t)
        }
        o.put("vis", "V")

        var role: String? = null
        var text: String? = null
        var clickable = false
        val props = JSONObject()
        (call(n, "getConfig") as? Iterable<*>)?.forEach { entry ->
            val e = entry as? Map.Entry<*, *> ?: return@forEach
            val key = call(e.key!!, "getName") as? String ?: return@forEach
            val value = e.value
            when (key) {
                "Role" -> role = value.toString()
                "Text" -> text = (value as? List<*>)?.joinToString(" ") ?: value.toString()
                "EditableText" -> text = value.toString()
                "OnClick" -> clickable = true
            }
            val shown = short(value)
            // 无障碍动作大多是框架内部的（文本替换、取布局结果…），只留对看布局有意义的几个
            if (shown.startsWith("AccessibilityAction(")) {
                if (key in USEFUL_ACTIONS) props.put(key, "✓")
            } else {
                props.put(key, shown)
            }
        }
        val name = when {
            role != null -> role!!
            text != null -> "Text"
            clickable -> "Clickable"
            else -> "Node"
        }
        o.put("cls", "Compose.$name")
        text?.let { o.put("text", it) }
        (props.opt("TestTag") as? String)?.let { o.put("rid", it) }
        (props.opt("ContentDescription") as? String)?.let { o.put("desc", it) }
        if (clickable) o.put("clickable", true)
        if (props.length() > 0) o.put("semantics", props)

        if (depth < 120) {
            val children = (call(n, "getChildren") as? List<*>).orEmpty()
            if (children.isNotEmpty()) {
                o.put("children", org.json.JSONArray().apply {
                    for (c in children) if (c != null) put(node(c, origin, nodes, depth + 1))
                })
            }
        }
        return o
    }

    /** 语义值可能是 AnnotatedString 列表、AccessibilityAction 之类，统一转成短字符串。 */
    private fun short(v: Any?): String {
        val s = when (v) {
            null -> "null"
            is List<*> -> v.joinToString(", ")
            is Unit -> "true"
            else -> v.toString()
        }
        return if (s.length > 200) s.take(200) + "…" else s
    }

    private val methodCache = HashMap<Pair<Class<*>, String>, Method?>()

    private fun call(target: Any, name: String): Any? {
        val key = target.javaClass to name
        val m = methodCache.getOrPut(key) {
            runCatching { target.javaClass.getMethod(name) }.getOrNull()
        } ?: return null
        return runCatching { m.invoke(target) }.getOrNull()
    }
}

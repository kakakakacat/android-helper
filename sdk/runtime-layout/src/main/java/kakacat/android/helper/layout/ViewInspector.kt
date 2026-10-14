package kakacat.android.helper.layout

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * 把一个窗口的 View 树转成 JSON。必须在主线程调用。
 *
 * 坐标一律是**屏幕坐标**（getLocationOnScreen，含 translation），IDE 拿截图直接对齐，
 * 不用再关心窗口偏移。
 *
 * 每个节点分配一个本次抓取内唯一的 `id`，[nodes] 里按 id 存着 View 的弱引用，
 * 后续的属性修改请求用 id 找回 View。
 */
internal class ViewInspector(private val nodes: MutableList<WeakReference<View>?>) {

    private val loc = IntArray(2)
    private val fragmentTagId: Int by lazy { resolveFragmentTag() }
    private var context: android.content.Context? = null

    fun window(root: View, index: Int): JSONObject {
        context = root.context
        root.getLocationOnScreen(loc)
        val lp = root.layoutParams as? WindowManager.LayoutParams
        val activity = WindowRoots.activityOf(root)
        return JSONObject().apply {
            put("index", index)
            put("title", lp?.title?.toString() ?: JSONObject.NULL)
            put("type", lp?.type ?: 0)
            put("kind", when (val t = lp?.type ?: 0) {
                WindowManager.LayoutParams.TYPE_BASE_APPLICATION -> "Activity"
                in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..WindowManager.LayoutParams.LAST_APPLICATION_WINDOW -> "Dialog"
                in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW -> "Popup"
                else -> "Window($t)"
            })
            put("x", loc[0]); put("y", loc[1])
            put("w", root.width); put("h", root.height)
            put("activity", activity?.javaClass?.name ?: JSONObject.NULL)
            put("root", node(root, 0))
        }
    }

    private fun node(v: View, depth: Int): JSONObject {
        val id = nodes.size
        nodes.add(WeakReference(v))
        v.getLocationOnScreen(loc)
        val o = JSONObject()
        o.put("id", id)
        o.put("cls", v.javaClass.name)
        idName(v)?.let { o.put("rid", it) }
        o.put("x", loc[0]); o.put("y", loc[1]); o.put("w", v.width); o.put("h", v.height)
        o.put("vis", when (v.visibility) { View.VISIBLE -> "V"; View.INVISIBLE -> "I"; else -> "G" })
        if (v.alpha != 1f) o.put("alpha", v.alpha.toDouble())
        if (!v.isEnabled) o.put("enabled", false)
        if (v.isClickable) o.put("clickable", true)
        if (v.isLongClickable) o.put("longClickable", true)
        if (v.isFocusable) o.put("focusable", true)
        if (v.isFocused) o.put("focused", true)
        if (v.isSelected) o.put("selected", true)
        o.put("padding", JSONArray(listOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)))
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            o.put("margin", JSONArray(listOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)))
        }
        v.layoutParams?.let { o.put("lp", JSONArray(listOf(it.width, it.height))) }
        if (v.translationX != 0f || v.translationY != 0f) o.put("translation", JSONArray(listOf(v.translationX.toDouble(), v.translationY.toDouble())))
        if (v.scaleX != 1f || v.scaleY != 1f) o.put("scale", JSONArray(listOf(v.scaleX.toDouble(), v.scaleY.toDouble())))
        if (v.scrollX != 0 || v.scrollY != 0) o.put("scroll", JSONArray(listOf(v.scrollX, v.scrollY)))
        if (v.elevation != 0f) o.put("elevation", v.elevation.toDouble())
        (v.background as? ColorDrawable)?.let { o.put("bg", color(it.color)) }
            ?: v.background?.let { o.put("bgDrawable", it.javaClass.simpleName) }
        v.contentDescription?.let { o.put("desc", it.toString()) }
        if (v is TextView) {
            o.put("text", v.text?.toString() ?: "")
            v.hint?.let { o.put("hint", it.toString()) }
            o.put("textSize", v.textSize.toDouble())
            o.put("textColor", color(v.currentTextColor))
        }
        if (v is ImageView) {
            o.put("scaleType", v.scaleType?.name ?: "")
            v.drawable?.let { o.put("drawable", it.javaClass.simpleName) }
        }
        fragmentOf(v)?.let { o.put("fragment", it) }
        ViewSources.peek(v)?.let { s ->
            JSONObject().apply {
                s.created?.let { put("created", it) }
                s.click?.let { put("click", it) }
                s.longClick?.let { put("longClick", it) }
                s.touch?.let { put("touch", it) }
                s.layout?.let { put("layout", it) }
                s.inflated?.let { put("inflated", it) }
                s.shown?.let { put("shown", it) }
            }.takeIf { it.length() > 0 }?.let { o.put("src", it) }
        }

        val children = JSONArray()
        if (ComposeInspector.isComposeView(v)) {
            o.put("compose", true)
            ComposeInspector.semantics(v, nodes)?.let { children.put(it) }
        } else if (v is ViewGroup && depth < MAX_DEPTH) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i) ?: continue
                children.put(node(child, depth + 1))
            }
        }
        if (children.length() > 0) o.put("children", children)
        return o
    }

    private fun idName(v: View): String? {
        if (v.id == View.NO_ID) return null
        return try {
            val r = v.resources
            val pkg = r.getResourcePackageName(v.id)
            val name = r.getResourceEntryName(v.id)
            if (pkg == "android") "android:id/$name" else name
        } catch (t: Throwable) {
            "0x" + Integer.toHexString(v.id)
        }
    }

    /**
     * androidx Fragment 会在自己的根 View 上挂 `R.id.fragment_container_view_tag` → Fragment。
     * 不依赖 fragment 库：id 是合并进 App 的资源，按名字查就行；没有用 Fragment 的 App 查不到，直接跳过。
     */
    private fun fragmentOf(v: View): String? {
        if (fragmentTagId == 0) return null
        val tag = v.getTag(fragmentTagId) ?: return null
        return tag.javaClass.name
    }

    private fun resolveFragmentTag(): Int {
        val ctx = context ?: return 0
        return runCatching { ctx.resources.getIdentifier("fragment_container_view_tag", "id", ctx.packageName) }.getOrDefault(0)
    }

    private fun color(c: Int) = "#%08X".format(c)

    companion object {
        /** 防御极端嵌套（以及有环的自定义 ViewGroup）把主线程卡死。 */
        private const val MAX_DEPTH = 120

    }
}

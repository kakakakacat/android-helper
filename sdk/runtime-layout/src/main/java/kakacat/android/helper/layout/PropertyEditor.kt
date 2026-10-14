package kakacat.android.helper.layout

import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * IDE 里改属性。值一律是字符串，在这里按 key 解析；解析失败直接抛，错误信息会回到 IDE。
 *
 * 改的是运行时状态，不会写回源码 —— 用来快速试效果（"这个 padding 改成 24 好不好看"）。
 */
internal object PropertyEditor {

    fun apply(v: View, key: String, value: String) {
        when (key) {
            "visibility", "vis" -> v.visibility = when (value.trim().uppercase()) {
                "V", "VISIBLE" -> View.VISIBLE
                "I", "INVISIBLE" -> View.INVISIBLE
                "G", "GONE" -> View.GONE
                else -> throw IllegalArgumentException("visibility must be V, I or G")
            }
            "alpha" -> v.alpha = value.toFloat()
            "enabled" -> v.isEnabled = value.toBooleanStrict()
            "text" -> textView(v).text = value
            "hint" -> textView(v).hint = value
            "textSize" -> textView(v).setTextSize(TypedValue.COMPLEX_UNIT_PX, value.toFloat())
            "textColor" -> textView(v).setTextColor(Color.parseColor(value.trim()))
            "bg" -> v.setBackgroundColor(Color.parseColor(value.trim()))
            "padding" -> ints(value, 4).let { v.setPadding(it[0], it[1], it[2], it[3]) }
            "margin" -> {
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
                    ?: throw IllegalArgumentException("the parent layout does not support margins")
                val m = ints(value, 4)
                lp.setMargins(m[0], m[1], m[2], m[3])
                v.layoutParams = lp
            }
            "lp" -> {
                val lp = v.layoutParams ?: throw IllegalArgumentException("the view has no LayoutParams")
                val s = layoutSize(value)
                lp.width = s[0]; lp.height = s[1]
                v.layoutParams = lp
            }
            "translation" -> floats(value, 2).let { v.translationX = it[0]; v.translationY = it[1] }
            "scale" -> floats(value, 2).let { v.scaleX = it[0]; v.scaleY = it[1] }
            "elevation" -> v.elevation = value.toFloat()
            else -> throw IllegalArgumentException("cannot edit $key")
        }
        v.requestLayout()
        v.invalidate()
    }

    private fun textView(v: View) = v as? TextView ?: throw IllegalArgumentException("${v.javaClass.simpleName} is not a TextView")

    /** "[1, 2, 3, 4]" / "1,2,3,4" / "1 2 3 4" 都接受。 */
    private fun ints(value: String, n: Int): IntArray {
        val parts = value.trim().removePrefix("[").removeSuffix("]").split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        require(parts.size == n) { "expected $n integers, e.g. ${(1..n).joinToString(", ") { "0" }}" }
        return IntArray(n) { parts[it].toFloat().toInt() }
    }

    /** "match_parent, 120" / "-1, -2" / "300 px (150 dp), wrap_content"（IDE 显示的格式原样改回来也行）。 */
    private fun layoutSize(value: String): IntArray {
        val parts = value.split(',').map { it.trim().lowercase() }
        require(parts.size == 2) { "format: width, height (match_parent / wrap_content / px)" }
        return IntArray(2) { i ->
            val p = parts[i]
            when {
                p.startsWith("match") || p.startsWith("fill") -> ViewGroup.LayoutParams.MATCH_PARENT
                p.startsWith("wrap") -> ViewGroup.LayoutParams.WRAP_CONTENT
                else -> Regex("-?\\d+").find(p)?.value?.toInt() ?: throw IllegalArgumentException("cannot parse \"$p\"")
            }
        }
    }

    private fun floats(value: String, n: Int): FloatArray {
        val parts = value.trim().removePrefix("[").removeSuffix("]").split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        require(parts.size == n) { "expected $n numbers" }
        return FloatArray(n) { parts[it].toFloat() }
    }
}

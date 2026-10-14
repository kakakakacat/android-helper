package kakacat.android.helper.ide.layout

import kakacat.android.helper.ide.HelperBundle
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * 截图面板：按窗口位置把各窗口截图拼起来，叠加选中 / 悬停的 View 边框。
 *
 *  * 单击：选中该点下面积最小的 View；在同一点连续单击逐层往外选（解决"点不到父布局"）。
 *  * Shift + 单击：选第二个 View，显示两者之间的间距（CodeLocator 同款）。
 */
class ScreenView : JComponent() {

    var snapshot: LayoutSnapshot? = null
        set(value) {
            field = value
            hovered = null
            compare = null
            repaint()
        }

    var selected: LNode? = null
        set(value) {
            field = value
            repaint()
        }

    var showHidden = false
    var onPick: (LNode) -> Unit = {}

    private var hovered: LNode? = null
    private var compare: LNode? = null
    private var lastClick: Point? = null
    private var clickDepth = 0

    init {
        preferredSize = Dimension(JBUI.scale(320), JBUI.scale(560))
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val p = toDevice(e.point)
                val n = p?.let { snapshot?.pick(it.x, it.y, showHidden) }
                if (n !== hovered) {
                    hovered = n
                    toolTipText = n?.let { "${it.simpleName}  ${it.bounds.width}×${it.bounds.height}" }
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = null
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                val p = toDevice(e.point) ?: return
                val snap = snapshot ?: return
                if (e.isShiftDown && selected != null) {
                    compare = snap.pick(p.x, p.y, showHidden)
                    repaint()
                    return
                }
                clickDepth = if (lastClick != null && lastClick!!.distance(e.point) < 4) clickDepth + 1 else 0
                lastClick = e.point
                compare = null
                snap.pick(p.x, p.y, showHidden, clickDepth)?.let(onPick)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    /** 当前缩放：设备像素 → 面板像素。 */
    private fun transform(): Pair<Double, Point>? {
        val canvas = snapshot?.canvas ?: return null
        if (canvas.width <= 0 || canvas.height <= 0) return null
        val pad = JBUI.scale(8)
        val scale = minOf((width - 2.0 * pad) / canvas.width, (height - 2.0 * pad) / canvas.height)
        if (scale <= 0) return null
        val ox = ((width - canvas.width * scale) / 2 - canvas.x * scale).toInt()
        val oy = ((height - canvas.height * scale) / 2 - canvas.y * scale).toInt()
        return scale to Point(ox, oy)
    }

    private fun toDevice(p: Point): Point? {
        val (scale, o) = transform() ?: return null
        return Point(((p.x - o.x) / scale).toInt(), ((p.y - o.y) / scale).toInt())
    }

    private fun toScreen(r: Rectangle, scale: Double, o: Point) = Rectangle(
        (o.x + r.x * scale).toInt(), (o.y + r.y * scale).toInt(),
        maxOf(1, (r.width * scale).toInt()), maxOf(1, (r.height * scale).toInt()),
    )

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        try {
            g.color = UIUtil.getPanelBackground()
            g.fillRect(0, 0, width, height)
            val snap = snapshot
            val t = transform()
            if (snap == null || t == null) {
                g.color = UIUtil.getContextHelpForeground()
                val msg = HelperBundle.message("screen.empty")
                g.drawString(msg, (width - g.fontMetrics.stringWidth(msg)) / 2, height / 2)
                return
            }
            val (scale, o) = t
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (w in snap.windows) {
                val r = toScreen(w.bounds, scale, o)
                val img = w.image
                if (img != null) {
                    g.drawImage(img, r.x, r.y, r.width, r.height, null)
                } else {
                    g.color = JBColor.border()
                    g.drawRect(r.x, r.y, r.width, r.height)
                }
            }

            hovered?.takeIf { it !== selected }?.let { outline(g, toScreen(it.bounds, scale, o), HOVER, 1f, false) }
            compare?.let { outline(g, toScreen(it.bounds, scale, o), COMPARE, 2f, true) }
            selected?.let { sel ->
                val r = toScreen(sel.bounds, scale, o)
                outline(g, r, SELECT, 2f, true)
                val cmp = compare
                if (cmp != null) drawGaps(g, sel.bounds, cmp.bounds, scale, o)
                else label(g, r, "${sel.bounds.width}×${sel.bounds.height}  (${dp(sel.bounds.width)}×${dp(sel.bounds.height)} dp)")
            }
        } finally {
            g.dispose()
        }
    }

    private fun dp(px: Int): String {
        val d = snapshot?.density?.takeIf { it > 0 } ?: 1f
        val v = px / d
        return if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
    }

    private fun outline(g: Graphics2D, r: Rectangle, c: Color, width: Float, fill: Boolean) {
        if (fill) {
            val old = g.composite
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f)
            g.color = c
            g.fillRect(r.x, r.y, r.width, r.height)
            g.composite = old
        }
        g.color = c
        g.stroke = BasicStroke(JBUI.scale(width.toInt()).toFloat())
        g.drawRect(r.x, r.y, r.width, r.height)
    }

    private fun label(g: Graphics2D, anchor: Rectangle, text: String) {
        val fm = g.fontMetrics
        val w = fm.stringWidth(text) + JBUI.scale(8)
        val h = fm.height + JBUI.scale(2)
        var x = anchor.x
        var y = anchor.y - h - JBUI.scale(2)
        if (y < 0) y = anchor.y + anchor.height + JBUI.scale(2)
        if (x + w > width) x = width - w
        g.color = SELECT
        g.fillRect(x, y, w, h)
        g.color = Color.WHITE
        g.drawString(text, x + JBUI.scale(4), y + fm.ascent + JBUI.scale(1))
    }

    /** 两个 View 之间的水平 / 垂直间距（设备像素和 dp）。 */
    private fun drawGaps(g: Graphics2D, a: Rectangle, b: Rectangle, scale: Double, o: Point) {
        val parts = ArrayList<String>()
        fun gap(name: String, px: Int) { if (px >= 0) parts.add("$name $px px (${dp(px)} dp)") }
        when {
            b.x >= a.x + a.width -> gap("→", b.x - (a.x + a.width))
            a.x >= b.x + b.width -> gap("←", a.x - (b.x + b.width))
        }
        when {
            b.y >= a.y + a.height -> gap("↓", b.y - (a.y + a.height))
            a.y >= b.y + b.height -> gap("↑", a.y - (b.y + b.height))
        }
        if (parts.isEmpty()) {
            // 重叠/包含：给出四边的距离
            parts.add(HelperBundle.message("screen.gaps", (b.x - a.x).toString(), (b.y - a.y).toString(), ((a.x + a.width) - (b.x + b.width)).toString(), ((a.y + a.height) - (b.y + b.height)).toString()))
        }
        label(g, toScreen(a, scale, o), parts.joinToString("   "))
    }

    private companion object {
        val SELECT = JBColor(Color(0xF0, 0x6A, 0x00), Color(0xFF, 0x8A, 0x1F))
        val HOVER = JBColor(Color(0x2F, 0x7B, 0xF5), Color(0x58, 0x9D, 0xF6))
        val COMPARE = JBColor(Color(0x1E, 0x9E, 0x4A), Color(0x4C, 0xC3, 0x6B))
    }
}

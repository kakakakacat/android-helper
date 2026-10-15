package kakacat.android.helper.layout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kakacat.android.helper.HelperErrors

/**
 * 窗口截图。
 *
 * 首选 PixelCopy（API 26+，需要拿到 Window）：拷的是窗口 Surface 上真正显示的像素，
 * 硬件位图（Glide / Coil 默认用的 Bitmap.Config.HARDWARE）、TextureView 都能截到。
 * 软件绘制 `View.draw(canvas)` 碰到硬件位图会直接抛异常，只作为兜底。
 *
 * SurfaceView 不在窗口的 Surface 里：它有自己独立的 Surface（视频、相机、Filament / Unity
 * 之类的 3D 渲染），窗口上对应的位置只是一个透明的洞，所以窗口截图里那块是黑的。
 * 每个可见的 SurfaceView 再单独 PixelCopy 一次（API 24+），垫在窗口截图下面合成 ——
 * 默认的 SurfaceView 就是在窗口下面，窗口里盖在它上面的按钮、文字照样在上层。
 * （setZOrderOnTop 的 SurfaceView 实际在窗口上面，没有公开 API 能判断，也按下层处理；
 * 那种情况下窗口在它的区域里通常也只有那个洞，结果一样。）DRM 等安全 Surface 拷不到，保持原样。
 *
 * 编码用 JPEG 而不是 PNG：PNG 无损压缩在低端机上一张全屏图要几百毫秒、几 MB；
 * 截图只是给人看的，JPEG 80 在 IDE 里肉眼看不出区别，编码快一个数量级。
 * （WebP 更小，但 IDE 的 ImageIO 默认解不了。）
 */
internal object Screenshots {

    private val copyThread by lazy { HandlerThread("androidhelper-pixelcopy").apply { start() } }

    class Pending(val bitmap: Bitmap, val done: CountDownLatch, @Volatile var ok: Boolean = false) {
        /** 窗口下面的 SurfaceView 内容，坐标相对根 View。 */
        val surfaces = ArrayList<Surface>()
    }

    class Surface(val bitmap: Bitmap, val x: Int, val y: Int) {
        val done = CountDownLatch(1)
        @Volatile var ok = false
    }

    /** 主线程调用。PixelCopy 是异步的，返回后用 [Pending.done] 等结果。 */
    fun start(root: View): Pending? {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return null
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: OutOfMemoryError) {
            return null
        }
        val window = if (Build.VERSION.SDK_INT >= 26) WindowRoots.windowOf(root) else null
        if (window != null && Build.VERSION.SDK_INT >= 26) {
            val pending = Pending(bitmap, CountDownLatch(1))
            copySurfaces(root, pending.surfaces)
            try {
                PixelCopy.request(window, bitmap, { result ->
                    pending.ok = result == PixelCopy.SUCCESS
                    if (!pending.ok) HelperErrors.record("layout", "layout.screenshot.window", message = "PixelCopy result $result")
                    if (!pending.ok) pending.ok = drawSoftware(root, bitmap)
                    pending.done.countDown()
                }, Handler(copyThread.looper))
                return pending
            } catch (t: Throwable) {
                // 窗口还没有 Surface 等情况，落到软件绘制
                HelperErrors.record("layout", "layout.screenshot.window", t)
            }
        }
        val pending = Pending(bitmap, CountDownLatch(1))
        copySurfaces(root, pending.surfaces)
        pending.ok = drawSoftware(root, bitmap)
        pending.done.countDown()
        return pending
    }

    /** 主线程调用：给根 View 下每个可见的 SurfaceView 发起一次 PixelCopy。 */
    private fun copySurfaces(root: View, out: MutableList<Surface>) {
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val loc = IntArray(2)
        fun visit(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is SurfaceView) {
                val surface = v.holder.surface
                if (v.width <= 0 || v.height <= 0 || surface == null || !surface.isValid) return
                val bmp = try {
                    Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                } catch (t: OutOfMemoryError) {
                    return
                }
                v.getLocationInWindow(loc)
                val s = Surface(bmp, loc[0] - rootLoc[0], loc[1] - rootLoc[1])
                try {
                    PixelCopy.request(v, bmp, { result ->
                        s.ok = result == PixelCopy.SUCCESS
                        if (!s.ok) HelperErrors.record("layout", "layout.screenshot.surface", message = "PixelCopy result $result")
                        s.done.countDown()
                    }, Handler(copyThread.looper))
                    out.add(s)
                } catch (t: Throwable) {
                    // Surface 刚好被销毁之类，跳过这一个
                    HelperErrors.record("layout", "layout.screenshot.surface", t)
                    bmp.recycle()
                }
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
    }

    /**
     * 注意：在 PixelCopy 回调里兜底时这里不在主线程，View.draw 理论上不安全；
     * 但那时 PixelCopy 已经失败，有图总比没有强，出错就放弃。
     */
    private fun drawSoftware(root: View, bitmap: Bitmap): Boolean = try {
        root.draw(Canvas(bitmap))
        true
    } catch (t: Throwable) {
        HelperErrors.record("layout", "layout.screenshot.software", t)
        false
    }

    /** 后台线程调用：等结果、缩放、编码。失败返回 null。 */
    fun finish(p: Pending, maxWidth: Int, quality: Int): ByteArray? {
        try {
            if (!p.done.await(3, TimeUnit.SECONDS) || !p.ok) return null
            var bmp = composite(p)
            if (maxWidth > 0 && bmp.width > maxWidth) {
                val h = (bmp.height.toLong() * maxWidth / bmp.width).toInt()
                bmp = Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
            }
            val out = ByteArrayOutputStream(bmp.width * bmp.height / 4)
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        } catch (t: Throwable) {
            return null
        } finally {
            p.bitmap.recycle()
            p.surfaces.forEach { it.bitmap.recycle() }
        }
    }

    /** SurfaceView 内容垫底，窗口截图（洞是透明的）盖在上面。没有 SurfaceView 时原样返回。 */
    private fun composite(p: Pending): Bitmap {
        val ready = p.surfaces.filter { it.done.await(1, TimeUnit.SECONDS) && it.ok }
        if (ready.size < p.surfaces.size) {
            // 拷不到的 SurfaceView 在图里会是黑的：DRM 等安全 Surface，或渲染线程卡住 1 s 没交出画面
            HelperErrors.record("layout", "layout.screenshot.surface", message = "screenshot: ${p.surfaces.size - ready.size} of ${p.surfaces.size} SurfaceView(s) could not be copied")
        }
        if (ready.isEmpty()) return p.bitmap
        val out = Bitmap.createBitmap(p.bitmap.width, p.bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        ready.forEach { canvas.drawBitmap(it.bitmap, it.x.toFloat(), it.y.toFloat(), null) }
        canvas.drawBitmap(p.bitmap, 0f, 0f, null)
        return out
    }
}

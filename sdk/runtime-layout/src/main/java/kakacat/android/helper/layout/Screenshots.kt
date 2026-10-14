package kakacat.android.helper.layout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.View
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 窗口截图。
 *
 * 首选 PixelCopy（API 26+，需要拿到 Window）：拷的是窗口 Surface 上真正显示的像素，
 * 硬件位图（Glide / Coil 默认用的 Bitmap.Config.HARDWARE）、SurfaceView、TextureView
 * 都能截到。软件绘制 `View.draw(canvas)` 碰到硬件位图会直接抛异常，只作为兜底。
 *
 * 编码用 JPEG 而不是 PNG：PNG 无损压缩在低端机上一张全屏图要几百毫秒、几 MB；
 * 截图只是给人看的，JPEG 80 在 IDE 里肉眼看不出区别，编码快一个数量级。
 * （WebP 更小，但 IDE 的 ImageIO 默认解不了。）
 */
internal object Screenshots {

    private val copyThread by lazy { HandlerThread("androidhelper-pixelcopy").apply { start() } }

    class Pending(val bitmap: Bitmap, val done: CountDownLatch, @Volatile var ok: Boolean = false)

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
            try {
                PixelCopy.request(window, bitmap, { result ->
                    pending.ok = result == PixelCopy.SUCCESS
                    if (!pending.ok) pending.ok = drawSoftware(root, bitmap)
                    pending.done.countDown()
                }, Handler(copyThread.looper))
                return pending
            } catch (t: Throwable) {
                // 窗口还没有 Surface 等情况，落到软件绘制
            }
        }
        val pending = Pending(bitmap, CountDownLatch(1))
        pending.ok = drawSoftware(root, bitmap)
        pending.done.countDown()
        return pending
    }

    /**
     * 注意：在 PixelCopy 回调里兜底时这里不在主线程，View.draw 理论上不安全；
     * 但那时 PixelCopy 已经失败，有图总比没有强，出错就放弃。
     */
    private fun drawSoftware(root: View, bitmap: Bitmap): Boolean = try {
        root.draw(Canvas(bitmap))
        true
    } catch (t: Throwable) {
        false
    }

    /** 后台线程调用：等结果、缩放、编码。失败返回 null。 */
    fun finish(p: Pending, maxWidth: Int, quality: Int): ByteArray? {
        try {
            if (!p.done.await(3, TimeUnit.SECONDS) || !p.ok) return null
            var bmp = p.bitmap
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
        }
    }
}

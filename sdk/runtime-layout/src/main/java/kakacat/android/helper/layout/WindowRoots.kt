package kakacat.android.helper.layout

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.Window
import java.lang.ref.WeakReference

/**
 * 找出当前进程里所有窗口的根 View（Activity、Dialog、PopupWindow……），按叠放顺序。
 *
 * 首选 `WindowManagerGlobal.mViews`：它是唯一能拿到 Dialog / PopupWindow 根 View 的地方。
 * 这是隐藏 API（灰名单），LeakCanary 等工具一直在用；万一哪天读不到，退回到只看
 * resumed 的 Activity —— 至少主界面还能看。
 */
internal object WindowRoots {

    private val activities = ArrayList<WeakReference<Activity>>()

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                synchronized(activities) {
                    activities.removeAll { it.get() == null || it.get() === a }
                    activities.add(WeakReference(a))
                }
            }

            override fun onActivityDestroyed(a: Activity) {
                synchronized(activities) { activities.removeAll { it.get() == null || it.get() === a } }
            }

            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
    }

    /** 必须在主线程调用。 */
    fun roots(): List<View> {
        val fromGlobal = runCatching { globalViews() }.getOrNull()
        val roots = if (!fromGlobal.isNullOrEmpty()) fromGlobal else activityDecors()
        // mViews 是按添加顺序排的，不一定是叠放顺序：在 onCreate 里弹的 Dialog 比 Activity
        // 自己的窗口先 add。按窗口类型排（Activity < Dialog < 子窗口/PopupWindow），同类型保持原顺序。
        return roots
            .filter { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            .sortedBy { (it.layoutParams as? android.view.WindowManager.LayoutParams)?.type ?: 0 }
    }

    /** 根 View 所属的 Activity（Dialog 的窗口返回它依附的 Activity，找不到返回 null）。 */
    fun activityOf(root: View): Activity? {
        synchronized(activities) {
            activities.firstOrNull { it.get()?.window?.peekDecorView() === root }?.get()?.let { return it }
        }
        var ctx = root.context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    /** 根 View 对应的 Window（用于 PixelCopy）。Activity 和 Dialog 的 DecorView 都有。 */
    fun windowOf(root: View): Window? {
        synchronized(activities) {
            activities.firstOrNull { it.get()?.window?.peekDecorView() === root }?.get()?.let { return it.window }
        }
        return runCatching {
            var c: Class<*>? = root.javaClass
            while (c != null) {
                val f = c.declaredFields.firstOrNull { it.name == "mWindow" && Window::class.java.isAssignableFrom(it.type) }
                if (f != null) {
                    f.isAccessible = true
                    return@runCatching f.get(root) as? Window
                }
                c = c.superclass
            }
            null
        }.getOrNull()
    }

    private fun globalViews(): List<View> {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val instance = cls.getMethod("getInstance").invoke(null)
        val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return ArrayList(field.get(instance) as List<View>)
    }

    private fun activityDecors(): List<View> = synchronized(activities) {
        activities.mapNotNull { it.get()?.takeIf { a -> !a.isFinishing }?.window?.peekDecorView() }
    }
}

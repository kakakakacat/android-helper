package kakacat.android.helper.layout

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window

/**
 * 构建期插桩调用的入口。作用只有一个：记下"这个 View 是在哪一行代码里来的"，
 * 供 IDE 一键跳转（替代 CodeLocator 依赖的 Lancet —— Lancet 基于 AGP 8 已删除的 Transform API）。
 *
 * `site` 由插件在编译期拼好，格式和异常栈帧一致：`com.foo.Bar.method(Bar.kt:42)`，
 * 运行时只是一次 setTag，不做任何字符串处理。
 *
 * | 原调用点 | 插桩后 |
 * |---|---|
 * | `new XxxView(...)`                      | 原调用后追加 `created(view, site)` |
 * | `x.setOnClickListener(l)`（长按、触摸同理）| 原调用**前**追加 `click(x, l, site)`，原调用不动 |
 * | `inflater.inflate(res, root[, attach])` | 换成 `inflate(inflater, res, root[, attach], site)` |
 * | `View.inflate(ctx, res, root)`          | 换成 `inflate(ctx, res, root, site)` |
 * | `x.setContentView(res)`                 | 换成 `setContentView(x, res, site)` |
 * | `dialog.show()`                         | 换成 `show(dialog, site)` |
 * | `builder.show()` → Dialog               | 原调用后追加 `dialogShown(dialog, site)` |
 *
 * 除了 inflate（owner 类型在插桩时能精确确定），参数一律是 `Any?`：插桩时不一定能证明
 * 调用者是 View —— 比如某个 Adapter 恰好也有 `setOnClickListener(View.OnClickListener)`。
 * 用 View 做参数类型的话，那种类加载时会 VerifyError，直接把 App 弄崩。
 *
 * 和 network 的 Hooks 一样的铁律：不改变业务语义、绝不吞掉业务异常、自身绝不抛异常。
 */
object LayoutHooks {

    @JvmStatic
    fun created(view: Any?, site: String) {
        if (view is View) ViewSources.of(view)?.let { if (it.created == null) it.created = site }
    }

    @JvmStatic
    fun click(view: Any?, listener: Any?, site: String) {
        if (view is View) ViewSources.of(view)?.click = if (listener != null) site else null
    }

    @JvmStatic
    fun longClick(view: Any?, listener: Any?, site: String) {
        if (view is View) ViewSources.of(view)?.longClick = if (listener != null) site else null
    }

    @JvmStatic
    fun touch(view: Any?, listener: Any?, site: String) {
        if (view is View) ViewSources.of(view)?.touch = if (listener != null) site else null
    }

    @JvmStatic
    fun inflate(inflater: LayoutInflater, res: Int, root: ViewGroup?, site: String): View {
        val result = inflater.inflate(res, root)
        markInflated(inflater.context, result, res, root, root != null, site)
        return result
    }

    @JvmStatic
    fun inflate(inflater: LayoutInflater, res: Int, root: ViewGroup?, attach: Boolean, site: String): View {
        val result = inflater.inflate(res, root, attach)
        markInflated(inflater.context, result, res, root, attach && root != null, site)
        return result
    }

    @JvmStatic
    fun inflate(context: Context, res: Int, root: ViewGroup?, site: String): View {
        val result = View.inflate(context, res, root)
        markInflated(context, result, res, root, root != null, site)
        return result
    }

    /** Activity / Dialog / Window 的 setContentView(int)。其它类型（插件判断失误时）反射调用原方法。 */
    @JvmStatic
    fun setContentView(target: Any, res: Int, site: String) {
        when (target) {
            is Activity -> {
                target.setContentView(res)
                markContent(target, target.findViewById(android.R.id.content), res, site)
            }
            is Dialog -> {
                target.setContentView(res)
                markContent(target.context, target.findViewById(android.R.id.content), res, site)
            }
            is Window -> {
                target.setContentView(res)
                markContent(target.context, target.findViewById(android.R.id.content), res, site)
            }
            else -> invoke(target, "setContentView", res)
        }
    }

    @JvmStatic
    fun show(target: Any, site: String) {
        if (target is Dialog) {
            target.show()
            dialogShown(target, site)
        } else {
            invoke(target, "show")
        }
    }

    @JvmStatic
    fun dialogShown(dialog: Any?, site: String) {
        try {
            val decor = (dialog as? Dialog)?.window?.peekDecorView() ?: return
            ViewSources.of(decor)?.shown = site
        } catch (t: Throwable) {
        }
    }

    /** attach 时 inflate 返回的是 root 本身，真正新建的是 root 的最后一个子 View。 */
    private fun markInflated(context: Context, result: View, res: Int, root: ViewGroup?, attached: Boolean, site: String) {
        try {
            val inflated = if (attached && root != null && root.childCount > 0) root.getChildAt(root.childCount - 1) else result
            mark(context, inflated, res, site)
        } catch (t: Throwable) {
        }
    }

    private fun markContent(context: Context, content: View?, res: Int, site: String) {
        try {
            (content as? ViewGroup)?.getChildAt(0)?.let { mark(context, it, res, site) }
        } catch (t: Throwable) {
        }
    }

    private fun mark(context: Context, view: View, res: Int, site: String) {
        val src = ViewSources.of(view) ?: return
        src.layout = runCatching { context.resources.getResourceEntryName(res) }.getOrNull()
        src.inflated = site
    }

    /** 兜底：反射调原方法，业务异常原样抛出。 */
    private fun invoke(target: Any, name: String, vararg args: Int) {
        val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
        val m = target.javaClass.getMethod(name, *types)
        try {
            m.invoke(target, *args.toTypedArray())
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}

/** 插桩记下的源码位置。挂在 View 的 tag 上，跟着 View 一起回收。 */
class ViewSource {
    var created: String? = null
    var click: String? = null
    var longClick: String? = null
    var touch: String? = null
    /** 布局文件名（不带 .xml），如 `activity_main`。 */
    var layout: String? = null
    var inflated: String? = null
    var shown: String? = null
}

object ViewSources {
    fun of(view: View): ViewSource? = try {
        (view.getTag(R.id.androidhelper_view_source) as? ViewSource)
            ?: ViewSource().also { view.setTag(R.id.androidhelper_view_source, it) }
    } catch (t: Throwable) {
        null
    }

    fun peek(view: View): ViewSource? = try {
        view.getTag(R.id.androidhelper_view_source) as? ViewSource
    } catch (t: Throwable) {
        null
    }
}

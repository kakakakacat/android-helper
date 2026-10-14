package kakacat.android.helper.layout

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import kakacat.android.helper.Frame
import kakacat.android.helper.HelperClient
import kakacat.android.helper.HelperFeature
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 布局检查。请求-响应式，全部走控制帧，只回给发请求的那个客户端：
 *
 * ```
 * → {"type":"layout.snapshot","req":7,"maxWidth":1080,"quality":80}
 * ← BLOB  ref="layout:7:<窗口序号>"   JPEG 截图
 * ← {"type":"layout.snapshot.result","req":7,"windows":[…],"screen":{…},"costMs":{…}}
 *
 * → {"type":"layout.set","req":8,"node":42,"key":"alpha","value":"0.5"}
 * ← {"type":"layout.set.result","req":8,"ok":true}
 * ```
 *
 * 和 Layout Inspector 的根本区别：**只在 IDE 点"抓取"时干活**，不做持续推送。
 * 没人点的时候，这个模块在 App 里的开销是零（除了插桩记源码位置的那次 setTag）。
 */
internal class LayoutFeature(private val context: Context) : HelperFeature {

    override val name: String = "layout"

    private val main = Handler(Looper.getMainLooper())

    /** 最近一次抓取的节点表，下标就是节点 id。 */
    @Volatile
    private var nodes: List<WeakReference<View>?> = emptyList()

    override fun onControl(client: HelperClient, json: JSONObject): Boolean {
        when (json.optString("type")) {
            "layout.snapshot" -> snapshot(client, json)
            "layout.set" -> set(client, json)
            else -> return false
        }
        return true
    }

    private fun snapshot(client: HelperClient, req: JSONObject) {
        val reqId = req.optInt("req")
        val maxWidth = req.optInt("maxWidth", 1080)
        val quality = req.optInt("quality", 80).coerceIn(30, 100)
        val t0 = SystemClock.uptimeMillis()

        class Captured(val windows: JSONArray, val shots: List<Screenshots.Pending?>, val nodes: List<WeakReference<View>?>)

        val captured = onMain {
            val list = ArrayList<WeakReference<View>?>()
            val inspector = ViewInspector(list)
            val windows = JSONArray()
            val shots = ArrayList<Screenshots.Pending?>()
            WindowRoots.roots().forEachIndexed { i, root ->
                windows.put(inspector.window(root, i))
                shots.add(Screenshots.start(root))
            }
            Captured(windows, shots, list)
        }
        if (captured == null) {
            error(client, "layout.snapshot.result", reqId, "主线程 3 秒内没有响应（App 可能卡住了）")
            return
        }
        nodes = captured.nodes
        val tTree = SystemClock.uptimeMillis()

        captured.shots.forEachIndexed { i, pending ->
            val bytes = pending?.let { Screenshots.finish(it, maxWidth, quality) } ?: return@forEachIndexed
            client.send(Frame.BLOB, Frame.encodeBlob("layout:$reqId:$i", bytes, -1))
        }
        val tDone = SystemClock.uptimeMillis()

        val dm = context.resources.displayMetrics
        reply(client, JSONObject().apply {
            put("type", "layout.snapshot.result")
            put("req", reqId)
            put("windows", captured.windows)
            put("screen", JSONObject().apply {
                put("w", dm.widthPixels); put("h", dm.heightPixels); put("density", dm.density.toDouble())
            })
            put("costMs", JSONObject().apply {
                put("tree", tTree - t0)
                put("image", tDone - tTree)
                put("nodes", captured.nodes.size)
            })
        })
        Log.i(TAG, "snapshot #$reqId: ${captured.windows.length()} windows, ${captured.nodes.size} nodes, tree ${tTree - t0}ms, image ${tDone - tTree}ms")
    }

    private fun set(client: HelperClient, req: JSONObject) {
        val reqId = req.optInt("req")
        val view = nodes.getOrNull(req.optInt("node", -1))?.get()
        if (view == null) {
            error(client, "layout.set.result", reqId, "View 已经不存在了，请重新抓取")
            return
        }
        val key = req.optString("key")
        val value = req.optString("value")
        val result = onMain { runCatching { PropertyEditor.apply(view, key, value) } }
        val err = when {
            result == null -> "主线程没有响应"
            result.isFailure -> result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            else -> null
        }
        if (err != null) error(client, "layout.set.result", reqId, err)
        else reply(client, JSONObject().put("type", "layout.set.result").put("req", reqId).put("ok", true))
    }

    /** 在主线程上跑 [block] 并等结果；超时返回 null。 */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val ref = AtomicReference<T?>()
        val latch = CountDownLatch(1)
        main.post {
            try {
                ref.set(block())
            } catch (t: Throwable) {
                Log.w(TAG, "main-thread task failed", t)
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(3, TimeUnit.SECONDS)) ref.get() else null
    }

    private fun error(client: HelperClient, type: String, reqId: Int, message: String) {
        reply(client, JSONObject().put("type", type).put("req", reqId).put("ok", false).put("error", message))
    }

    private fun reply(client: HelperClient, json: JSONObject) {
        client.send(Frame.CONTROL, Frame.encodeEvent(json))
    }

    private companion object {
        const val TAG = "AndroidHelper"
    }
}

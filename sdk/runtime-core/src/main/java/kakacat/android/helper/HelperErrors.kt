package kakacat.android.helper

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

/**
 * SDK 自身的错误（不是 App 的）：插桩失败、截图失败、反射拿不到 Compose 内部 API……
 *
 * 这些地方原来都是静默吞掉、落回原始行为 —— 业务照常跑，但功能悄悄缺了一块，没人知道。
 * 现在吞掉之前记一笔：
 *
 *  * 打一条 logcat（tag `AndroidHelper`）；
 *  * 存进进程内的小缓冲区，按 [where] + 异常类型去重计数，**不落盘、不联网**；
 *  * 有客户端连着时实时发一帧 `sdk.error`，新客户端连上时补发缓冲区里的全部。
 *
 * 上不上报、怎么脱敏由 IDE 插件决定；SDK 自己从不往外发任何东西。
 */
object HelperErrors {

    private const val TAG = "AndroidHelper"
    private const val MAX_ENTRIES = 50

    private class Entry(
        val feature: String,
        val where: String,
        val exception: String,
        var message: String,
        var stack: String,
        val first: Long,
    ) {
        var last = first
        var count = 1
    }

    /** key = where + 异常类型。LinkedHashMap 保持插入顺序，满了丢最老的。 */
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * 记录一个被吞掉的异常。任何线程都能调，本身绝不抛异常。
     *
     * @param where 稳定的位置标识，比如 `layout.screenshot.pixelCopy`；同一个位置同一种异常只算一条。
     */
    @JvmStatic
    @JvmOverloads
    fun record(feature: String, where: String, error: Throwable? = null, message: String? = null) {
        try {
            val exception = error?.javaClass?.name ?: "-"
            val text = message ?: error?.message ?: ""
            val key = "$where|$exception"
            val now = System.currentTimeMillis()
            val entry = synchronized(entries) {
                val e = entries[key]
                if (e != null) {
                    e.count++
                    e.last = now
                    e.message = text
                    e
                } else {
                    if (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
                    Entry(feature, where, exception, text, error?.let(::stackOf) ?: "", now).also { entries[key] = it }
                }
            }
            // 同一个错误在循环里刷屏时，logcat 只打前几次
            if (entry.count <= 3) Log.w(TAG, "[$where] $exception: $text", error)
            if (HelperServer.hasClient) HelperServer.broadcast(Frame.CONTROL, Frame.encodeEvent(toJson(entry)))
        } catch (t: Throwable) {
            // 记录错误本身出错就算了
        }
    }

    /** 新客户端连上时补发。 */
    internal fun replay(client: HelperClient) {
        val snapshot = synchronized(entries) { entries.values.map(::toJson) }
        for (json in snapshot) client.send(Frame.CONTROL, Frame.encodeEvent(json))
    }

    private fun toJson(e: Entry): JSONObject = synchronized(entries) {
        JSONObject().apply {
            put("type", "sdk.error")
            put("sdk", Helper.VERSION)
            put("feature", e.feature)
            put("where", e.where)
            put("exception", e.exception)
            put("message", e.message)
            put("stack", e.stack)
            put("count", e.count)
            put("first", e.first)
            put("last", e.last)
            put("api", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    private fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        // 太长的（深递归）截掉，够定位就行
        return sw.toString().let { if (it.length > 8000) it.substring(0, 8000) + "\n\t..." else it }
    }
}

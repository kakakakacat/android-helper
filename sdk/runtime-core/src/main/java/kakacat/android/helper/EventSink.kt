package kakacat.android.helper

import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 事件总线。所有 feature 的实时数据都从这里出去。
 *
 * 三条硬约束，顺序就是重要性：
 *
 *  1. **没人连时零开销**。[active] 为 false 时采集点直接 return，不 copy、不建 JSON、不入队。
 *  2. **业务线程只做入队**。JSON 拼装、脱敏、写 socket 全在下面这条 daemon 线程上。
 *  3. **队列有界，满了就丢**。客户端读得慢、USB 断了、body 太大，都不允许反压到业务线程。
 *     丢了多少通过事件里的 `dropped` 字段报出去，不静默。
 */
object EventSink {

    /** 2000 条足够吸收突发，又不会在 OOM 边缘徘徊。 */
    private const val QUEUE_CAPACITY = 2000

    private val ids = AtomicLong(0)
    private val queue = ArrayBlockingQueue<Pending>(QUEUE_CAPACITY)
    private val dropped = AtomicLong(0)
    private val filters = CopyOnWriteArrayList<Filter>()

    @Volatile
    private var worker: Thread? = null

    val active: Boolean get() = HelperServer.hasClient

    fun newId(prefix: String): String = prefix + ids.incrementAndGet()

    fun droppedCount(): Long = dropped.get()

    /**
     * 在后台线程上、帧离开进程之前执行的变换（比如脱敏）。放在这里而不是采集点上，
     * 是为了让正则之类的开销不落在业务线程上。
     */
    interface Filter {
        fun onEvent(json: JSONObject) {}
        fun onBlob(ref: String, bytes: ByteArray): ByteArray = bytes
    }

    fun addFilter(filter: Filter) {
        if (filter !in filters) filters.add(filter)
    }

    /** 由 HelperServer 在第一个客户端连上时调用。 */
    internal fun ensureWorker() {
        if (worker != null) return
        synchronized(this) {
            if (worker != null) return
            worker = Thread({ loop() }, "androidhelper-sink").apply {
                isDaemon = true
                // 明确压到后台优先级：调试工具永远不该和业务线程抢 CPU。
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    fun event(kind: String, phase: String, id: String, json: JSONObject) {
        if (!active) return
        json.put("v", 1)
        json.put("kind", kind)
        json.put("phase", phase)
        json.put("id", id)
        json.put("t", System.currentTimeMillis())
        json.put("mono", System.nanoTime() / 1_000_000)
        json.put("thread", Thread.currentThread().name)
        submit(Pending.Event(json))
    }

    fun blob(ref: String, bytes: ByteArray, truncatedFrom: Int = -1) {
        if (!active) return
        submit(Pending.Blob(ref, bytes, truncatedFrom))
    }

    private fun submit(p: Pending) {
        if (!queue.offer(p)) dropped.incrementAndGet()
    }

    private fun loop() {
        while (true) {
            val p = try {
                queue.take()
            } catch (e: InterruptedException) {
                return
            }
            try {
                when (p) {
                    is Pending.Event -> {
                        for (f in filters) f.onEvent(p.json)
                        val d = dropped.get()
                        if (d > 0) p.json.put("dropped", d)
                        HelperServer.broadcast(Frame.EVENT, Frame.encodeEvent(p.json))
                    }

                    is Pending.Blob -> {
                        var bytes = p.bytes
                        for (f in filters) bytes = f.onBlob(p.ref, bytes)
                        HelperServer.broadcast(
                            Frame.BLOB,
                            Frame.encodeBlob(p.ref, bytes, p.truncatedFrom)
                        )
                    }
                }
            } catch (t: Throwable) {
                // 自身的任何异常都不允许冒泡：这条线程死了就再也没有数据，但业务必须完全无感。
                HelperErrors.record("core", "core.sink", t)
            }
        }
    }

    private sealed interface Pending {
        class Event(val json: JSONObject) : Pending
        class Blob(val ref: String, val bytes: ByteArray, val truncatedFrom: Int) : Pending
    }
}

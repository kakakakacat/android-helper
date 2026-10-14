package kakacat.android.helper

import android.annotation.SuppressLint
import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 设备侧的唯一对外出口：**abstract namespace 的 LocalSocket**，不监听任何 TCP 端口。
 *
 * IDE 插件用 `adb forward tcp:0 localabstract:<name>` 连进来；网页版 adb（WebUSB）
 * 直接 `createSocket("localabstract:<name>")`。两条路共用这一份实现。
 *
 * 绑两个名字，协议完全一样：
 *
 *  * `androidhelper.<pkg>` —— 主名字，IDE 插件优先连它；
 *  * `netcapture.<pkg>` —— 兼容 android-netcapture 的网页控制台，老页面不用改就能看。
 *
 * 名字里带包名：一台设备上同时跑多个带 SDK 的 debug 包时，名字本身就说明了它是谁。
 */
internal object HelperServer {

    private const val TAG = "AndroidHelper"
    private val NAME_PREFIXES = listOf("androidhelper", "netcapture")

    private val clients = CopyOnWriteArrayList<Client>()

    @Volatile
    private var started = false

    val hasClient: Boolean get() = clients.isNotEmpty()

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val pkg = context.packageName
        for (prefix in NAME_PREFIXES) {
            val name = "$prefix.$pkg"
            Thread({ accepting(context, name) }, "androidhelper-accept").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    private fun accepting(context: Context, name: String) {
        // 绑不上就是绑不上（上一个进程还没退干净、或者多进程 app 的另一个进程先绑了），
        // 记一条日志就够 —— 起不来绝不能影响应用启动。
        val server = try {
            LocalServerSocket(name)
        } catch (t: Throwable) {
            Log.w(TAG, "bind $name failed: ${t.message}")
            return
        }
        Log.i(TAG, "listening on localabstract:$name")
        while (true) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                Log.w(TAG, "accept failed: ${t.message}")
                return
            }
            try {
                onClient(socket, context, name)
            } catch (t: Throwable) {
                runCatching { socket.close() }
            }
        }
    }

    private fun onClient(socket: LocalSocket, context: Context, name: String) {
        val client = Client(socket, socket.outputStream)
        EventSink.ensureWorker()
        // hello 先发、再加入广播列表：保证客户端收到的第一帧一定是 hello。
        client.send(Frame.CONTROL, Frame.encodeEvent(hello(context, name)))
        for (f in Helper.features) runCatching { f.onAttach(client) }
        clients.add(client)
        Log.i(TAG, "client attached via $name (${clients.size} total)")

        Thread({
            try {
                readControl(socket, client)
            } catch (t: Throwable) {
                // 断开就是断开，不需要区分原因
            } finally {
                clients.remove(client)
                runCatching { socket.close() }
                Log.i(TAG, "client detached (${clients.size} left)")
            }
        }, "androidhelper-client").apply {
            isDaemon = true
            start()
        }
    }

    @SuppressLint("HardwareIds")
    private fun hello(context: Context, name: String): JSONObject = JSONObject().apply {
        put("v", 1)
        put("type", "hello")
        put("sdk", Helper.VERSION)
        put("socket", name)
        put("pkg", context.packageName)
        put("pid", Process.myPid())
        put("uid", Process.myUid())
        put("process", currentProcessName(context))
        put("t", System.currentTimeMillis())
        put("mono", System.nanoTime() / 1_000_000)
        put("features", JSONArray(Helper.features.map { it.name }))
        for (f in Helper.features) {
            // feature 可以往 hello 里塞自己的字段（比如 network 的 body 上限）
            (f as? HelloContributor)?.let { runCatching { it.contribute(this) } }
        }
    }

    private fun currentProcessName(context: Context): String =
        runCatching { android.app.Application.getProcessName() }.getOrNull()
            ?: context.packageName

    /**
     * 客户端 → 设备的控制帧。读循环本身也是我们感知"客户端走了"的唯一途径。
     */
    private fun readControl(socket: LocalSocket, client: Client) {
        val input = DataInputStream(socket.inputStream)
        while (true) {
            val len = input.readInt()
            if (len <= 0 || len > 1 shl 20) throw IllegalStateException("bad frame len $len")
            val type = input.readUnsignedByte()
            val payload = ByteArray(len - 1)
            input.readFully(payload)
            if (type != Frame.CONTROL) continue
            val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: continue
            val handled = Helper.features.any { f ->
                runCatching { f.onControl(client, json) }.getOrDefault(false)
            }
            if (!handled) Log.d(TAG, "unhandled control: ${json.optString("type")}")
        }
    }

    /** 广播一帧。写失败的客户端直接踢掉 —— 慢客户端不允许拖住采集线程。 */
    fun broadcast(type: Int, payload: ByteArray) {
        if (clients.isEmpty()) return
        val frame = Frame.wrap(type, payload)
        for (c in clients) {
            if (!c.write(frame)) {
                clients.remove(c)
                runCatching { c.socket.close() }
            }
        }
    }

    private class Client(val socket: LocalSocket, private val out: OutputStream) : HelperClient {
        override fun send(type: Int, payload: ByteArray): Boolean = write(Frame.wrap(type, payload))

        fun write(frame: ByteArray): Boolean = synchronized(this) {
            try {
                out.write(frame)
                out.flush()
                true
            } catch (t: Throwable) {
                false
            }
        }
    }
}

/** feature 可选实现：往 hello 帧里追加字段。 */
interface HelloContributor {
    fun contribute(hello: JSONObject)
}

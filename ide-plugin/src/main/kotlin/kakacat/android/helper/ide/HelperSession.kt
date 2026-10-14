package kakacat.android.helper.ide

import com.google.gson.JsonObject
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kakacat.android.helper.ide.adb.Adb
import kakacat.android.helper.ide.adb.Endpoint
import kakacat.android.helper.ide.network.NetStore
import kakacat.android.helper.ide.network.int
import kakacat.android.helper.ide.network.str
import kakacat.android.helper.ide.protocol.Frame
import kakacat.android.helper.ide.protocol.HelperConnection
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 工具窗口级别的会话：当前连着哪个 App、连接状态、收到的数据。
 *
 * 各标签页（Network，以后的 Layout）共用这一条连接。
 *
 * 线程模型：
 *  * adb 调用和 socket 读写都在后台线程；
 *  * 帧先进 [inbox]，由 EDT 上的定时器**每 100ms 批量**取出来交给 [NetStore]，
 *    UI 每批只刷新一次 —— 流量很大时也不会把 EDT 淹掉（Layout Inspector 卡死的
 *    一大原因就是数据一来就刷）。
 */
class HelperSession(val project: Project) : Disposable {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, WAITING }

    interface Listener {
        fun onStateChanged(state: State, message: String?) {}
        fun onData(changes: NetStore.Changes) {}
    }

    val netStore = NetStore()

    var state = State.DISCONNECTED
        private set
    var statusMessage: String? = null
        private set
    var endpoint: Endpoint? = null
        private set
    var hello: JsonObject? = null
        private set

    /** App 重启后自动重连。 */
    var autoReconnect = true

    /**
     * 这个项目上次连过的包名。打开工具窗口时如果它正在运行就自动连上。
     * 主动点"断开"会清掉它 —— 用户明确不想连的时候不该自作主张。
     */
    var lastPackage: String?
        get() = System.getProperty("androidhelper.autoConnect")
            ?: PropertiesComponent.getInstance(project).getValue(LAST_PACKAGE_KEY)
        private set(value) = PropertiesComponent.getInstance(project).setValue(LAST_PACKAGE_KEY, value)

    /** 设备对一次请求的回复：结果控制帧 + 同一请求附带的 BLOB（比如截图），key 是 ref。 */
    class Response(val json: JsonObject, val blobs: Map<String, ByteArray>) {
        val ok: Boolean get() = json.get("ok")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
        val error: String? get() = json.str("error")
    }

    private class Pending(val callback: (Result<Response>) -> Unit, val deadline: Long) {
        val blobs = LinkedHashMap<String, ByteArray>()
    }

    private var nextReq = 1
    private val pending = HashMap<Int, Pending>()

    private val listeners = mutableListOf<Listener>()
    private val inbox = ConcurrentLinkedQueue<Pair<Frame, Int>>()
    private var connection: HelperConnection? = null

    /** 每次 connect / disconnect 加一，用来丢弃过期连接的回调。 */
    @Volatile
    private var generation = 0

    private val pump = Timer(100) { drain() }.apply { start() }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun adb(): Adb? = Adb.locate(project)

    fun connect(target: Endpoint) {
        val adb = adb() ?: run {
            setState(State.DISCONNECTED, HelperBundle.message("adb.notFound"))
            return
        }
        connection?.close()
        failPending(HelperBundle.message("conn.reconnecting"))
        val gen = ++generation
        endpoint = target
        lastPackage = target.pkg
        hello = null
        setState(State.CONNECTING, HelperBundle.message("conn.connecting", target.pkg))

        // pid 来自 hello，只有读线程会写，读线程之后的帧都用它
        var pid = 0
        val conn = HelperConnection(adb, target, object : HelperConnection.Listener {
            override fun onFrame(frame: Frame) {
                if (frame is Frame.Control && frame.json.str("type") == "hello") {
                    pid = frame.json.int("pid") ?: 0
                }
                inbox.add(frame to pid)
            }

            override fun onClosed(error: Throwable?) {
                SwingUtilities.invokeLater { onConnectionClosed(gen, error) }
            }
        })
        connection = conn
        conn.start()
    }

    /**
     * 发一个请求（如 `layout.snapshot`），回调在 EDT 上。设备的回复是 `type` 以 `.result`
     * 结尾、带同一个 `req` 的控制帧；回复之前到达的 `<feature>:<req>:…` BLOB 一并交给回调。
     */
    fun request(json: JsonObject, timeoutMs: Long = 15_000, callback: (Result<Response>) -> Unit) {
        val conn = connection
        if (conn == null || state != State.CONNECTED) {
            callback(Result.failure(IllegalStateException(HelperBundle.message("conn.notConnected"))))
            return
        }
        val req = nextReq++
        json.addProperty("req", req)
        pending[req] = Pending(callback, System.currentTimeMillis() + timeoutMs)
        conn.sendControl(json)
    }

    /** 当前连接的 App 是否支持某个能力（看 hello 里的 features）。老 netcapture SDK 只有抓包。 */
    fun supports(feature: String): Boolean =
        hello?.getAsJsonArray("features")?.any { it.asString == feature } == true

    private fun failPending(reason: String) {
        if (pending.isEmpty()) return
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.callback(Result.failure(IllegalStateException(reason))) }
    }

    fun disconnect() {
        lastPackage = null
        failPending(HelperBundle.message("conn.closed"))
        generation++
        connection?.close()
        connection = null
        setState(State.DISCONNECTED, null)
    }

    fun clear() {
        netStore.clear()
        val changes = NetStore.Changes().apply { removed = Int.MAX_VALUE }
        listeners.forEach { it.onData(changes) }
        val c = JsonObject().apply { addProperty("type", "clear") }
        connection?.sendControl(c)
    }

    private fun onConnectionClosed(gen: Int, error: Throwable?) {
        if (gen != generation) return
        connection = null
        failPending(HelperBundle.message("conn.closed"))
        val target = endpoint
        val wasConnected = state == State.CONNECTED
        if (error == null || target == null) {
            setState(State.DISCONNECTED, null)
            return
        }
        if (autoReconnect && wasConnected) {
            setState(State.WAITING, HelperBundle.message("conn.waiting", target.pkg))
            waitAndReconnect(gen, target)
        } else {
            setState(State.DISCONNECTED, HelperBundle.message("conn.failed", error.message ?: error.javaClass.simpleName))
        }
    }

    /** App 被杀或重装后，socket 会在新进程启动时重新出现；轮询 /proc/net/unix 等它。 */
    private fun waitAndReconnect(gen: Int, target: Endpoint) {
        val adb = adb() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            while (gen == generation) {
                val found = runCatching { adb.endpoints(target.serial) }.getOrNull()
                    ?.firstOrNull { it.pkg == target.pkg }
                if (found != null) {
                    SwingUtilities.invokeLater { if (gen == generation) connect(found) }
                    return@executeOnPooledThread
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun drain() {
        expireRequests()
        if (inbox.isEmpty()) return
        val changes = NetStore.Changes()
        var budget = 5000 // 单批上限，剩下的留给下一个 tick，保证 EDT 不被一次性占满
        while (budget-- > 0) {
            val (frame, pid) = inbox.poll() ?: break
            if (frame is Frame.Control) {
                val type = frame.json.str("type")
                if (type == "hello") onHello(frame.json)
                else if (type != null && type.endsWith(".result")) onResult(frame.json)
                continue
            }
            if (frame is Frame.Blob && isRequestBlob(frame.ref)) {
                val req = frame.ref.split(':').getOrNull(1)?.toIntOrNull()
                req?.let { pending[it]?.blobs?.put(frame.ref, frame.bytes) }
                continue
            }
            netStore.apply(frame, pid, changes)
        }
        if (!changes.isEmpty) listeners.forEach { it.onData(changes) }
    }

    /** `layout:7:0` 这种：feature 名 + 请求号。抓包的 ref 是 `h1:resp`，不会冲突。 */
    private fun isRequestBlob(ref: String): Boolean = ref.startsWith("layout:")

    private fun onResult(json: JsonObject) {
        val req = json.int("req") ?: return
        val p = pending.remove(req) ?: return
        p.callback(Result.success(Response(json, p.blobs)))
    }

    private fun expireRequests() {
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val expired = pending.filterValues { it.deadline < now }
        for ((req, p) in expired) {
            pending.remove(req)
            p.callback(Result.failure(IllegalStateException(HelperBundle.message("conn.timeout"))))
        }
    }

    private fun onHello(json: JsonObject) {
        hello = json
        val pkg = json.str("pkg") ?: endpoint?.pkg
        val sdk = json.str("sdk")?.let { " · SDK $it" } ?: " · netcapture"
        setState(State.CONNECTED, HelperBundle.message("conn.connected", pkg ?: "", json.int("pid")?.toString() ?: "?", sdk))
    }

    private fun setState(s: State, message: String?) {
        state = s
        statusMessage = message
        listeners.forEach { it.onStateChanged(s, message) }
    }

    private companion object {
        const val LAST_PACKAGE_KEY = "kakacat.androidhelper.lastPackage"
    }

    override fun dispose() {
        pump.stop()
        generation++
        connection?.close()
        connection = null
    }
}

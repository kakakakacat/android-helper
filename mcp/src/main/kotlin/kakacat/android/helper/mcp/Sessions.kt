package kakacat.android.helper.mcp

import com.google.gson.JsonObject
import kakacat.android.helper.client.adb.Adb
import kakacat.android.helper.client.adb.Device
import kakacat.android.helper.client.adb.Endpoint
import kakacat.android.helper.client.layout.LayoutSnapshot
import kakacat.android.helper.client.network.NetStore
import kakacat.android.helper.client.network.int
import kakacat.android.helper.client.network.str
import kakacat.android.helper.client.protocol.Frame
import kakacat.android.helper.client.protocol.HelperConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 到一个 App 的长连接。连着的时候 App 才会采集网络请求，所以连上之后就一直保持，
 * agent 后面调 list_requests 才有东西看。
 */
class AppSession(private val adb: Adb, val endpoint: Endpoint) {

    class Response(val json: JsonObject, val blobs: Map<String, ByteArray>)

    private class Pending(val future: CompletableFuture<Response>) {
        val blobs = LinkedHashMap<String, ByteArray>()
    }

    private val lock = Any()
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextReq = AtomicInteger(1)
    private var connection: HelperConnection? = null
    private var helloLatch = CountDownLatch(1)

    @Volatile var hello: JsonObject? = null; private set
    @Volatile var closed = true; private set
    @Volatile var connectedAt = 0L; private set

    /** 只在 [lock] 下读写。 */
    val netStore = NetStore(maxCalls = 2000)
    private var pid = 0

    /** 最近一次 get_view_tree / find_views 的结果；节点 id 只对它有效。 */
    @Volatile var snapshot: LayoutSnapshot? = null

    fun <T> withStore(block: (NetStore) -> T): T = synchronized(lock) { block(netStore) }

    fun ensureConnected() {
        if (!closed) return
        synchronized(this) {
            if (!closed) return
            helloLatch = CountDownLatch(1)
            val conn = HelperConnection(adb, endpoint, object : HelperConnection.Listener {
                override fun onFrame(frame: Frame) = onFrameReceived(frame)
                override fun onClosed(error: Throwable?) {
                    closed = true
                    pending.values.forEach { it.future.completeExceptionally(ToolError("connection to ${endpoint.pkg} closed")) }
                    pending.clear()
                    helloLatch.countDown()
                }
            })
            closed = false
            connection = conn
            conn.start()
            if (!helloLatch.await(10, TimeUnit.SECONDS) || closed) {
                conn.close()
                closed = true
                throw ToolError("could not connect to ${endpoint.pkg} (is the app still running?)")
            }
            connectedAt = System.currentTimeMillis()
        }
    }

    private fun onFrameReceived(frame: Frame) {
        when (frame) {
            is Frame.Control -> {
                val type = frame.json.str("type")
                if (type == "hello") {
                    synchronized(lock) { pid = frame.json.int("pid") ?: 0 }
                    hello = frame.json
                    helloLatch.countDown()
                } else if (type != null && type.endsWith(".result")) {
                    val req = frame.json.int("req") ?: return
                    val p = pending.remove(req) ?: return
                    p.future.complete(Response(frame.json, p.blobs))
                }
            }
            is Frame.Blob -> {
                if (frame.ref.startsWith("layout:")) {
                    val req = frame.ref.split(':').getOrNull(1)?.toIntOrNull() ?: return
                    pending[req]?.blobs?.put(frame.ref, frame.bytes)
                } else {
                    synchronized(lock) { netStore.apply(frame, pid, NetStore.Changes()) }
                }
            }
            is Frame.Event -> synchronized(lock) { netStore.apply(frame, pid, NetStore.Changes()) }
        }
    }

    fun supports(feature: String): Boolean =
        hello?.getAsJsonArray("features")?.any { it.asString == feature } == true

    fun request(json: JsonObject, timeoutMs: Long = 15_000): Response {
        ensureConnected()
        val req = nextReq.getAndIncrement()
        json.addProperty("req", req)
        val p = Pending(CompletableFuture())
        pending[req] = p
        connection?.sendControl(json)
        return try {
            p.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(req)
            throw ToolError("${endpoint.pkg} did not respond within ${timeoutMs / 1000}s")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun close() {
        connection?.close()
        closed = true
    }
}

/**
 * 解析 device / app 参数，复用已有连接。
 *
 * 不传参数时尽量猜：只有一台设备就用它；只有一个接入 SDK 的 App 就用它；
 * 上一次用过的 App 还在就继续用它。猜不出来就报错并列出候选，让 agent 自己选。
 */
class Sessions(private val adbOverride: String?) {

    private val sessions = ConcurrentHashMap<String, AppSession>()
    @Volatile private var lastKey: String? = null

    val adb: Adb by lazy {
        adbOverride?.let { Adb(java.io.File(it)) }
            ?: Adb.locate(System.getProperty("user.dir"))
            ?: throw ToolError("adb not found. Set ANDROID_HOME, or start the server with --adb /path/to/adb")
    }

    fun device(arg: String?): Device {
        val devices = adb.devices()
        val online = devices.filter { it.online }
        if (arg != null) {
            return devices.firstOrNull { it.serial == arg }
                ?: online.firstOrNull { it.model?.contains(arg, ignoreCase = true) == true }
                ?: throw ToolError("no device matches \"$arg\". Devices:\n" + devices.joinToString("\n") { "  ${it.serial}  ${it.model ?: ""}  ${it.state}" })
        }
        lastKey?.let { key -> online.firstOrNull { key.startsWith(it.serial + "|") }?.let { return it } }
        return when (online.size) {
            0 -> throw ToolError("no online device. Connect one over USB with USB debugging enabled (adb devices).")
            1 -> online[0]
            else -> throw ToolError("several devices are connected; pass device=<serial>:\n" + online.joinToString("\n") { "  ${it.serial}  ${it.model ?: ""}" })
        }
    }

    fun session(appArg: String?, deviceArg: String?): AppSession {
        val device = device(deviceArg)
        if (appArg == null) {
            lastKey?.let { key -> sessions[key]?.takeIf { key.startsWith(device.serial + "|") && !it.closed }?.let { return it } }
        }
        val endpoints = adb.endpoints(device.serial)
        val endpoint = when {
            appArg != null -> endpoints.firstOrNull { it.pkg == appArg }
                ?: endpoints.singleOrNull { it.pkg.contains(appArg) }
                ?: throw ToolError("no app matching \"$appArg\" with the Android Helper SDK is running on ${device.serial}. Running: " +
                    endpoints.joinToString(", ") { it.pkg }.ifEmpty { "(none)" })
            endpoints.size == 1 -> endpoints[0]
            endpoints.isEmpty() -> throw ToolError("no debug app with the Android Helper SDK is running on ${device.serial}. " +
                "Start the app (debug build with plugins { id(\"kakacat.android.helper\") }).")
            else -> {
                val last = lastKey?.substringAfter('|')
                endpoints.firstOrNull { it.pkg == last }
                    ?: throw ToolError("several apps are running; pass app=<package>:\n" + endpoints.joinToString("\n") { "  ${it.pkg}" })
            }
        }
        val key = "${device.serial}|${endpoint.pkg}"
        val s = sessions.compute(key) { _, old ->
            // App 重启后 socket 名不变但进程变了：旧连接已断，直接换新的
            if (old != null && !old.closed) old else AppSession(adb, endpoint)
        }!!
        s.ensureConnected()
        lastKey = key
        return s
    }

    fun closeAll() = sessions.values.forEach { it.close() }
}

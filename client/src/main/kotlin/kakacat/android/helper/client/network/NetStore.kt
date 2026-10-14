package kakacat.android.helper.client.network

import com.google.gson.JsonObject
import kakacat.android.helper.client.protocol.Frame

/**
 * 把设备端的帧还原成 [NetCall] 列表。
 *
 * 只在 EDT 上调用（由 NetworkSession 批量喂进来），所以内部不加锁。
 * 和 Swing 无关，单元测试可以直接喂帧。
 */
class NetStore(private val maxCalls: Int = 5000) {

    private val byKey = HashMap<String, NetCall>()

    /**
     * WebSocket 握手的请求头，按 `<pid>|<url>` 暂存。
     *
     * 握手请求会先作为一条普通 HTTP（101 Switching Protocols）经过 OkHttp 拦截器，
     * 而 WS 自己的 start 事件里只有响应头。两条合成一条（和浏览器 DevTools 一样），
     * 请求头挪到 WS 那条上，HTTP 101 那条从列表里去掉。
     */
    private val pendingHandshakes = HashMap<String, List<Pair<String, String>>>()
    private val list = ArrayList<NetCall>()
    private var nextIndex = 1

    /** 设备上报的丢弃数（队列满时），只增不减。 */
    var dropped: Long = 0
        private set

    /** 设备推来的 protobuf descriptor set（FileDescriptorSet 字节）。 */
    var descriptors: ByteArray? = null
        private set

    val calls: List<NetCall> get() = list

    /** 本批次里新增 / 变化的调用，给 UI 做增量刷新用。 */
    class Changes {
        var added = 0
        var removed = 0
        val updated = LinkedHashSet<NetCall>()
        var descriptorsChanged = false
        val isEmpty: Boolean get() = added == 0 && removed == 0 && updated.isEmpty() && !descriptorsChanged
    }

    fun clear() {
        byKey.clear()
        list.clear()
        nextIndex = 1
        dropped = 0
        pendingHandshakes.clear()
    }

    /** [pid] 来自这条连接的 hello。 */
    fun apply(frame: Frame, pid: Int, changes: Changes) {
        when (frame) {
            is Frame.Event -> onEvent(frame.json, pid, changes)
            is Frame.Blob -> onBlob(frame, pid, changes)
            is Frame.Control -> Unit
        }
    }

    private fun onEvent(e: JsonObject, pid: Int, changes: Changes) {
        e.long("dropped")?.let { if (it > dropped) dropped = it }
        val id = e.str("id") ?: return
        val kind = when (e.str("kind")) {
            "http" -> Kind.HTTP
            "grpc" -> Kind.GRPC
            "ws" -> Kind.WS
            else -> return
        }
        val phase = e.str("phase") ?: return
        val key = "$pid:$id"
        var call = byKey[key]
        if (call == null) {
            // 正常情况下第一帧是 start；中途连上时会先看到别的 phase，同样建一条，信息少一点而已。
            call = NetCall(key, kind, nextIndex++, pid)
            call.startTime = e.long("t") ?: System.currentTimeMillis()
            byKey[key] = call
            list.add(call)
            changes.added++
            trim(changes)
        } else {
            changes.updated.add(call)
        }
        call.rawEvents.add(e)
        when (kind) {
            Kind.HTTP -> {
                http(call, phase, e)
                if (phase == "end" && call.status == 101) foldHandshake(call, changes)
            }
            Kind.GRPC -> grpc(call, phase, e)
            Kind.WS -> ws(call, phase, e)
        }
    }

    private fun http(c: NetCall, phase: String, e: JsonObject) {
        when (phase) {
            "start" -> {
                c.method = e.str("method")
                c.url = e.str("url")
                c.host = e.str("host")
                c.path = e.str("path")
                c.query = e.str("query")
                c.reqHeaders = e.pairs("reqHeaders")
                c.reqType = e.str("reqType")
                c.reqLen = e.long("reqLen") ?: -1
                c.thread = e.str("thread")
            }
            "end" -> {
                c.state = CallState.DONE
                c.status = e.int("status")
                c.statusText = e.str("statusText")
                c.protocol = e.str("protocol")
                c.respHeaders = e.pairs("respHeaders")
                c.respType = e.str("respType")
                c.respLen = e.long("respLen") ?: -1
                c.durationMs = e.long("durMs")
                c.redirects = e.int("redirects") ?: 0
                c.fromCache = e.bool("fromCache")
                e.getAsJsonObject("tls")?.let {
                    c.tlsVersion = it.str("version")
                    c.tlsCipher = it.str("cipher")
                }
            }
            "error" -> {
                c.state = CallState.FAILED
                c.error = e.str("error")
                c.durationMs = e.long("durMs")
            }
        }
    }

    private fun grpc(c: NetCall, phase: String, e: JsonObject) {
        when (phase) {
            "start" -> {
                c.grpcMethod = e.str("method")
                c.callType = e.str("callType")
                c.authority = e.str("authority")
                c.metadata = e.pairs("metadata")
                c.deadlineMs = e.long("deadlineMs")
                c.thread = e.str("thread")
            }
            "headers" -> c.respMetadata = e.pairs("metadata")
            "message" -> c.messages.add(message(e))
            "end" -> {
                c.grpcCode = e.str("code")
                c.grpcDescription = e.str("description")
                c.trailers = e.pairs("trailers")
                c.durationMs = e.long("durMs")
                c.state = if (c.grpcCode == "OK") CallState.DONE else CallState.FAILED
                e.str("cause")?.let { c.error = it }
            }
            "cancel" -> {
                c.state = CallState.FAILED
                c.error = "cancelled: " + (e.str("reason") ?: "")
            }
        }
    }

    private fun ws(c: NetCall, phase: String, e: JsonObject) {
        when (phase) {
            "start" -> {
                c.url = e.str("url")
                c.status = e.int("status")
                c.protocol = e.str("protocol")
                c.respHeaders = e.pairs("respHeaders")
                c.thread = e.str("thread")
                c.url?.let { pendingHandshakes.remove("${c.pid}|$it") }?.let { c.reqHeaders = it }
                c.url?.let { u ->
                    runCatching {
                        val uri = java.net.URI(u)
                        c.host = uri.host
                        c.path = uri.rawPath
                        c.query = uri.rawQuery
                    }
                }
            }
            "message" -> c.messages.add(message(e))
            "closing" -> c.closeReason = "closing ${e.int("code") ?: ""}"
            "end" -> {
                c.state = CallState.DONE
                c.closeReason = "closed ${e.int("code") ?: ""}".trim()
                c.durationMs = e.long("t")?.let { it - c.startTime }
            }
            "error" -> {
                c.state = CallState.FAILED
                c.error = e.str("error")
            }
        }
    }

    private fun foldHandshake(http: NetCall, changes: NetStore.Changes) {
        val wsUrl = http.url?.replaceFirst(Regex("^http"), "ws") ?: return
        val ws = list.lastOrNull { it.kind == Kind.WS && it.pid == http.pid && it.url == wsUrl && it.reqHeaders.isEmpty() }
        if (ws != null) {
            ws.reqHeaders = http.reqHeaders
            changes.updated.add(ws)
        } else {
            pendingHandshakes["${http.pid}|$wsUrl"] = http.reqHeaders
        }
        list.remove(http)
        byKey.remove(http.key)
        changes.updated.remove(http)
        changes.removed++
    }

    private fun message(e: JsonObject) = NetMessage(
        dir = e.str("dir") ?: "?",
        seq = e.int("seq") ?: 0,
        type = e.str("opcode") ?: e.str("type"),
        len = e.int("len") ?: -1,
        time = e.long("t") ?: 0,
    )

    private fun onBlob(b: Frame.Blob, pid: Int, changes: Changes) {
        if (b.ref == "descriptors") {
            descriptors = b.bytes
            changes.descriptorsChanged = true
            return
        }
        val colon = b.ref.indexOf(':')
        if (colon <= 0) return
        val call = byKey["$pid:${b.ref.substring(0, colon)}"] ?: return
        val body = Body(b.bytes, b.truncatedFrom)
        when (val part = b.ref.substring(colon + 1)) {
            "req" -> call.reqBody = body
            "resp" -> call.respBody = body
            else -> {
                // "up3" / "down12"
                val dir = part.takeWhile { it.isLetter() }
                val seq = part.substring(dir.length).toIntOrNull() ?: return
                call.messages.lastOrNull { it.dir == dir && it.seq == seq }?.body = body
            }
        }
        changes.updated.add(call)
    }

    /** 超过上限时丢最老的，防止长时间挂着把 IDE 内存吃光。 */
    private fun trim(changes: Changes) {
        val excess = list.size - maxCalls
        if (excess <= 0) return
        repeat(excess) {
            val old = list.removeAt(0)
            byKey.remove(old.key)
            changes.updated.remove(old)
        }
        changes.removed += excess
    }
}

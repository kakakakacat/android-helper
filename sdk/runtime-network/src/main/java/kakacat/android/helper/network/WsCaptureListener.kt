package kakacat.android.helper.network

import kakacat.android.helper.EventSink

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket 帧采集。
 *
 * Interceptor 看不到 WebSocket 的帧（升级完成后 OkHttp 不再走 interceptor 链），所以
 * 下行靠包 listener、上行靠包 WebSocket 对象（见 CaptureWebSocket）。这条链路在使用方
 * 项目里往往是语音/实时业务的核心，不是边角功能。
 *
 * 上下行共用同一个 id 和同一个序号计数器，IDE 按 dir 分列显示。
 */
internal class WsCaptureListener(
    private val url: String,
    private val delegate: WebSocketListener,
) : WebSocketListener() {

    val id: String = EventSink.newId("w")
    private val seq = AtomicInteger(0)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        runCatching {
            EventSink.event("ws", "start", id, JSONObject().apply {
                put("url", url)
                put("status", response.code)
                put("protocol", response.protocol.toString())
                put("respHeaders", JSONObject().apply {
                    val headers = response.headers
                    for (i in 0 until headers.size) put(headers.name(i), headers.value(i))
                })
            })
        }
        delegate.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        runCatching { message("down", "text", text.toByteArray(Charsets.UTF_8)) }
        delegate.onMessage(webSocket, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        runCatching { message("down", "binary", bytes.toByteArray()) }
        delegate.onMessage(webSocket, bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        runCatching {
            EventSink.event("ws", "closing", id, JSONObject().apply {
                put("code", code)
                put("reason", reason)
            })
        }
        delegate.onClosing(webSocket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        runCatching {
            EventSink.event("ws", "end", id, JSONObject().apply {
                put("code", code)
                put("reason", reason)
                put("frames", seq.get())
            })
        }
        delegate.onClosed(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        runCatching {
            EventSink.event("ws", "error", id, JSONObject().apply {
                put("error", t.javaClass.simpleName + ": " + (t.message ?: ""))
                put("frames", seq.get())
            })
        }
        delegate.onFailure(webSocket, t, response)
    }

    /** 由 CaptureWebSocket 调用，记录上行帧。 */
    fun sent(opcode: String, bytes: ByteArray) {
        runCatching { message("up", opcode, bytes) }
    }

    private fun message(dir: String, opcode: String, bytes: ByteArray) {
        if (!EventSink.active) return
        val n = seq.incrementAndGet()
        val limit = if (opcode == "text") NetworkFeature.MAX_TEXT_BODY else NetworkFeature.MAX_BINARY_BODY
        EventSink.event("ws", "message", id, JSONObject().apply {
            put("dir", dir)
            put("opcode", opcode)
            put("seq", n)
            put("len", bytes.size)
        })
        val take = minOf(bytes.size, limit)
        EventSink.blob(
            "$id:$dir$n",
            if (take == bytes.size) bytes else bytes.copyOf(take),
            if (take < bytes.size) bytes.size else -1
        )
    }
}

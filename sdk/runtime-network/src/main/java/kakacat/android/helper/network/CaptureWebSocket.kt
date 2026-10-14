package kakacat.android.helper.network

import okhttp3.WebSocket
import okio.ByteString

/**
 * 转发壳，用来采**上行**帧。
 *
 * `newWebSocket()` 的返回值就是业务后面用来 `send()` 的那个对象，插桩把它换成这个壳，
 * 于是业务发出去的每一帧都能记下来。手工接入做不到这件事：那需要业务主动把
 * WebSocket 对象交出来。
 *
 * `send()` 的返回值必须原样透传 —— 它表示"帧有没有进入发送队列"，业务会据此做背压。
 */
internal class CaptureWebSocket(
    private val delegate: WebSocket,
    private val capture: WsCaptureListener,
) : WebSocket {

    override fun request() = delegate.request()

    override fun queueSize(): Long = delegate.queueSize()

    override fun send(text: String): Boolean {
        val accepted = delegate.send(text)
        // 只记真的进了队列的帧，否则 IDE 里会出现业务其实没发出去的内容。
        if (accepted) capture.sent("text", text.toByteArray(Charsets.UTF_8))
        return accepted
    }

    override fun send(bytes: ByteString): Boolean {
        val accepted = delegate.send(bytes)
        if (accepted) capture.sent("binary", bytes.toByteArray())
        return accepted
    }

    override fun close(code: Int, reason: String?): Boolean = delegate.close(code, reason)

    override fun cancel() = delegate.cancel()
}

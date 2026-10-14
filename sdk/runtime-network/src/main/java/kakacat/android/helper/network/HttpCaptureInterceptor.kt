package kakacat.android.helper.network

import kakacat.android.helper.EventSink

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.io.IOException

/**
 * OkHttp 采集。挂在**应用层** interceptor（不是 network interceptor），理由：
 *
 *  * 这一层拿到的是 OkHttp 已经透明解压过的 body，直接就是明文，不用自己处理 gzip；
 *  * `Response.protocol` / `Response.handshake` 在这一层同样能拿到，TLS 版本和
 *    cipher 不需要 network interceptor 就有；
 *  * 重定向和重试在这一层是一次调用，符合"用户发了一个请求"的直觉，
 *    次数从 `priorResponse` 链上数。
 *
 * 绝不碰 EventListener：Builder 没有 getter，设置就等于覆盖业务已有的 factory。
 */
internal class HttpCaptureInterceptor : Interceptor {

    /** 这些内容类型只记元数据 —— 读了就是自找 OOM，或者会拖垮实时链路。 */
    private fun skipByType(contentType: String?): Boolean {
        val t = contentType?.lowercase() ?: return false
        return t.startsWith("text/event-stream") ||   // SSE：无限流，读了就不会返回
            t.startsWith("audio/") ||                 // ASR 上行音频
            t.startsWith("video/") ||
            t.startsWith("application/octet-stream")
    }

    /**
     * 请求体：`writeTo` 会把 body 整个物化到内存里，所以长度未知（chunked / 流式上传）
     * 时宁可不采 —— 一个文件上传就能把进程打崩。
     */
    private fun skipRequestBody(contentType: String?, length: Long): Boolean =
        length < 0 || length > NetworkFeature.MAX_TEXT_BODY || skipByType(contentType)

    /**
     * 响应体：`peekBody` 自带上限，长度未知很常见（chunked、gzip 解压后），不能因此跳过，
     * 否则大部分响应都看不见了。
     */
    private fun skipResponseBody(contentType: String?, declared: Long): Boolean =
        declared > NetworkFeature.MAX_TEXT_BODY || skipByType(contentType)

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!EventSink.active) return chain.proceed(chain.request())

        val request = chain.request()
        val id = EventSink.newId("h")
        val startedAt = System.nanoTime()

        runCatching {
            val url = request.url
            val body = request.body
            val len = body?.contentLength() ?: 0L
            val type = body?.contentType()?.toString()
            EventSink.event("http", "start", id, JSONObject().apply {
                put("method", request.method)
                put("url", url.toString())
                put("host", url.host)
                put("path", url.encodedPath)
                put("query", url.encodedQuery ?: "")
                put("reqHeaders", headersJson(request.headers))
                put("reqLen", len)
                put("reqType", type ?: JSONObject.NULL)
            })

            // one-shot / duplex body 读一次就没了，绝不能碰。
            if (body != null && !body.isOneShot() && !body.isDuplex() && !skipRequestBody(type, len)) {
                val buffer = Buffer()
                body.writeTo(buffer)
                val size = buffer.size
                val take = minOf(size, NetworkFeature.MAX_TEXT_BODY.toLong())
                EventSink.blob(
                    "$id:req",
                    buffer.readByteArray(take),
                    if (take < size) size.toInt() else -1
                )
            }
        }

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            runCatching {
                EventSink.event("http", "error", id, JSONObject().apply {
                    put("error", e.javaClass.simpleName + ": " + (e.message ?: ""))
                    put("durMs", elapsedMs(startedAt))
                })
            }
            throw e
        }

        runCatching {
            val body = response.body
            val contentType = body?.contentType()?.toString()
            val declared = body?.contentLength() ?: -1L
            EventSink.event("http", "end", id, JSONObject().apply {
                put("status", response.code)
                put("statusText", response.message)
                put("protocol", response.protocol.toString())
                put("respHeaders", headersJson(response.headers))
                put("respType", contentType ?: JSONObject.NULL)
                put("respLen", declared)
                put("durMs", elapsedMs(startedAt))
                put("redirects", priorCount(response))
                put("fromCache", response.cacheResponse != null)
                response.handshake?.let { hs ->
                    put("tls", JSONObject().apply {
                        put("version", hs.tlsVersion.javaName)
                        put("cipher", hs.cipherSuite.javaName)
                    })
                }
            })

            if (!skipResponseBody(contentType, declared)) {
                // peekBody 是唯一安全的读法：它复制一份，业务照旧能完整读到 body。
                val peeked = response.peekBody(NetworkFeature.MAX_TEXT_BODY.toLong())
                val bytes = peeked.bytes()
                EventSink.blob(
                    "$id:resp",
                    bytes,
                    if (declared > bytes.size) declared.toInt() else -1
                )
            }
        }

        return response
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun priorCount(response: Response): Int {
        var n = 0
        var r = response.priorResponse
        while (r != null) {
            n++
            r = r.priorResponse
        }
        return n
    }

    private fun headersJson(headers: Headers): JSONObject = JSONObject().apply {
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            // 同名 header 合并成逗号分隔，和 HTTP 语义一致
            val existing = optString(name, "")
            put(name, if (existing.isEmpty()) headers.value(i) else "$existing, ${headers.value(i)}")
        }
    }
}

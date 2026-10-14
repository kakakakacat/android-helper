package kakacat.android.helper.network

import kakacat.android.helper.EventSink
import org.json.JSONObject

/**
 * 脱敏。**在事件离开业务进程之前**执行，不是在 IDE 里隐藏 —— 一旦字节进了 USB
 * 就再也收不回来了。
 *
 * 跑在 sink 的后台线程上，所以可以用正则；业务的网络线程上不做这件事。
 */
internal object Redaction : EventSink.Filter {

    private const val MASK = "<redacted>"

    /** header 名（小写比较）。 */
    private val HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "x-auth-token",
        "x-access-token",
    )

    /** gRPC metadata key 同样处理，另外加上项目里实际在用的鉴权 key。 */
    private val METADATA = HEADERS + setOf("token", "access-token", "authorization-bin")

    /**
     * URL query 参数名，**按子串匹配**。
     *
     * 精确匹配在实测里漏了 S3 预签名 URL 的 `X-Amz-Signature` / `X-Amz-Credential` ——
     * 那串东西本身就是一份 48 小时有效的凭证，不该出现在调试工具里。代价是这类 URL 复制出去
     * 不能直接重放；真需要重放时改这里就行。
     */
    private val QUERY = listOf(
        "token", "signature", "credential", "password", "secret", "apikey", "api_key",
    )

    /** 文本 body 里的 JSON 字段名。故意只做一层字符串替换，不解析 —— body 可能不是合法 JSON。 */
    private val BODY_KEYS = listOf(
        "password", "passwd", "access_token", "refresh_token", "id_token",
        "secret", "apiKey", "api_key", "token",
    )

    private val bodyPatterns = BODY_KEYS.map { key ->
        Regex("(\"${Regex.escape(key)}\"\\s*:\\s*\")([^\"]*)(\")", RegexOption.IGNORE_CASE)
    }

    private val queryPattern = Regex("([?&])([^=&]+)=([^&]*)")

    override fun onEvent(json: JSONObject) = apply(json)

    override fun onBlob(ref: String, bytes: ByteArray): ByteArray = body(ref, bytes)

    fun apply(json: JSONObject) {
        maskHeaders(json.optJSONObject("reqHeaders"), HEADERS)
        maskHeaders(json.optJSONObject("respHeaders"), HEADERS)
        maskHeaders(json.optJSONObject("metadata"), METADATA)
        maskHeaders(json.optJSONObject("trailers"), METADATA)
        json.optString("url").takeIf { it.isNotEmpty() }?.let { json.put("url", maskQuery(it)) }
        json.optString("query").takeIf { it.isNotEmpty() }?.let {
            json.put("query", maskQuery("?$it").removePrefix("?"))
        }
    }

    /**
     * body 脱敏。只对看起来是文本的内容做（protobuf 里做字符串替换会破坏 wire 格式，
     * 那种情况下只能靠上面的 header/metadata 脱敏 + 客户端不落盘）。
     */
    fun body(ref: String, bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || !looksTextual(bytes)) return bytes
        val text = String(bytes, Charsets.UTF_8)
        var out = text
        for (p in bodyPatterns) out = p.replace(out) { m -> m.groupValues[1] + MASK + m.groupValues[3] }
        return if (out === text) bytes else out.toByteArray(Charsets.UTF_8)
    }

    private fun maskHeaders(headers: JSONObject?, names: Set<String>) {
        if (headers == null) return
        val keys = headers.keys().asSequence().toList()
        for (k in keys) {
            if (k.lowercase() in names) headers.put(k, MASK)
        }
    }

    private fun maskQuery(url: String): String =
        queryPattern.replace(url) { m ->
            val (sep, name, value) = m.destructured
            val lower = name.lowercase()
            if (QUERY.any { lower.contains(it) }) "$sep$name=$MASK" else "$sep$name=$value"
        }

    /** 采样前 512 字节判断是不是文本：控制字符多就当二进制。 */
    private fun looksTextual(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 512)
        var suspicious = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt()
            if (b == 0) return false
            if (b in 1..8 || b in 14..31) suspicious++
        }
        return suspicious * 20 < n
    }
}

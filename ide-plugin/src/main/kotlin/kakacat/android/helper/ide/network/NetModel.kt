package kakacat.android.helper.ide.network

import kakacat.android.helper.ide.HelperBundle
import com.google.gson.JsonElement
import com.google.gson.JsonObject

enum class Kind(val label: String) { HTTP("HTTP"), GRPC("gRPC"), WS("WS") }

enum class CallState { PENDING, DONE, FAILED }

/** 一段 body。[truncatedFrom] ≥ 0 表示设备侧截断过，值是原始长度。 */
class Body(val bytes: ByteArray, val truncatedFrom: Int) {
    val truncated: Boolean get() = truncatedFrom >= 0
}

/** gRPC 的一条流式消息，或者 WebSocket 的一帧。 */
class NetMessage(
    val dir: String,          // "up" | "down"
    val seq: Int,
    val type: String?,        // gRPC: Java 类名；WS: "text" | "binary"
    val len: Int,
    val time: Long,
) {
    var body: Body? = null
}

/**
 * 一次调用：一个 HTTP 请求、一次 gRPC call，或者一条 WebSocket 连接。
 *
 * 字段直接对应设备端事件里的字段（见 runtime-network 的 *CaptureInterceptor），
 * 只在 EDT 上读写。
 */
class NetCall(
    /** `<pid>:<id>`。id 在进程内唯一，App 重启后会从 1 重新数，所以要带上 pid。 */
    val key: String,
    val kind: Kind,
    /** 在列表里的序号，从 1 开始。 */
    val index: Int,
    val pid: Int,
) {
    var state = CallState.PENDING
    var startTime: Long = 0
    var thread: String? = null

    // HTTP / WS
    var method: String? = null
    var url: String? = null
    var host: String? = null
    var path: String? = null
    var query: String? = null
    var reqHeaders: List<Pair<String, String>> = emptyList()
    var reqType: String? = null
    var reqLen: Long = -1
    var status: Int? = null
    var statusText: String? = null
    var protocol: String? = null
    var respHeaders: List<Pair<String, String>> = emptyList()
    var respType: String? = null
    var respLen: Long = -1
    var redirects: Int = 0
    var fromCache: Boolean = false
    var tlsVersion: String? = null
    var tlsCipher: String? = null

    // gRPC
    var grpcMethod: String? = null
    var callType: String? = null
    var authority: String? = null
    var metadata: List<Pair<String, String>> = emptyList()
    var respMetadata: List<Pair<String, String>> = emptyList()
    var trailers: List<Pair<String, String>> = emptyList()
    var grpcCode: String? = null
    var grpcDescription: String? = null
    var deadlineMs: Long? = null

    // 共用
    var durationMs: Long? = null
    var error: String? = null
    var closeReason: String? = null

    var reqBody: Body? = null
    var respBody: Body? = null
    val messages = mutableListOf<NetMessage>()

    /** 原始事件，详情页"Raw"标签用。 */
    val rawEvents = mutableListOf<JsonObject>()

    /** 列表里"Name"一栏：HTTP 显示 path，gRPC 显示 Service/Method，WS 显示 path。 */
    val name: String
        get() = when (kind) {
            Kind.GRPC -> grpcMethod ?: MISSING_START
            else -> buildString {
                append(path ?: url ?: MISSING_START)
                if (!query.isNullOrEmpty()) append('?').append(query)
            }
        }

    val displayHost: String
        get() = when (kind) {
            Kind.GRPC -> authority ?: ""
            else -> host ?: url?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: ""
        }

    val displayMethod: String
        get() = when (kind) {
            Kind.HTTP -> method ?: ""
            Kind.GRPC -> callType?.let(::shortCallType) ?: "gRPC"
            Kind.WS -> "WS"
        }

    val displayStatus: String
        get() = when {
            error != null -> "ERR"
            kind == Kind.GRPC -> grpcCode ?: if (state == CallState.PENDING) "…" else ""
            kind == Kind.WS -> when (state) {
                CallState.PENDING -> if (status != null) "open" else "…"
                else -> closeReason ?: "closed"
            }
            else -> status?.toString() ?: "…"
        }

    /** 列表里标红：HTTP ≥ 400、gRPC 非 OK、任何 error。 */
    val isFailure: Boolean
        get() = error != null || state == CallState.FAILED ||
            (kind == Kind.HTTP && (status ?: 0) >= 400) ||
            (kind == Kind.GRPC && grpcCode != null && grpcCode != "OK")

    /** 列表里"Size"一栏：响应体大小；流式调用是消息总字节数。 */
    val displaySize: Long
        get() = when (kind) {
            Kind.HTTP -> when {
                respBody != null -> (respBody!!.truncatedFrom.takeIf { it >= 0 } ?: respBody!!.bytes.size).toLong()
                else -> respLen
            }
            else -> messages.sumOf { it.len.toLong().coerceAtLeast(0) }
        }

    private fun shortCallType(t: String): String = when (t) {
        "UNARY" -> "unary"
        "SERVER_STREAMING" -> "s-stream"
        "CLIENT_STREAMING" -> "c-stream"
        "BIDI_STREAMING" -> "bidi"
        else -> t.lowercase()
    }
}

/** 调用在连上之前就开始了，没收到 start 事件，不知道 URL / 方法名。 */
private val MISSING_START: String get() = HelperBundle.message("call.missingStart")

internal fun JsonObject.str(name: String): String? {
    val e: JsonElement = get(name) ?: return null
    return if (e.isJsonNull) null else e.asString
}

internal fun JsonObject.long(name: String): Long? {
    val e = get(name) ?: return null
    return if (e.isJsonNull) null else runCatching { e.asLong }.getOrNull()
}

internal fun JsonObject.int(name: String): Int? = long(name)?.toInt()

internal fun JsonObject.bool(name: String): Boolean = get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

internal fun JsonObject.pairs(name: String): List<Pair<String, String>> {
    val o = get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    return o.entrySet().map { (k, v) -> k to (if (v.isJsonNull) "" else if (v.isJsonPrimitive) v.asString else v.toString()) }
}

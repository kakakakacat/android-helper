package kakacat.android.helper.network

import kakacat.android.helper.EventSink

import com.google.protobuf.MessageLite
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * gRPC 采集。由插桩在 `XxxChannelBuilder.build()` 的返回值上挂进来（见 Hooks.grpc /
 * CaptureManagedChannel）。
 *
 * 语义上必须守住三件事，否则会造成比"看不到数据"严重得多的后果：
 *
 *  * 不吞不改任何回调，`super` 一律要调；
 *  * 不碰 gRPC Context（不切线程、不包 Runnable），否则取消和超时会错乱；
 *  * 不影响 backpressure（不在 onMessage 里做慢操作）—— 序列化只有一次
 *    `toByteArray()`，脱敏和成帧都在 EventSink 的后台线程上。
 *
 * unary 和四种 streaming 用同一份代码：一次调用一个 id，上下行各自维护序号。
 */
internal class GrpcCaptureInterceptor : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val delegate = next.newCall(method, callOptions)
        if (!EventSink.active) return delegate

        val id = EventSink.newId("g")
        val startedAt = System.nanoTime()
        val up = AtomicInteger(0)
        val down = AtomicInteger(0)

        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(delegate) {

            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                runCatching {
                    // 注意：`AuthCallCredentials` 那类通过 CallCredentials 加的 header
                    // 是 transport 在所有 interceptor 之后才写进去的，任何
                    // ClientInterceptor 都看不到 —— 所以这里的 metadata 经常是空的，
                    // 不是漏采。
                    EventSink.event("grpc", "start", id, JSONObject().apply {
                        put("method", method.fullMethodName)
                        put("service", method.serviceName ?: JSONObject.NULL)
                        put("callType", method.type.name)
                        put("authority", callOptions.authority ?: next.authority())
                        put("metadata", metadataJson(headers))
                        callOptions.deadline?.let {
                            put("deadlineMs", it.timeRemaining(TimeUnit.MILLISECONDS))
                        }
                    })
                }

                val wrapped = object :
                    ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                        responseListener
                    ) {
                    override fun onHeaders(headers: Metadata) {
                        runCatching {
                            EventSink.event("grpc", "headers", id, JSONObject().apply {
                                put("metadata", metadataJson(headers))
                            })
                        }
                        super.onHeaders(headers)
                    }

                    override fun onMessage(message: RespT) {
                        runCatching { capture(id, "down", down.incrementAndGet(), message) }
                        super.onMessage(message)
                    }

                    override fun onClose(status: Status, trailers: Metadata) {
                        runCatching {
                            EventSink.event("grpc", "end", id, JSONObject().apply {
                                put("code", status.code.name)
                                put("codeValue", status.code.value())
                                put("description", status.description ?: JSONObject.NULL)
                                status.cause?.let {
                                    put("cause", it.javaClass.simpleName + ": " + (it.message ?: ""))
                                }
                                put("trailers", metadataJson(trailers))
                                put("durMs", (System.nanoTime() - startedAt) / 1_000_000)
                                put("upCount", up.get())
                                put("downCount", down.get())
                            })
                        }
                        super.onClose(status, trailers)
                    }
                }
                super.start(wrapped, headers)
            }

            override fun sendMessage(message: ReqT) {
                runCatching { capture(id, "up", up.incrementAndGet(), message) }
                super.sendMessage(message)
            }

            override fun cancel(message: String?, cause: Throwable?) {
                runCatching {
                    EventSink.event("grpc", "cancel", id, JSONObject().apply {
                        put("reason", message ?: JSONObject.NULL)
                        cause?.let { put("cause", it.javaClass.simpleName) }
                    })
                }
                super.cancel(message, cause)
            }
        }
    }

    /**
     * 消息落盘为**裸 protobuf 字节**，字段解析全部留给 IDE。
     *
     * 项目用的是 protobuf-**lite**，运行时没有 descriptor，设备侧本来就还原不出字段名；
     * 与其在这里做半吊子的转换，不如原样送出去，设备上只花一次 `toByteArray()`。
     */
    private fun capture(id: String, dir: String, seq: Int, message: Any?) {
        if (!EventSink.active) return
        val lite = message as? MessageLite
        val size = lite?.serializedSize ?: -1
        EventSink.event("grpc", "message", id, JSONObject().apply {
            put("dir", dir)
            put("seq", seq)
            put("len", size)
            put("type", message?.javaClass?.name ?: JSONObject.NULL)
        })
        if (lite == null || size <= 0) return
        if (size > NetworkFeature.MAX_BINARY_BODY) {
            // 超限只记长度：流式音频/大图一条就能把队列打满。
            return
        }
        EventSink.blob("$id:$dir$seq", lite.toByteArray())
    }

    private fun metadataJson(metadata: Metadata): JSONObject = JSONObject().apply {
        for (key in metadata.keys()) {
            if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                put(key, "<binary>")
                continue
            }
            val values = metadata.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER))
            put(key, values?.joinToString(", ") ?: "")
        }
    }
}

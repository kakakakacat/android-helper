package kakacat.android.helper.network

import io.grpc.ManagedChannel
import kakacat.android.helper.EventSink
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 构建期插桩唯一会调用到的入口。
 *
 * Gradle 插件把字节码里的调用点改写成对这里的静态调用，栈形状刻意和被替换的方法一致，
 * 所以插桩只是"换一条 invoke 指令"，不需要动局部变量表、不需要重算 frame：
 *
 * | 原调用点 | 改写成 |
 * |---|---|
 * | `OkHttpClient.Builder.build()` | `Hooks.okhttp(builder)` |
 * | `OkHttpClient.newWebSocket(req, listener)` | `Hooks.newWebSocket(client, req, listener)` |
 * | `XxxChannelBuilder.build()` → ManagedChannel | 原调用后追加 `Hooks.grpc(channel)` |
 *
 * 三条铁律，全部由本文件承担：
 *
 *  1. **绝不改变业务语义**。每个方法都必须返回和原调用等价的对象。
 *  2. **绝不抛异常**。插桩后的代码在业务的关键路径上，抓包出问题只能是"抓不到"，
 *     不能是"业务崩了"。所以每个方法都是 try/catch 兜底 + 返回原值。
 *  3. **没人订阅时零成本**。判断只有一次 volatile 读。
 */
object Hooks {

    @JvmStatic
    fun okhttp(builder: OkHttpClient.Builder): OkHttpClient {
        try {
            // 幂等：同一个 builder 被 newBuilder 链式复用时不能出现两份数据。
            if (builder.interceptors().none { it is HttpCaptureInterceptor }) {
                builder.addInterceptor(HttpCaptureInterceptor())
            }
        } catch (t: Throwable) {
            // 落到原始行为
        }
        return builder.build()
    }

    /**
     * WebSocket。这里同时包住 listener（下行帧）和返回的 WebSocket（上行帧）——
     * 上行是手工接入拿不到的：那需要业务自己把 WebSocket 对象交出来。
     */
    @JvmStatic
    fun newWebSocket(
        client: OkHttpClient,
        request: Request,
        listener: WebSocketListener,
    ): WebSocket {
        if (!EventSink.active) return client.newWebSocket(request, listener)
        return try {
            val url = request.url.toString()
            val capture = WsCaptureListener(url, listener)
            CaptureWebSocket(client.newWebSocket(request, capture), capture)
        } catch (t: Throwable) {
            client.newWebSocket(request, listener)
        }
    }

    /**
     * gRPC。`ManagedChannelBuilder.build()` 的返回值必须仍然是 `ManagedChannel`，
     * 所以不能直接用 `ClientInterceptors.intercept()`（它返回 `Channel`）——
     * 见 CaptureManagedChannel。
     */
    @JvmStatic
    fun grpc(channel: ManagedChannel): ManagedChannel = try {
        CaptureManagedChannel(channel, GrpcCaptureInterceptor())
    } catch (t: Throwable) {
        channel
    }
}

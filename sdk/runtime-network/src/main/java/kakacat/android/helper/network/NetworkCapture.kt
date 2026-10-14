package kakacat.android.helper.network

import kakacat.android.helper.EventSink
import okhttp3.OkHttpClient
import okhttp3.WebSocketListener

/**
 * 手工接入入口。
 *
 * 正常用法下**一行都不用调** —— Gradle 插件会插桩调用点。这里保留是为了：
 *
 *  * 插桩覆盖不到的地方（比如某个三方 AAR 内部自建的 client，而你不想开 `scope = "all"`）；
 *  * 想在代码里判断"现在有没有人在抓"，据此少做点日志之类。
 */
object NetworkCapture {

    /** 是否有客户端正在订阅。没人订阅时采集点全部短路，开销是一次 volatile 读。 */
    val capturing: Boolean get() = EventSink.active

    /** 手工挂 HTTP 采集。只加 interceptor，不碰 EventListener（那会覆盖业务的 factory）。 */
    @JvmStatic
    fun okhttp(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (builder.interceptors().none { it is HttpCaptureInterceptor }) {
            builder.addInterceptor(HttpCaptureInterceptor())
        }
        return builder
    }

    /**
     * 手工挂 WebSocket 采集。注意这条路**只能拿到下行帧**：上行需要包住
     * `newWebSocket()` 的返回值，那只有插桩做得到（见 Hooks.newWebSocket）。
     */
    @JvmStatic
    fun ws(url: String, listener: WebSocketListener): WebSocketListener =
        if (listener is WsCaptureListener) listener else WsCaptureListener(url, listener)
}

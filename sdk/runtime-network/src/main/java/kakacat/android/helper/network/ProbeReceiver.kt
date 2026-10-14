package kakacat.android.helper.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 按需发一个 HTTP 请求，用来自检抓包链路（IDE 里的"探针"按钮就是发这个广播）：
 *
 * ```
 * adb shell am broadcast -n <pkg>/kakacat.android.helper.network.ProbeReceiver \
 *     -a kakacat.android.helper.PROBE [--es url https://…]
 * ```
 *
 * `exported=true` 是必须的：`adb shell` 是另一个 uid，不导出就收不到广播。作为代偿，
 * URL 只接受 https，且不回传响应内容 —— 结果只出现在抓包通道里。
 *
 * 依赖使用方自己有 OkHttp；没有 OkHttp 的项目点探针只会在 logcat 里报一行错。
 */
class ProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra("url")?.takeIf { it.startsWith("https://") } ?: DEFAULT_URL
        Log.i(TAG, "probe -> $url")
        try {
            // 走和业务完全一样的入口，否则这个自检就没有意义了。
            val client = NetworkCapture.okhttp(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "probe failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { Log.i(TAG, "probe ${it.code} ${it.body?.contentLength() ?: -1}B") }
                }
            })
        } catch (t: LinkageError) {
            Log.w(TAG, "probe needs OkHttp on the classpath: $t")
        }
    }

    companion object {
        private const val TAG = "AndroidHelper"

        /** 小、快、无副作用，返回一段 JSON，顺带验证 IDE 里的 JSON 格式化视图。 */
        private const val DEFAULT_URL = "https://httpbin.org/json"
    }
}

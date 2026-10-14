package kakacat.android.helper.sample

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/**
 * 造流量用的示例页。业务代码里没有任何 Android Helper 的调用 —— OkHttpClient 的
 * build() 和 newWebSocket() 都由 Gradle 插件在构建期改写。
 */
class MainActivity : Activity() {

    private val client = OkHttpClient.Builder().build()
    private lateinit var log: TextView
    private var socket: WebSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        log = findViewById(R.id.log)
        val buttons = findViewById<LinearLayout>(R.id.buttons)
        buttons.addView(button("GET json") { get("https://httpbin.org/json") })
        buttons.addView(button("GET 404") { get("https://httpbin.org/status/404") })
        buttons.addView(button("POST json") { postJson() })
        buttons.addView(button("GET image") { get("https://httpbin.org/image/png") })
        buttons.addView(button("WebSocket echo") { webSocket() })
        buttons.addView(button("Show dialog") { showDialog() })
        buttons.addView(button("Compose screen") {
            startActivity(android.content.Intent(this, ComposeActivity::class.java))
        })
        burstIfAsked(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        burstIfAsked(intent)
    }

    /**
     * 不点屏幕也能造一批流量，方便联调 / 自动化测试：
     * `adb shell am start -n kakacat.android.helper.sample/.MainActivity --ez burst true`
     * （`--ez dialog true` 弹一个 Dialog，用来验证多窗口）
     */
    private fun burstIfAsked(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("dialog", false) == true) {
            intent.removeExtra("dialog")
            showDialog()
        }
        if (intent?.getBooleanExtra("burst", false) != true) return
        intent.removeExtra("burst")
        get("https://httpbin.org/json")
        get("https://httpbin.org/status/404")
        postJson()
        get("https://httpbin.org/image/png")
        webSocket()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun showDialog() {
        AlertDialog.Builder(this)
            .setTitle("Hello")
            .setMessage("这个 Dialog 是在 MainActivity.showDialog() 里弹出的")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun get(url: String) {
        client.newCall(Request.Builder().url(url).header("X-Sample", "1").build()).enqueue(callback)
    }

    private fun postJson() {
        val body = """{"user":"demo","password":"hunter2","items":[1,2,3]}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://httpbin.org/post?token=abc&page=1")
            .header("Authorization", "Bearer secret")
            .post(body)
            .build()
        client.newCall(request).enqueue(callback)
    }

    private fun webSocket() {
        socket?.close(1000, "restart")
        socket = client.newWebSocket(
            Request.Builder().url("wss://ws.postman-echo.com/raw").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"hello":"world"}""")
                    webSocket.send("second frame")
                    show("ws open ${response.code}")
                }

                override fun onMessage(webSocket: WebSocket, text: String) = show("ws <- $text")

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    show("ws failed: ${t.message}")
            }
        )
    }

    private val callback = object : Callback {
        override fun onFailure(call: Call, e: IOException) = show("${call.request().url} failed: ${e.message}")

        override fun onResponse(call: Call, response: Response) {
            response.use { show("${it.code} ${call.request().url}") }
        }
    }

    private fun show(line: String) = runOnUiThread { log.append(line + "\n") }
}

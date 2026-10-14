package kakacat.android.helper.ide.protocol

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import kakacat.android.helper.ide.adb.Adb
import kakacat.android.helper.ide.adb.Endpoint
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * 到一个 App 的长连接：`adb forward tcp:0 localabstract:<socket>`，然后普通 TCP。
 *
 * 一次 forward、一条 socket，之后每帧都是毫秒级 —— 不像 CodeLocator 每次操作都
 * `am broadcast`（每次要在设备上拉起一个 app_process，固定几百毫秒）。
 *
 * 读循环跑在自己的线程上，帧通过 [listener] 回调出去（**在读线程上**，调用方自己切线程）。
 */
class HelperConnection(
    private val adb: Adb,
    val endpoint: Endpoint,
    private val listener: Listener,
) {

    interface Listener {
        fun onFrame(frame: Frame)

        /** 连接结束。[error] 为 null 表示是我们自己关的。 */
        fun onClosed(error: Throwable?)
    }

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var port: Int = -1

    @Volatile
    private var closedByUs = false

    private var out: OutputStream? = null

    fun start() {
        Thread({ run() }, "AndroidHelper-${endpoint.pkg}").apply {
            isDaemon = true
            start()
        }
    }

    private fun run() {
        var error: Throwable? = null
        try {
            port = adb.forward(endpoint.serial, endpoint.socket)
            val s = Socket(InetAddress.getLoopbackAddress(), port)
            s.tcpNoDelay = true
            socket = s
            synchronized(this) { out = s.getOutputStream() }
            if (closedByUs) return
            val reader = FrameReader(s.getInputStream())
            while (true) {
                val frame = reader.next() ?: break
                listener.onFrame(frame)
            }
        } catch (t: Throwable) {
            if (!closedByUs) {
                error = t
                LOG.info("connection to ${endpoint.socket} ended: $t")
            }
        } finally {
            cleanup()
            listener.onClosed(if (closedByUs) null else error)
        }
    }

    fun sendControl(json: JsonObject) {
        val frame = Frame.encodeControl(json)
        Thread({
            runCatching { synchronized(this) { out?.apply { write(frame); flush() } } }
        }, "AndroidHelper-send").apply { isDaemon = true; start() }
    }

    /** 可以在任意线程调用；adb 清理放到后台。 */
    fun close() {
        closedByUs = true
        runCatching { socket?.close() }
    }

    private fun cleanup() {
        runCatching { socket?.close() }
        val p = port
        if (p > 0) {
            port = -1
            adb.removeForward(endpoint.serial, p)
        }
    }

    private companion object {
        val LOG = logger<HelperConnection>()
    }
}

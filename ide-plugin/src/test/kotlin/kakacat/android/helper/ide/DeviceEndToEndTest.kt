package kakacat.android.helper.ide

import kakacat.android.helper.ide.adb.Adb
import kakacat.android.helper.ide.network.CallState
import kakacat.android.helper.ide.network.NetStore
import kakacat.android.helper.ide.protocol.Frame
import kakacat.android.helper.ide.protocol.HelperConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 真机端到端：adb → forward → 帧 → NetStore，走的是插件里同一套代码（除了 Swing）。
 *
 * 需要设备上跑着 sdk/sample 的 debug 包，且显式打开：
 * ```
 * ANDROIDHELPER_E2E=1 ./gradlew test --tests '*DeviceEndToEndTest*'
 * ```
 */
class DeviceEndToEndTest {

    private val pkg = "kakacat.android.helper.sample"

    @Test
    fun probeRequestArrivesWithBody() {
        assumeTrue(System.getenv("ANDROIDHELPER_E2E") == "1")
        val adb = Adb.locate(null)!!
        val device = adb.devices().first { it.online }
        val endpoint = adb.endpoints(device.serial).firstOrNull { it.pkg == pkg }
        assertNotNull("设备上没有运行 $pkg", endpoint)
        assertTrue("新 SDK 不应被识别成 legacy", !endpoint!!.legacy)

        fun forwards() = adb.run("-s", device.serial, "forward", "--list").stdout.lines()
            .filter { it.contains(endpoint.socket) }.toSet()
        val forwardsBefore = forwards()

        val frames = LinkedBlockingQueue<Frame>()
        val closed = CountDownLatch(1)
        val conn = HelperConnection(adb, endpoint, object : HelperConnection.Listener {
            override fun onFrame(frame: Frame) { frames.add(frame) }
            override fun onClosed(error: Throwable?) { closed.countDown() }
        })
        conn.start()

        val hello = frames.poll(10, TimeUnit.SECONDS) as Frame.Control
        assertEquals("hello", hello.json.get("type").asString)
        assertEquals(pkg, hello.json.get("pkg").asString)
        assertTrue(hello.json.getAsJsonArray("features").any { it.asString == "network" })
        val pid = hello.json.get("pid").asInt

        val probe = adb.probe(endpoint)
        assertTrue(probe.stdout + probe.stderr, probe.ok)

        val store = NetStore()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val f = frames.poll(500, TimeUnit.MILLISECONDS) ?: continue
            store.apply(f, pid, NetStore.Changes())
            val call = store.calls.firstOrNull { it.url == "https://httpbin.org/json" }
            if (call?.respBody != null) break
        }
        val call = store.calls.first { it.url == "https://httpbin.org/json" }
        assertEquals(CallState.DONE, call.state)
        assertEquals(200, call.status)
        assertTrue(String(call.respBody!!.bytes).contains("slideshow"))

        conn.close()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        // 自己建的 forward 应该被清理掉（IDE 里可能同时连着，所以只和连接前比）
        assertEquals(forwardsBefore, forwards())
    }
}

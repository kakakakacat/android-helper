package kakacat.android.helper.client

import kakacat.android.helper.client.adb.Adb
import kakacat.android.helper.client.network.CallState
import kakacat.android.helper.client.network.NetStore
import kakacat.android.helper.client.protocol.Frame
import kakacat.android.helper.client.protocol.HelperConnection
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

    /**
     * 布局：抓取 → 截图能解码、树里有带源码位置的按钮 → 改属性 → 再抓取能看到变化。
     */
    @Test
    fun layoutSnapshotAndEdit() {
        assumeTrue(System.getenv("ANDROIDHELPER_E2E") == "1")
        val adb = Adb.locate(null)!!
        val device = adb.devices().first { it.online }
        adb.run("-s", device.serial, "shell", "am", "start", "-n", "$pkg/.MainActivity")
        Thread.sleep(1500)
        val endpoint = adb.endpoints(device.serial).first { it.pkg == pkg }

        val frames = LinkedBlockingQueue<Frame>()
        val conn = HelperConnection(adb, endpoint, object : HelperConnection.Listener {
            override fun onFrame(frame: Frame) { frames.add(frame) }
            override fun onClosed(error: Throwable?) {}
        })
        conn.start()
        val hello = frames.poll(10, TimeUnit.SECONDS) as Frame.Control
        assertTrue(hello.json.getAsJsonArray("features").any { it.asString == "layout" })

        fun request(json: com.google.gson.JsonObject): Pair<com.google.gson.JsonObject, Map<String, ByteArray>> {
            conn.sendControl(json)
            val blobs = HashMap<String, ByteArray>()
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                when (val f = frames.poll(500, TimeUnit.MILLISECONDS)) {
                    is Frame.Blob -> if (f.ref.startsWith("layout:")) blobs[f.ref] = f.bytes
                    is Frame.Control -> if (f.json.get("req")?.asInt == json.get("req").asInt) return f.json to blobs
                    else -> Unit
                }
            }
            throw AssertionError("no response for $json")
        }

        val (result, blobs) = request(com.google.gson.JsonParser.parseString("""{"type":"layout.snapshot","req":1}""").asJsonObject)
        val snap = kakacat.android.helper.client.layout.LayoutSnapshot.parse(result)
        assertTrue(snap.windows.isNotEmpty())
        val image = javax.imageio.ImageIO.read(blobs.getValue("layout:1:0").inputStream())
        assertNotNull("截图应能被 ImageIO 解码", image)

        var getJson: kakacat.android.helper.client.layout.LNode? = null
        snap.windows.forEach { w -> w.root.walk { if (it.text == "GET json") getJson = it } }
        val button = getJson!!
        assertTrue(button.sources["created"]!!.contains("MainActivity.kt:"))
        assertTrue(button.sources["click"]!!.contains("MainActivity.kt:"))
        var layoutRoot = button.parent
        while (layoutRoot != null && layoutRoot.sources["layout"] == null) layoutRoot = layoutRoot.parent
        assertEquals("activity_main", layoutRoot!!.sources["layout"])

        val (set, _) = request(com.google.gson.JsonParser.parseString(
            """{"type":"layout.set","req":2,"node":${button.id},"key":"text","value":"edited"}""").asJsonObject)
        assertEquals(true, set.get("ok").asBoolean)
        val (bad, _) = request(com.google.gson.JsonParser.parseString(
            """{"type":"layout.set","req":3,"node":${button.id},"key":"padding","value":"1,2"}""").asJsonObject)
        assertEquals(false, bad.get("ok").asBoolean)

        val (again, _) = request(com.google.gson.JsonParser.parseString("""{"type":"layout.snapshot","req":4}""").asJsonObject)
        val snap2 = kakacat.android.helper.client.layout.LayoutSnapshot.parse(again)
        val w = snap.windowOf(button)!!.index
        assertEquals("edited", snap2.findByPath(w, button.path)!!.text)

        // 恢复原样，不影响下一次测试
        request(com.google.gson.JsonParser.parseString(
            """{"type":"layout.set","req":5,"node":${button.id},"key":"text","value":"GET json"}""").asJsonObject)
        conn.close()
    }
}

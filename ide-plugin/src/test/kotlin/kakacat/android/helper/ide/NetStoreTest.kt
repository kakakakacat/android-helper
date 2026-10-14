package kakacat.android.helper.ide

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import kakacat.android.helper.ide.network.CallState
import kakacat.android.helper.ide.network.Kind
import kakacat.android.helper.ide.network.NetStore
import kakacat.android.helper.ide.network.ProtoRegistry
import kakacat.android.helper.ide.protocol.Frame
import kakacat.android.helper.ide.protocol.FrameReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class NetStoreTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun event(s: String) = Frame.Event(json(s))

    private fun blob(ref: String, bytes: ByteArray, truncatedFrom: Int = -1) = Frame.Blob(ref, truncatedFrom, bytes)

    @Test
    fun frameReaderRoundTrip() {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        fun frame(type: Int, payload: ByteArray) {
            d.writeInt(payload.size + 1); d.writeByte(type); d.write(payload)
        }
        frame(3, """{"type":"hello","pid":42}""".toByteArray())
        frame(9, byteArrayOf(1, 2, 3)) // 未知类型要跳过
        val ref = "h1:resp".toByteArray()
        val body = "hello".toByteArray()
        val blob = ByteArrayOutputStream().apply {
            val b = DataOutputStream(this)
            b.writeShort(ref.size); b.write(ref); b.writeInt(1000); b.write(body)
        }.toByteArray()
        frame(2, blob)

        val r = FrameReader(ByteArrayInputStream(out.toByteArray()))
        val hello = r.next() as Frame.Control
        assertEquals(42, hello.json.get("pid").asInt)
        val b = r.next() as Frame.Blob
        assertEquals("h1:resp", b.ref)
        assertEquals(1000, b.truncatedFrom)
        assertEquals("hello", String(b.bytes))
        assertNull(r.next())
    }

    @Test
    fun httpLifecycle() {
        val store = NetStore()
        val ch = NetStore.Changes()
        store.apply(event("""{"kind":"http","phase":"start","id":"h1","t":1000,"method":"POST","url":"https://a.com/x?y=1","host":"a.com","path":"/x","query":"y=1","reqHeaders":{"A":"1"},"reqLen":3,"reqType":"application/json"}"""), 7, ch)
        store.apply(blob("h1:req", "{}".toByteArray()), 7, ch)
        assertEquals(1, ch.added)
        val c = store.calls.single()
        assertEquals(Kind.HTTP, c.kind)
        assertEquals(CallState.PENDING, c.state)
        assertEquals("/x?y=1", c.name)

        val ch2 = NetStore.Changes()
        store.apply(event("""{"kind":"http","phase":"end","id":"h1","status":404,"protocol":"h2","respHeaders":{"content-type":"text/plain"},"durMs":12,"tls":{"version":"TLSv1.3","cipher":"X"}}"""), 7, ch2)
        store.apply(blob("h1:resp", "nope".toByteArray()), 7, ch2)
        assertEquals(0, ch2.added)
        assertTrue(c in ch2.updated)
        assertEquals(404, c.status)
        assertTrue(c.isFailure)
        assertEquals("TLSv1.3", c.tlsVersion)
        assertEquals("nope", String(c.respBody!!.bytes))
        assertEquals("{}", String(c.reqBody!!.bytes))
    }

    @Test
    fun sameIdDifferentProcessIsDifferentCall() {
        val store = NetStore()
        val ch = NetStore.Changes()
        store.apply(event("""{"kind":"http","phase":"start","id":"h1","url":"https://a"}"""), 1, ch)
        store.apply(event("""{"kind":"http","phase":"start","id":"h1","url":"https://b"}"""), 2, ch)
        assertEquals(2, store.calls.size)
    }

    @Test
    fun grpcAndWsMessages() {
        val store = NetStore()
        val ch = NetStore.Changes()
        store.apply(event("""{"kind":"grpc","phase":"start","id":"g1","method":"pkg.Svc/Do","callType":"BIDI_STREAMING","authority":"api:443","metadata":{}}"""), 1, ch)
        store.apply(event("""{"kind":"grpc","phase":"message","id":"g1","dir":"up","seq":1,"len":2,"type":"pkg.Req"}"""), 1, ch)
        store.apply(blob("g1:up1", byteArrayOf(8, 1)), 1, ch)
        store.apply(event("""{"kind":"grpc","phase":"message","id":"g1","dir":"down","seq":1,"len":2}"""), 1, ch)
        store.apply(event("""{"kind":"grpc","phase":"end","id":"g1","code":"UNAVAILABLE","description":"boom","durMs":5,"trailers":{}}"""), 1, ch)
        val g = store.calls.single()
        assertEquals(2, g.messages.size)
        assertEquals(2, g.messages[0].body!!.bytes.size)
        assertNull(g.messages[1].body)
        assertEquals("UNAVAILABLE", g.displayStatus)
        assertTrue(g.isFailure)
        assertEquals("bidi", g.displayMethod)

        store.apply(event("""{"kind":"ws","phase":"start","id":"w1","url":"wss://e.com/raw","status":101}"""), 1, ch)
        store.apply(event("""{"kind":"ws","phase":"message","id":"w1","dir":"up","opcode":"text","seq":1,"len":5}"""), 1, ch)
        store.apply(blob("w1:up1", "hello".toByteArray()), 1, ch)
        val w = store.calls[1]
        assertEquals("/raw", w.name)
        assertEquals("open", w.displayStatus)
        assertEquals("hello", String(w.messages.single().body!!.bytes))
    }

    @Test
    fun websocketHandshakeFoldsIntoWsRow() {
        for (httpFirst in listOf(true, false)) {
            val store = NetStore()
            val ch = NetStore.Changes()
            val http = listOf(
                event("""{"kind":"http","phase":"start","id":"h1","method":"GET","url":"https://e.com/raw","reqHeaders":{"Sec-WebSocket-Key":"k"}}"""),
                event("""{"kind":"http","phase":"end","id":"h1","status":101}"""),
            )
            val ws = event("""{"kind":"ws","phase":"start","id":"w1","url":"wss://e.com/raw","status":101}""")
            if (httpFirst) { http.forEach { store.apply(it, 1, ch) }; store.apply(ws, 1, ch) }
            else { store.apply(http[0], 1, ch); store.apply(ws, 1, ch); store.apply(http[1], 1, ch) }
            assertEquals("httpFirst=$httpFirst", 1, store.calls.size)
            val w = store.calls.single()
            assertEquals(Kind.WS, w.kind)
            assertEquals(listOf("Sec-WebSocket-Key" to "k"), w.reqHeaders)
        }
    }

    @Test
    fun trimsOldestBeyondCap() {
        val store = NetStore(maxCalls = 3)
        val ch = NetStore.Changes()
        for (i in 1..5) store.apply(event("""{"kind":"http","phase":"start","id":"h$i","url":"https://x/$i"}"""), 1, ch)
        assertEquals(3, store.calls.size)
        assertEquals(2, ch.removed)
        assertEquals("https://x/3", store.calls.first().url)
    }

    @Test
    fun protobufHeuristicAndDescriptors() {
        // message Req { string name = 1; int32 n = 2; }
        val bytes = byteArrayOf(10, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 16, 42)
        val h = ProtoRegistry.heuristic(bytes)!!
        assertTrue(h, h.contains("1: \"abc\""))
        assertTrue(h, h.contains("2: 42"))
        assertNull(ProtoRegistry.heuristic(byteArrayOf(0x0f, 0x7f)))

        val file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("t.proto").setPackage("pkg").setSyntax("proto3")
            .addMessageType(
                DescriptorProtos.DescriptorProto.newBuilder().setName("Req")
                    .addField(FieldDescriptorProto.newBuilder().setName("name").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                    .addField(FieldDescriptorProto.newBuilder().setName("n").setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_INT32).setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
            )
            .addService(
                DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("Svc")
                    .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder().setName("Do")
                        .setInputType(".pkg.Req").setOutputType(".pkg.Req"))
            )
            .build()
        val set = DescriptorProtos.FileDescriptorSet.newBuilder().addFile(file).build().toByteArray()
        val registry = ProtoRegistry.from(set)
        assertNotNull(registry)
        val text = registry!!.decode("pkg.Svc/Do", "up", bytes)!!
        assertTrue(text, text.contains("name: \"abc\""))
        assertTrue(text, text.contains("n: 42"))
    }
}

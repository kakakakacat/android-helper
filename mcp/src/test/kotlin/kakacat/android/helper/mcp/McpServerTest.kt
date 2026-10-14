package kakacat.android.helper.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class McpServerTest {

    private val server = McpServer(
        "t", "1", "hi",
        listOf(
            Tool("echo", "echo", schema { string("x", "value", required = true) }) { a -> ToolResult.text(a.get("x").asString) },
            Tool("boom", "fails", schema { }) { throw ToolError("nope") },
        ),
    )

    private fun call(json: String): JsonObject? = server.handle(JsonParser.parseString(json).asJsonObject)

    @Test
    fun initializeNegotiatesVersion() {
        val r = call("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")!!
        assertEquals("2025-03-26", r.getAsJsonObject("result").get("protocolVersion").asString)
        val unknown = call("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")!!
        assertEquals(McpServer.SUPPORTED_VERSIONS.first(), unknown.getAsJsonObject("result").get("protocolVersion").asString)
    }

    @Test
    fun notificationsGetNoReply() {
        assertNull(call("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun toolsListAndCall() {
        val list = call("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")!!.getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals(listOf("echo", "boom"), list.map { it.asJsonObject.get("name").asString })
        assertEquals("x", list[0].asJsonObject.getAsJsonObject("inputSchema").getAsJsonArray("required")[0].asString)

        val ok = call("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo","arguments":{"x":"hi"}}}""")!!
        assertEquals("hi", ok.getAsJsonObject("result").getAsJsonArray("content")[0].asJsonObject.get("text").asString)

        val err = call("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"boom","arguments":{}}}""")!!.getAsJsonObject("result")
        assertTrue(err.get("isError").asBoolean)
        assertEquals("nope", err.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun unknownMethodIsJsonRpcError() {
        val r = call("""{"jsonrpc":"2.0","id":6,"method":"resources/list"}""")!!
        assertEquals(-32601, r.getAsJsonObject("error").get("code").asInt)
    }

    @Test
    fun stdioIsOneMessagePerLine() {
        val out = ByteArrayOutputStream()
        val input = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"x":"a\nb"}}}""" + "\n" +
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n" + "not json\n"
        server.serve(input.reader().buffered(), PrintStream(out, true, Charsets.UTF_8))
        val lines = out.toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        assertEquals(2, lines.size) // 结果 + parse error；通知没有回复
        assertTrue(lines[0].contains("a\\nb"))
        assertEquals(-32700, JsonParser.parseString(lines[1]).asJsonObject.getAsJsonObject("error").get("code").asInt)
    }
}

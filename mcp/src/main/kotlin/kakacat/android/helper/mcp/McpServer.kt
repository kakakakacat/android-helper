package kakacat.android.helper.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.PrintStream

/** 一个 MCP 工具：名字、说明、参数 JSON Schema，以及实现。 */
class Tool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val run: (JsonObject) -> ToolResult,
)

/** 工具结果：文本为主，必要时带图片。[isError] 时 agent 会看到这是一次失败的调用。 */
class ToolResult(val content: List<Content>, val isError: Boolean = false) {
    sealed interface Content
    class Text(val text: String) : Content
    class Image(val base64: String, val mimeType: String) : Content

    companion object {
        fun text(s: String) = ToolResult(listOf(Text(s)))
        fun error(s: String) = ToolResult(listOf(Text(s)), isError = true)
    }
}

/** 工具实现里抛这个，消息原样给 agent 看（比如"有多个 App，请指定 app 参数"）。 */
class ToolError(message: String) : Exception(message)

/**
 * MCP over stdio：每行一条 JSON-RPC 2.0 消息。stdout 只能写协议消息，日志一律走 stderr。
 *
 * 只实现了工具相关的部分（initialize / tools/list / tools/call / ping），
 * 没有用 SDK —— 协议面很小，手写不引入依赖，也不跟 SDK 的版本节奏绑定。
 */
class McpServer(
    private val name: String,
    private val version: String,
    private val instructions: String,
    private val tools: List<Tool>,
) {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    fun serve(input: BufferedReader, output: PrintStream) {
        while (true) {
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            val msg = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: Exception) {
                send(output, errorResponse(JsonNull.INSTANCE, -32700, "Parse error"))
                continue
            }
            val response = handle(msg) ?: continue
            send(output, response)
        }
    }

    /** 返回 null 表示是通知，不需要回复。 */
    fun handle(msg: JsonObject): JsonObject? {
        val id: JsonElement? = msg.get("id")
        val method = msg.get("method")?.asString ?: return null
        if (id == null || id.isJsonNull) return null // notification（initialized、cancelled…）
        val params = msg.getAsJsonObject("params") ?: JsonObject()
        return try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject())
                "tools/list" -> result(id, listTools())
                "tools/call" -> result(id, callTool(params))
                else -> errorResponse(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            errorResponse(id, -32603, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params.get("protocolVersion")?.asString
        val version = if (requested in SUPPORTED_VERSIONS) requested!! else SUPPORTED_VERSIONS.first()
        return JsonObject().apply {
            addProperty("protocolVersion", version)
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject().apply { addProperty("listChanged", false) })
            })
            add("serverInfo", JsonObject().apply {
                addProperty("name", name)
                addProperty("version", this@McpServer.version)
            })
            addProperty("instructions", instructions)
        }
    }

    private fun listTools(): JsonObject = JsonObject().apply {
        add("tools", JsonArray().apply {
            for (t in tools) add(JsonObject().apply {
                addProperty("name", t.name)
                addProperty("description", t.description)
                add("inputSchema", t.schema)
            })
        })
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = params.get("name")?.asString ?: throw IllegalArgumentException("missing tool name")
        val tool = tools.firstOrNull { it.name == name } ?: return toJson(ToolResult.error("Unknown tool: $name"))
        val args = params.getAsJsonObject("arguments") ?: JsonObject()
        val result = try {
            tool.run(args)
        } catch (e: ToolError) {
            ToolResult.error(e.message ?: "error")
        } catch (e: Exception) {
            System.err.println("[android-helper-mcp] ${tool.name} failed: $e")
            ToolResult.error("${e.javaClass.simpleName}: ${e.message}")
        }
        return toJson(result)
    }

    private fun toJson(r: ToolResult): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            for (c in r.content) add(JsonObject().apply {
                when (c) {
                    is ToolResult.Text -> {
                        addProperty("type", "text")
                        addProperty("text", c.text)
                    }
                    is ToolResult.Image -> {
                        addProperty("type", "image")
                        addProperty("data", c.base64)
                        addProperty("mimeType", c.mimeType)
                    }
                }
            })
        })
        if (r.isError) addProperty("isError", true)
    }

    private fun result(id: JsonElement, result: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("result", result)
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    private fun send(out: PrintStream, msg: JsonObject) {
        // Gson 的紧凑输出不含换行（字符串里的换行会被转义），满足 stdio 传输"一行一条"的要求
        synchronized(out) {
            out.print(gson.toJson(msg))
            out.print('\n')
            out.flush()
        }
    }

    companion object {
        /** 第一个是默认值：客户端请求的版本不在列表里时用它。 */
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
    }
}

/** 构造参数 schema 的小工具，让工具定义读起来像文档。 */
class SchemaBuilder {
    private val props = JsonObject()
    private val required = JsonArray()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        prop(name, "string", description, required) { o -> enum?.let { e -> o.add("enum", JsonArray().apply { e.forEach(::add) }) } }

    fun integer(name: String, description: String, required: Boolean = false) = prop(name, "integer", description, required)
    fun boolean(name: String, description: String) = prop(name, "boolean", description, false)

    private fun prop(name: String, type: String, description: String, req: Boolean, extra: (JsonObject) -> Unit = {}) {
        props.add(name, JsonObject().apply {
            addProperty("type", type)
            addProperty("description", description)
            extra(this)
        })
        if (req) required.add(name)
    }

    fun build(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", props)
        if (required.size() > 0) add("required", required)
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

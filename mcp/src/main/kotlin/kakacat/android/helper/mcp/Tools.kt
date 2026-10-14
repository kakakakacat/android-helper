package kakacat.android.helper.mcp

import com.google.gson.JsonObject
import kakacat.android.helper.client.layout.LNode
import kakacat.android.helper.client.layout.LayoutSnapshot
import kakacat.android.helper.client.network.BodyFormat
import kakacat.android.helper.client.network.BodyView
import kakacat.android.helper.client.network.Kind
import kakacat.android.helper.client.network.NetCall
import kakacat.android.helper.client.network.ProtoRegistry
import java.util.Base64

private fun JsonObject.s(name: String): String? = get(name)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
private fun JsonObject.i(name: String): Int? = get(name)?.takeIf { !it.isJsonNull }?.asInt
private fun JsonObject.b(name: String): Boolean = get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

/** 所有工具。描述是写给 agent 看的：说清楚什么时候用、返回什么、和别的工具怎么配合。 */
class Tools(private val sessions: Sessions) {

    private val target: SchemaBuilder.() -> Unit = {
        string("app", "Package name of the app. Optional when only one app with the SDK is running (or reuse the last one).")
        string("device", "adb serial (or part of the model name). Optional when only one device is connected.")
    }

    fun all(): List<Tool> = listOf(
        Tool(
            "list_devices",
            "List Android devices visible to adb.",
            schema { },
        ) { ToolResult.text(listDevices()) },

        Tool(
            "list_apps",
            "List running debug apps on a device that include the Android Helper SDK (these are the apps the other tools can inspect).",
            schema { string("device", "adb serial; optional when only one device is connected.") },
        ) { a -> ToolResult.text(listApps(a.s("device"))) },

        Tool(
            "get_view_tree",
            "Capture the current UI of the app and return its view hierarchy as compact text, one view per line: " +
                "[id] Class#resourceId \"text\" (x,y widthxheight) flags source-locations. Includes Jetpack Compose semantics nodes. " +
                "Coordinates are screen pixels. Source locations (click=File.kt:66, created=..., layout=activity_main) show where in the " +
                "app's code a view was created / got its listener / was inflated from, so you can open that file directly. " +
                "Node ids are valid until the next get_view_tree or find_views call. Prefer this over screenshots: it is much cheaper.",
            schema {
                target()
                integer("root_id", "Only render the subtree under this node id (from a previous call).")
                integer("max_depth", "Maximum depth to render (default 25).")
                integer("max_nodes", "Maximum number of lines (default 300).")
                boolean("include_hidden", "Also list GONE / INVISIBLE views (default false).")
                boolean("include_system", "Start from the window root (DecorView, action bar...) instead of the app content (default false).")
            },
        ) { a -> ToolResult.text(viewTree(a)) },

        Tool(
            "find_views",
            "Capture the current UI and return views whose text, resource id, class name or content description contains the query " +
                "(case-insensitive), with their parent path. Use it to locate a view without reading the whole tree.",
            schema {
                string("query", "Text to search for, e.g. \"Login\", \"btn_submit\", \"RecyclerView\".", required = true)
                target()
                integer("limit", "Maximum matches (default 20).")
            },
        ) { a -> ToolResult.text(findViews(a)) },

        Tool(
            "get_view",
            "All properties of one view from the last capture: bounds in px and dp, padding, margin, layout params, colors, text size, " +
                "visibility, full source locations, parent path, children, and which keys set_view_property can change.",
            schema {
                integer("id", "Node id from get_view_tree / find_views.", required = true)
                target()
            },
        ) { a -> ToolResult.text(getView(a)) },

        Tool(
            "set_view_property",
            "Change a property of a view in the running app (runtime only; source code is not modified) — useful to try a fix before editing code. " +
                "Keys: visibility (V/I/G), alpha, enabled (true/false), text, hint, textSize (px), textColor / bg (#AARRGGBB or #RRGGBB), " +
                "padding / margin (left,top,right,bottom px), lp (width,height: px | match_parent | wrap_content), translation (x,y), scale (x,y), elevation. " +
                "Re-captures afterwards and returns the updated view. Compose nodes cannot be edited.",
            schema {
                integer("id", "Node id from the last capture.", required = true)
                string("key", "Property to change.", required = true)
                string("value", "New value, e.g. \"G\", \"0.5\", \"Hello\", \"16,8,16,8\", \"match_parent,wrap_content\".", required = true)
                target()
            },
        ) { a -> ToolResult.text(setProperty(a)) },

        Tool(
            "tap",
            "Tap a view (by node id, taps its center) or a screen coordinate, via adb input. Then call get_view_tree to see the result.",
            schema {
                integer("id", "Node id from the last capture.")
                integer("x", "Screen x in px (when no id).")
                integer("y", "Screen y in px (when no id).")
                target()
            },
        ) { a -> ToolResult.text(tap(a)) },

        Tool(
            "input_text",
            "Type text into the focused field via adb input (ASCII only; tap the field first).",
            schema {
                string("text", "Text to type.", required = true)
                string("device", "adb serial; optional when only one device is connected.")
            },
        ) { a -> ToolResult.text(inputText(a)) },

        Tool(
            "press_key",
            "Press a key via adb: back, home, enter, tab, delete, recents, menu, up, down, left, right, or a numeric Android keycode.",
            schema {
                string("key", "Key name or keycode.", required = true)
                string("device", "adb serial; optional when only one device is connected.")
            },
        ) { a -> ToolResult.text(pressKey(a)) },

        Tool(
            "screenshot",
            "Screenshot of the app's windows as JPEG. Unlike get_view_tree it has no node ids, exact bounds or source locations, and a full-size " +
                "image costs several times more tokens — use it only when the visual appearance itself matters (colors, images, overlap). " +
                "Pixel coordinates in the image are scaled by the reported factor.",
            schema {
                target()
                integer("max_width", "Image width in px (default 480).")
            },
        ) { a -> screenshot(a) },

        Tool(
            "list_requests",
            "Network requests (OkHttp HTTP, gRPC, WebSocket) the app made since this MCP server connected to it, one per line: " +
                "#index time kind method status url duration size. Capturing starts on the first tool call for the app, so call this " +
                "(or any tool) once, reproduce the action in the app, then call it again.",
            schema {
                target()
                string("filter", "Only requests whose URL / gRPC method contains this text.")
                boolean("failed_only", "Only HTTP >= 400, gRPC errors and failed calls.")
                integer("limit", "Show the last N matches (default 30).")
            },
        ) { a -> ToolResult.text(listRequests(a)) },

        Tool(
            "get_request",
            "Details of one captured request: headers / gRPC metadata, status, timing, TLS, and request/response bodies " +
                "(JSON pretty-printed, protobuf decoded) or streamed messages. Sensitive headers and tokens are redacted on the device.",
            schema {
                integer("index", "Request index from list_requests.", required = true)
                target()
                integer("max_body_chars", "Truncate each body to this many characters (default 4000).")
            },
        ) { a -> ToolResult.text(getRequest(a)) },

        Tool(
            "clear_requests",
            "Forget the captured requests (e.g. before reproducing a specific action).",
            schema { target() },
        ) { a -> sessions.session(a.s("app"), a.s("device")).withStore { it.clear() }; ToolResult.text("cleared") },
    )

    // ---- devices / apps ----

    private fun listDevices(): String {
        val list = sessions.adb.devices()
        if (list.isEmpty()) return "No devices. Connect a device over USB with USB debugging enabled."
        return list.joinToString("\n") { "${it.serial}  ${it.model ?: ""}  ${it.state}" }
    }

    private fun listApps(device: String?): String {
        val d = sessions.device(device)
        val list = sessions.adb.endpoints(d.serial)
        if (list.isEmpty()) return "No running debug app with the Android Helper SDK on ${d.serial}."
        return list.joinToString("\n") { it.pkg + if (it.legacy) "  (old netcapture SDK: network tools only)" else "" }
    }

    // ---- layout ----

    private fun capture(a: JsonObject, images: Boolean = false, maxWidth: Int = 1080): Pair<AppSession, LayoutSnapshot> {
        val s = sessions.session(a.s("app"), a.s("device"))
        if (!s.supports("layout")) throw ToolError("${s.endpoint.pkg} uses an SDK without layout support (old netcapture SDK, or androidHelper { layout = false }).")
        val req = JsonObject().apply {
            addProperty("type", "layout.snapshot")
            addProperty("images", images)
            addProperty("maxWidth", maxWidth)
            addProperty("quality", 60)
        }
        val resp = s.request(req)
        if (resp.json.get("ok")?.asBoolean == false) throw ToolError("capture failed: ${resp.json.get("error")?.asString}")
        val snap = LayoutSnapshot.parse(resp.json)
        lastBlobs = resp.blobs
        s.snapshot = snap
        return s to snap
    }

    @Volatile private var lastBlobs: Map<String, ByteArray> = emptyMap()

    private fun header(s: AppSession, snap: LayoutSnapshot) =
        "app ${s.endpoint.pkg} (pid ${s.hello?.get("pid")?.asString ?: "?"}) · screen ${snap.screenWidth}x${snap.screenHeight} @${snap.density}x · " +
            "${snap.windows.size} window(s), ${snap.nodeCount} nodes · node ids valid until the next capture\n"

    private fun viewTree(a: JsonObject): String {
        val (s, snap) = capture(a)
        val out = StringBuilder(header(s, snap))
        val budget = intArrayOf(a.i("max_nodes") ?: 300)
        val rootId = a.i("root_id")
        val from = rootId?.let { snap.findById(it) ?: throw ToolError("no node $it in the new capture (the UI may have changed); call get_view_tree without root_id") }
        val windows = if (from != null) listOfNotNull(snap.windowOf(from)) else snap.windows
        for (w in windows) {
            Format.window(out, snap, w, from, a.i("max_depth") ?: 25, a.b("include_hidden"), a.b("include_system"), budget)
        }
        if (budget[0] <= 0) out.append("… output truncated at max_nodes; use root_id to zoom into a subtree\n")
        return out.toString()
    }

    private fun findViews(a: JsonObject): String {
        val q = a.s("query")?.lowercase() ?: throw ToolError("query is required")
        val (s, snap) = capture(a)
        val limit = a.i("limit") ?: 20
        val hits = ArrayList<LNode>()
        snap.windows.forEach { w ->
            w.root.walk { n ->
                if (listOfNotNull(n.text, n.rid, n.cls, n.json.get("desc")?.asString).any { it.lowercase().contains(q) }) hits.add(n)
            }
        }
        if (hits.isEmpty()) return header(s, snap) + "no view matches \"$q\""
        return header(s, snap) + hits.take(limit).joinToString("\n") { n ->
            val path = generateSequence(n.parent) { it.parent }.toList().asReversed().drop(1)
                .joinToString(" > ") { it.simpleName + (it.rid?.let { r -> "#" + r.substringAfter(":id/") } ?: "") }
            Format.nodeLine(n) + if (path.isNotEmpty()) "\n    in: $path" else ""
        } + if (hits.size > limit) "\n… ${hits.size - limit} more" else ""
    }

    private fun lastSnapshot(a: JsonObject): Pair<AppSession, LayoutSnapshot> {
        val s = sessions.session(a.s("app"), a.s("device"))
        val snap = s.snapshot ?: throw ToolError("no capture yet; call get_view_tree or find_views first")
        return s to snap
    }

    private fun node(snap: LayoutSnapshot, id: Int?): LNode {
        id ?: throw ToolError("id is required")
        return snap.findById(id) ?: throw ToolError("no node $id in the last capture; call get_view_tree again")
    }

    private fun getView(a: JsonObject): String {
        val (_, snap) = lastSnapshot(a)
        return Format.nodeDetail(snap, node(snap, a.i("id")))
    }

    private fun setProperty(a: JsonObject): String {
        val (s, snap) = lastSnapshot(a)
        val n = node(snap, a.i("id"))
        if (n.cls.startsWith("Compose.")) throw ToolError("Compose nodes cannot be edited")
        val key = a.s("key") ?: throw ToolError("key is required")
        val value = a.get("value")?.asString ?: throw ToolError("value is required")
        val window = snap.windowOf(n)?.index
        val path = n.path
        val resp = s.request(JsonObject().apply {
            addProperty("type", "layout.set")
            addProperty("node", n.id)
            addProperty("key", key)
            addProperty("value", value)
        })
        if (resp.json.get("ok")?.asBoolean == false) throw ToolError("set $key failed: ${resp.json.get("error")?.asString}")
        val (_, after) = capture(a)
        val updated = window?.let { after.findByPath(it, path) }
        return if (updated != null) "ok — re-captured; the view is now:\n" + Format.nodeLine(updated) + "\n(ids refreshed; this view is now [${updated.id}])"
        else "ok — re-captured, but the view moved in the hierarchy; call get_view_tree"
    }

    private fun tap(a: JsonObject): String {
        val (x, y, what) = if (a.i("id") != null) {
            val (_, snap) = lastSnapshot(a)
            val n = node(snap, a.i("id"))
            if (!n.isShown) throw ToolError("view [${n.id}] is not visible")
            Triple(n.bounds.centerX.toInt(), n.bounds.centerY.toInt(), Format.nodeLine(n))
        } else {
            val x = a.i("x") ?: throw ToolError("pass id, or x and y")
            val y = a.i("y") ?: throw ToolError("pass id, or x and y")
            Triple(x, y, "($x,$y)")
        }
        val d = sessions.device(a.s("device"))
        val r = sessions.adb.run("-s", d.serial, "shell", "input", "tap", x.toString(), y.toString())
        if (!r.ok) throw ToolError("adb input tap failed: ${r.stderr}")
        return "tapped ($x,$y): $what"
    }

    private fun inputText(a: JsonObject): String {
        val text = a.s("text") ?: throw ToolError("text is required")
        if (text.any { it.code > 126 }) throw ToolError("adb input only supports ASCII text")
        // adb shell input text：空格写成 %s，shell 特殊字符要转义
        val escaped = text.replace("%", "\\%").replace(" ", "%s")
            .replace(Regex("""([\\'"`$&|;<>()\[\]{}*?!~#])"""), "\\\\$1")
        val d = sessions.device(a.s("device"))
        val r = sessions.adb.run("-s", d.serial, "shell", "input", "text", escaped)
        if (!r.ok) throw ToolError("adb input text failed: ${r.stderr}")
        return "typed ${text.length} characters"
    }

    private fun pressKey(a: JsonObject): String {
        val key = a.s("key") ?: throw ToolError("key is required")
        val code = key.toIntOrNull()?.toString() ?: when (key.lowercase()) {
            "back" -> "KEYCODE_BACK"
            "home" -> "KEYCODE_HOME"
            "enter" -> "KEYCODE_ENTER"
            "tab" -> "KEYCODE_TAB"
            "delete", "backspace" -> "KEYCODE_DEL"
            "recents", "app_switch" -> "KEYCODE_APP_SWITCH"
            "menu" -> "KEYCODE_MENU"
            "up" -> "KEYCODE_DPAD_UP"
            "down" -> "KEYCODE_DPAD_DOWN"
            "left" -> "KEYCODE_DPAD_LEFT"
            "right" -> "KEYCODE_DPAD_RIGHT"
            else -> throw ToolError("unknown key \"$key\"")
        }
        val d = sessions.device(a.s("device"))
        val r = sessions.adb.run("-s", d.serial, "shell", "input", "keyevent", code)
        if (!r.ok) throw ToolError("adb input keyevent failed: ${r.stderr}")
        return "pressed $key"
    }

    private fun screenshot(a: JsonObject): ToolResult {
        val maxWidth = (a.i("max_width") ?: 480).coerceIn(120, 1440)
        val (s, snap) = capture(a, images = true, maxWidth = maxWidth)
        val content = ArrayList<ToolResult.Content>()
        val text = StringBuilder(header(s, snap))
        for (w in snap.windows) {
            val bytes = lastBlobs.entries.firstOrNull { it.key.endsWith(":${w.index}") }?.value ?: continue
            val img = runCatching { javax.imageio.ImageIO.read(bytes.inputStream()) }.getOrNull()
            val scale = if (img != null && w.bounds.width > 0) img.width.toDouble() / w.bounds.width else 1.0
            text.append("window ${w.index} ${w.kind ?: ""} at (${w.bounds.x},${w.bounds.y}) ${w.bounds.width}x${w.bounds.height}; " +
                "image px = screen px × ${"%.3f".format(scale)}\n")
            content.add(ToolResult.Image(Base64.getEncoder().encodeToString(bytes), "image/jpeg"))
        }
        return ToolResult(listOf(ToolResult.Text(text.toString())) + content)
    }

    // ---- network ----

    private fun listRequests(a: JsonObject): String {
        val s = sessions.session(a.s("app"), a.s("device"))
        val filter = a.s("filter")?.lowercase()
        val failedOnly = a.b("failed_only")
        val limit = a.i("limit") ?: 30
        return s.withStore { store ->
            val calls = store.calls.filter { c ->
                (filter == null || listOfNotNull(c.url, c.grpcMethod).any { it.lowercase().contains(filter) }) &&
                    (!failedOnly || c.isFailure)
            }
            buildString {
                append("app ${s.endpoint.pkg} · capturing since ${Format.time(s.connectedAt)} · ${store.calls.size} captured")
                if (store.dropped > 0) append(" · ${store.dropped} dropped on device (queue full)")
                append('\n')
                if (calls.isEmpty()) append("no matching requests yet — trigger the action in the app, then call list_requests again")
                calls.takeLast(limit).forEach { append(Format.callLine(it)).append('\n') }
                if (calls.size > limit) append("(showing last $limit of ${calls.size})\n")
            }
        }
    }

    private fun getRequest(a: JsonObject): String {
        val s = sessions.session(a.s("app"), a.s("device"))
        val index = a.i("index") ?: throw ToolError("index is required")
        val maxChars = a.i("max_body_chars") ?: 4000
        return s.withStore { store ->
            val c = store.calls.firstOrNull { it.index == index } ?: throw ToolError("no request #$index (it may have been cleared)")
            val registry = store.descriptors?.let { ProtoRegistry.from(it) }
            requestDetail(c, registry, maxChars)
        }
    }

    private fun requestDetail(c: NetCall, registry: ProtoRegistry?, maxChars: Int): String = buildString {
        appendLine(Format.callLine(c))
        fun kv(k: String, v: Any?) { if (v != null && v.toString().isNotEmpty()) appendLine("$k: $v") }
        kv("url", c.url)
        kv("grpc method", c.grpcMethod)
        kv("call type", c.callType)
        kv("protocol", c.protocol)
        kv("status", listOfNotNull(c.status?.toString(), c.statusText?.takeIf { it.isNotEmpty() }, c.grpcCode, c.grpcDescription).joinToString(" "))
        kv("tls", listOfNotNull(c.tlsVersion, c.tlsCipher).joinToString(" "))
        kv("duration", c.durationMs?.let { "$it ms" })
        kv("error", c.error)
        kv("thread", c.thread)
        fun headers(title: String, h: List<Pair<String, String>>) {
            if (h.isEmpty()) return
            appendLine("$title:")
            h.forEach { (k, v) -> appendLine("  $k: $v") }
        }
        headers("request headers", c.reqHeaders)
        headers("response headers", c.respHeaders)
        headers("request metadata", c.metadata)
        headers("response metadata", c.respMetadata)
        headers("trailers", c.trailers)
        if (c.kind == Kind.HTTP) {
            appendLine("request body:")
            appendLine(if (c.reqBody == null && c.reqLen <= 0) "  (none)" else body(c.reqBody?.bytes, c.reqType, false, null, maxChars, c.reqBody?.truncatedFrom))
            appendLine("response body:")
            appendLine(body(c.respBody?.bytes, c.respType, false, null, maxChars, c.respBody?.truncatedFrom))
        } else {
            appendLine("messages (${c.messages.size}):")
            for (m in c.messages.take(50)) {
                appendLine("  ${if (m.dir == "up") "↑" else "↓"} #${m.seq} ${Format.time(m.time)} ${m.len}B ${m.type?.substringAfterLast('.') ?: ""}")
                val decode: ((ByteArray) -> String?)? = if (c.kind == Kind.GRPC && registry != null && c.grpcMethod != null) {
                    { b -> registry.decode(c.grpcMethod!!, m.dir, b) }
                } else null
                val text = body(m.body?.bytes, if (c.kind == Kind.WS && m.type == "text") "text/plain" else null,
                    c.kind == Kind.GRPC, decode, maxChars / 2, m.body?.truncatedFrom)
                appendLine(text.prependIndent("    "))
            }
            if (c.messages.size > 50) appendLine("  … ${c.messages.size - 50} more")
        }
    }

    private fun body(bytes: ByteArray?, contentType: String?, isProto: Boolean, decode: ((ByteArray) -> String?)?, maxChars: Int, truncatedFrom: Int?): String {
        if (bytes == null) return "  (not captured)"
        if (bytes.isEmpty()) return "  (empty)"
        val text = when (BodyFormat.detect(bytes, contentType, isProto)) {
            BodyView.JSON -> BodyFormat.json(bytes) ?: BodyFormat.text(bytes)
            BodyView.TEXT -> BodyFormat.text(bytes)
            BodyView.PROTOBUF -> decode?.invoke(bytes) ?: ProtoRegistry.heuristic(bytes)?.let { "# protobuf by field number\n$it" } ?: BodyFormat.hex(bytes, 512)
            BodyView.IMAGE -> "(image, ${bytes.size} bytes)"
            else -> BodyFormat.hex(bytes, 512)
        }
        val clipped = if (text.length > maxChars) text.take(maxChars) + "\n… (${text.length - maxChars} more chars; raise max_body_chars)" else text
        val note = if (truncatedFrom != null && truncatedFrom >= 0) "\n  (device captured ${bytes.size} of $truncatedFrom bytes)" else ""
        return clipped.prependIndent("  ") + note
    }
}

package kakacat.android.helper.mcp

import java.io.PrintStream

const val VERSION = "0.1.0"

private const val INSTRUCTIONS = """Inspect and modify the UI and network traffic of a running Android debug app that includes the Android Helper SDK.
Prefer text over images: get_view_tree / find_views return the view hierarchy as compact text with screen coordinates and the source
location (File.kt:line) where each view was created, got its click listener, or was inflated from — open those files instead of guessing.
Typical loop: get_view_tree → (get_view / set_view_property to try a change) → edit the code → rebuild → get_view_tree to confirm.
Use tap / input_text / press_key to navigate; list_requests / get_request to inspect HTTP, gRPC and WebSocket traffic.
Only use screenshot when the visual appearance itself matters."""

/**
 * ```
 * java -jar android-helper-mcp.jar [--adb /path/to/adb]
 * ```
 * stdin / stdout 是 MCP 协议通道，日志写 stderr。
 */
fun main(args: Array<String>) {
    if ("--version" in args) {
        println("android-helper-mcp $VERSION")
        return
    }
    val adb = args.indexOf("--adb").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    // 协议只能走 stdout：先把 System.out 换成 stderr，防止任何库的 println 混进协议流
    val protocolOut = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), false, Charsets.UTF_8)
    System.setOut(System.err)

    val sessions = Sessions(adb)
    Runtime.getRuntime().addShutdownHook(Thread { sessions.closeAll() })
    val server = McpServer("android-helper", VERSION, INSTRUCTIONS.trimIndent(), Tools(sessions).all())
    server.serve(System.`in`.bufferedReader(Charsets.UTF_8), protocolOut)
    sessions.closeAll()
}

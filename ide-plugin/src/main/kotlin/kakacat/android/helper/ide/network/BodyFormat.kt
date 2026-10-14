package kakacat.android.helper.ide.network

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/** body 的显示方式。AUTO 按内容类型和字节特征自动选。 */
enum class BodyView(val label: String) {
    AUTO("Auto"), JSON("JSON"), TEXT("Text"), PROTOBUF("Protobuf"), HEX("Hex"), IMAGE("Image")
}

object BodyFormat {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    /**
     * 选出 AUTO 实际对应的视图。
     * [contentType] 是 HTTP 的 Content-Type；gRPC 消息传 [isProto] = true。
     */
    fun detect(bytes: ByteArray, contentType: String?, isProto: Boolean): BodyView {
        val ct = contentType?.lowercase().orEmpty()
        return when {
            bytes.isEmpty() -> BodyView.TEXT
            ct.startsWith("image/") && !ct.contains("svg") -> BodyView.IMAGE
            isProto || ct.contains("protobuf") || ct.contains("grpc") -> BodyView.PROTOBUF
            ct.contains("json") || looksLikeJson(bytes) -> BodyView.JSON
            looksTextual(bytes) -> BodyView.TEXT
            else -> BodyView.HEX
        }
    }

    fun json(bytes: ByteArray): String? = runCatching {
        gson.toJson(JsonParser.parseString(String(bytes, Charsets.UTF_8)))
    }.getOrNull()

    fun text(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    /** 经典的 16 字节一行 hexdump。超大 body 只 dump 前 64KB，避免把文本控件撑爆。 */
    fun hex(bytes: ByteArray, limit: Int = 64 * 1024): String {
        val n = minOf(bytes.size, limit)
        val sb = StringBuilder(n * 4 + 64)
        var i = 0
        while (i < n) {
            sb.append("%08x  ".format(i))
            for (k in 0 until 16) {
                if (i + k < n) sb.append("%02x ".format(bytes[i + k])) else sb.append("   ")
                if (k == 7) sb.append(' ')
            }
            sb.append(" |")
            for (k in 0 until minOf(16, n - i)) {
                val c = bytes[i + k].toInt() and 0xff
                sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            sb.append("|\n")
            i += 16
        }
        if (bytes.size > n) sb.append("… ${bytes.size - n} more bytes\n")
        return sb.toString()
    }

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        val first = bytes.firstOrNull { it.toInt().toChar() !in " \t\r\n" }?.toInt()?.toChar() ?: return false
        return (first == '{' || first == '[') && json(bytes) != null
    }

    /** 和设备端 Redaction.looksTextual 同一套判断。 */
    fun looksTextual(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 512)
        var suspicious = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt()
            if (b == 0) return false
            if (b in 1..8 || b in 14..31) suspicious++
        }
        return suspicious * 20 < n
    }

    fun size(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}

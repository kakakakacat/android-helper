package kakacat.android.helper.ide.protocol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * 设备端的帧格式（见 sdk/runtime-core 的 Frame.kt）：
 *
 * ```
 * u32be len | u8 type | payload[len - 1]
 * ```
 */
sealed interface Frame {
    /** type 1：采集事件。 */
    class Event(val json: JsonObject) : Frame

    /** type 2：body / 消息体的裸字节。[truncatedFrom] ≥ 0 表示被截断过，值是原始长度。 */
    class Blob(val ref: String, val truncatedFrom: Int, val bytes: ByteArray) : Frame

    /** type 3：控制帧（hello 等）。 */
    class Control(val json: JsonObject) : Frame

    companion object {
        const val EVENT = 1
        const val BLOB = 2
        const val CONTROL = 3

        /** 编码一个发往设备的控制帧。 */
        fun encodeControl(json: JsonObject): ByteArray {
            val payload = json.toString().toByteArray(Charsets.UTF_8)
            val len = payload.size + 1
            val out = ByteArray(4 + len)
            out[0] = (len ushr 24).toByte()
            out[1] = (len ushr 16).toByte()
            out[2] = (len ushr 8).toByte()
            out[3] = len.toByte()
            out[4] = CONTROL.toByte()
            payload.copyInto(out, 5)
            return out
        }
    }
}

class FrameReader(input: InputStream) {

    private val input = DataInputStream(input.buffered(64 * 1024))

    /** 读下一帧；连接正常关闭时返回 null。未知类型的帧直接跳过。 */
    fun next(): Frame? {
        while (true) {
            val len = try {
                input.readInt()
            } catch (e: EOFException) {
                return null
            }
            // 设备侧 body 上限是 256KB，descriptor set 也就几 MB；放宽到 64MB 只为挡住错位的流。
            if (len <= 0 || len > MAX_FRAME) throw IllegalStateException("bad frame length $len")
            val type = input.readUnsignedByte()
            val payload = ByteArray(len - 1)
            input.readFully(payload)
            when (type) {
                Frame.EVENT -> return Frame.Event(parse(payload))
                Frame.CONTROL -> return Frame.Control(parse(payload))
                Frame.BLOB -> return decodeBlob(payload)
                else -> continue
            }
        }
    }

    private fun parse(payload: ByteArray): JsonObject =
        JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonObject

    private fun decodeBlob(p: ByteArray): Frame.Blob {
        val refLen = ((p[0].toInt() and 0xff) shl 8) or (p[1].toInt() and 0xff)
        val ref = String(p, 2, refLen, Charsets.UTF_8)
        var i = 2 + refLen
        val truncatedFrom = ((p[i].toInt() and 0xff) shl 24) or
            ((p[i + 1].toInt() and 0xff) shl 16) or
            ((p[i + 2].toInt() and 0xff) shl 8) or
            (p[i + 3].toInt() and 0xff)
        i += 4
        return Frame.Blob(ref, truncatedFrom, p.copyOfRange(i, p.size))
    }

    private companion object {
        const val MAX_FRAME = 64 * 1024 * 1024
    }
}

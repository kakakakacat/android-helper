package kakacat.android.helper

import org.json.JSONObject

/**
 * 线上帧格式，和 android-netcapture 完全一致（老的网页控制台照样能连）：
 *
 * ```
 * u32be len | u8 type | payload[len - 1]      // len 包含 type 这 1 个字节
 * ```
 *
 * | type | payload |
 * |------|---------|
 * | 1 EVENT   | UTF-8 JSON |
 * | 2 BLOB    | u16be refLen \| ref(UTF-8) \| i32be truncatedFrom \| bytes |
 * | 3 CONTROL | UTF-8 JSON，双向 |
 *
 * body 一律以裸字节走 BLOB，不做 Base64、不做 JSON 转义 —— 设备侧省掉一次全量
 * 编码，IDE 那边反正要自己解。
 */
object Frame {
    const val EVENT: Int = 1
    const val BLOB: Int = 2
    const val CONTROL: Int = 3

    fun encodeEvent(json: JSONObject): ByteArray = json.toString().toByteArray(Charsets.UTF_8)

    fun encodeBlob(ref: String, bytes: ByteArray, truncatedFrom: Int): ByteArray {
        val refBytes = ref.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + refBytes.size + 4 + bytes.size)
        var i = 0
        out[i++] = (refBytes.size ushr 8).toByte()
        out[i++] = refBytes.size.toByte()
        refBytes.copyInto(out, i); i += refBytes.size
        out[i++] = (truncatedFrom ushr 24).toByte()
        out[i++] = (truncatedFrom ushr 16).toByte()
        out[i++] = (truncatedFrom ushr 8).toByte()
        out[i++] = truncatedFrom.toByte()
        bytes.copyInto(out, i)
        return out
    }

    /** 完整帧（含 4 字节长度前缀和 1 字节类型）。 */
    fun wrap(type: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 1
        val out = ByteArray(4 + len)
        out[0] = (len ushr 24).toByte()
        out[1] = (len ushr 16).toByte()
        out[2] = (len ushr 8).toByte()
        out[3] = len.toByte()
        out[4] = type.toByte()
        payload.copyInto(out, 5)
        return out
    }
}

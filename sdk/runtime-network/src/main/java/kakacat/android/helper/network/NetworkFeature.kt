package kakacat.android.helper.network

import android.content.Context
import android.util.Log
import kakacat.android.helper.EventSink
import kakacat.android.helper.Frame
import kakacat.android.helper.HelloContributor
import kakacat.android.helper.HelperClient
import kakacat.android.helper.HelperFeature
import org.json.JSONObject

/**
 * 抓包能力。采集点本身由插桩挂进来（见 Hooks），这里只负责和客户端握手。
 */
internal class NetworkFeature(private val context: Context) : HelperFeature, HelloContributor {

    override val name: String = "network"

    override fun contribute(hello: JSONObject) {
        hello.put("maxTextBody", MAX_TEXT_BODY)
        hello.put("maxBinaryBody", MAX_BINARY_BODY)
        hello.put("descriptors", hasDescriptors())
    }

    /**
     * 连上后紧跟着推 descriptor set：客户端靠它把 protobuf 的字段号翻成字段名。
     * 只在有人连上时读，不连就不花这份 IO 和内存。
     */
    override fun onAttach(client: HelperClient) {
        val descriptors = readDescriptors() ?: return
        client.send(Frame.BLOB, Frame.encodeBlob(DESCRIPTORS_REF, descriptors, -1))
        Log.i(TAG, "sent descriptors (${descriptors.size} bytes)")
    }

    /** `clear`：客户端清空视图。目前设备侧没有积压可丢，只是确认收到。 */
    override fun onControl(client: HelperClient, json: JSONObject): Boolean =
        json.optString("type") == "clear"

    private fun hasDescriptors(): Boolean =
        runCatching { context.assets.list("netcapture")?.contains("descriptors.pb") == true }
            .getOrDefault(false)

    /**
     * 拿不到就返回 null —— 客户端会退回到按字段号显示的启发式视图，而不是整个功能挂掉。
     */
    private fun readDescriptors(): ByteArray? = try {
        context.assets.open(DESCRIPTORS_ASSET).use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }

    companion object {
        private const val TAG = "AndroidHelper"

        /** 文本 body 上限，超过只记长度和前缀。 */
        const val MAX_TEXT_BODY: Int = 256 * 1024

        /** 二进制 body / protobuf message 上限。 */
        const val MAX_BINARY_BODY: Int = 64 * 1024

        /** BLOB 的 ref，客户端按它认出这是 descriptor set 而不是某个请求的 body。 */
        private const val DESCRIPTORS_REF = "descriptors"

        /**
         * 使用方 protobuf 任务生成的 descriptor set，挂在 debug 的 assets 里。
         * 路径沿用 android-netcapture 的约定，已经配好的项目不用改。
         */
        private const val DESCRIPTORS_ASSET = "netcapture/descriptors.pb"
    }
}

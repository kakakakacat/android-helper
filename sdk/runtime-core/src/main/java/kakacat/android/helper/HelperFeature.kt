package kakacat.android.helper

import org.json.JSONObject

/**
 * 一个可插拔的能力模块（network、layout……）。
 *
 * 每个 feature 是一个独立的 AAR，由自己的 ContentProvider 在启动时调
 * [Helper.register] 注册进来。core 不依赖任何 feature —— 没加某个 AAR，
 * 那个能力就不存在，而不是"存在但被关掉"。
 */
interface HelperFeature {

    /** 出现在 hello 帧的 `features` 里，IDE 据此决定显示哪些标签页。 */
    val name: String

    /** 有客户端连上、hello 已经发出之后调用。用来推一次性的初始数据（比如 descriptor set）。 */
    fun onAttach(client: HelperClient) {}

    /**
     * 客户端发来的 CONTROL 帧。`type` 以 `"<feature>."` 开头的会路由到对应 feature；
     * 返回 true 表示已处理。
     */
    fun onControl(client: HelperClient, json: JSONObject): Boolean = false
}

/** 一个已连接的客户端（IDE 插件或网页）。 */
interface HelperClient {
    /** 只发给这个客户端。返回 false 表示连接已断。 */
    fun send(type: Int, payload: ByteArray): Boolean
}

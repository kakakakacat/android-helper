package kakacat.android.helper

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 公开入口。
 *
 * 正常用法下**一行都不用调** —— Gradle 插件按 build type 加依赖，AAR 的清单会自动装上
 * [HelperInitProvider]，各 feature 的 provider 再把自己注册进来。
 */
object Helper {

    /** 和 sdk/build.gradle.kts 里的 version 保持一致。 */
    const val VERSION: String = "0.1.0"

    internal val features = CopyOnWriteArrayList<HelperFeature>()

    /** 是否有客户端（IDE / 网页）连着。没人连时各采集点全部短路，开销是一次 volatile 读。 */
    val attached: Boolean get() = HelperServer.hasClient

    /** 由 HelperInitProvider 自动调用；重复调用无副作用。 */
    @JvmStatic
    fun install(context: Context) {
        HelperServer.start(context.applicationContext)
    }

    /** 由各 feature 的 provider 调用。provider 之间的初始化顺序不保证，所以这里也会顺手 install。 */
    @JvmStatic
    fun register(context: Context, feature: HelperFeature) {
        if (features.none { it.name == feature.name }) features.add(feature)
        install(context)
    }
}

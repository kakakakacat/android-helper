package kakacat.android.helper.gradle

/** 哪些类参与插桩。network 和 layout 两套插桩共用。 */
internal object InstrumentFilter {

    private const val SDK_PACKAGE = "kakacat/android/helper/"
    private val SDK_FEATURES = listOf("network", "layout")

    /**
     * 自己不能插自己（会变成 Hooks 调 Hooks 的无限递归），以及那些类数量巨大、
     * 又不可能是业务代码的包 —— 跳过它们纯赚构建时间。
     */
    private val SKIP_PREFIXES = listOf(
        "kakacat/android/netcapture/", // 老 SDK 同时存在时也别碰它
        "okhttp3/",       // 改 OkHttp 内部会让 Hooks 递归调用自己
        "okio/",
        "io/grpc/",
        "com/google/protobuf/",
        "org/objectweb/asm/",
        "kotlin/",
        "kotlinx/",
        "java/",
        "javax/",
        "android/",
        "androidx/",
        "com/google/android/",
    )

    fun accepts(className: String, excludes: List<String>): Boolean {
        val name = className.replace('.', '/')
        if (SKIP_PREFIXES.any { name.startsWith(it) }) return false
        if (isSdkClass(name)) return false
        return excludes.none { name.startsWith(it) }
    }

    /**
     * SDK 自己的类：core 在 `kakacat/android/helper/` 顶层，各 feature 在
     * `kakacat/android/helper/<feature>/`。不能用一个前缀全跳过 —— 使用方的包名
     * 也可能落在 `kakacat/android/helper/` 下面（本仓库的 sample 就是），那样会被静默跳过。
     */
    private fun isSdkClass(name: String): Boolean {
        if (!name.startsWith(SDK_PACKAGE)) return false
        val rest = name.substring(SDK_PACKAGE.length)
        return '/' !in rest || SDK_FEATURES.any { rest.startsWith("$it/") }
    }
}

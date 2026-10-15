package kakacat.android.helper.client.errors

import com.google.gson.JsonObject
import kakacat.android.helper.client.network.int
import kakacat.android.helper.client.network.str
import java.security.MessageDigest

/**
 * SDK 自身的一条错误（设备上的 `HelperErrors` 发来的 `sdk.error` 控制帧）。
 * 同一个 [where] + [exception] 在设备上已经去重，[count] 是累计次数。
 */
data class SdkError(
    val sdk: String,
    val feature: String,
    val where: String,
    val exception: String,
    val message: String,
    val stack: String,
    val count: Int,
    val first: Long,
    val last: Long,
    val api: Int?,
    val device: String?,
) {
    val key: String get() = "$where|$exception"

    companion object {
        fun parse(json: JsonObject): SdkError? {
            if (json.str("type") != "sdk.error") return null
            return SdkError(
                sdk = json.str("sdk") ?: "?",
                feature = json.str("feature") ?: "?",
                where = json.str("where") ?: return null,
                exception = json.str("exception") ?: "-",
                message = json.str("message") ?: "",
                stack = json.str("stack") ?: "",
                count = json.int("count") ?: 1,
                first = json.get("first")?.asLong ?: 0,
                last = json.get("last")?.asLong ?: 0,
                api = json.int("api"),
                device = json.str("device"),
            )
        }
    }
}

/** 一个连接上收到的 SDK 错误；同一个 key 后到的覆盖先到的（计数只增不减）。线程安全。 */
class SdkErrorStore {
    private val map = LinkedHashMap<String, SdkError>()

    fun add(e: SdkError) = synchronized(map) { map[e.key] = e }

    fun all(): List<SdkError> = synchronized(map) { map.values.sortedByDescending { it.last } }

    fun clear() = synchronized(map) { map.clear() }
}

/**
 * 上报前的脱敏。报告会进**公开**的 GitHub issue，所以只留下定位 SDK / 插件问题需要的东西：
 *
 *  * 堆栈里只保留 SDK、插件、系统框架和常见库的帧；使用方 App 自己的类一律换成 `<app>`，
 *    连续的合并成一行 —— 别人的内部类名、包名不能出现在公开页面上；
 *  * 消息里的 URL、文件路径、邮箱、IP、长串数字 / 十六进制（token、id）都替换掉；
 *  * App 的包名在任何地方出现都换成 `<app>`。
 */
object ErrorSanitizer {

    private val KEEP_PREFIXES = listOf(
        "kakacat.android.helper.",
        "android.", "androidx.", "com.android.", "dalvik.", "libcore.", "java.", "javax.", "jdk.", "sun.",
        "kotlin.", "kotlinx.", "okhttp3.", "okio.", "io.grpc.", "com.google.protobuf.", "com.google.gson.",
        "com.intellij.", "org.jetbrains.", "org.json.",
    )

    private val FRAME = Regex("""^(\s*at\s+)([\w$.]+)\.([\w$<>]+)\((.*)\)\s*$""")
    private val CAUSE = Regex("""^(\s*(?:Caused by|Suppressed):\s*)([\w$.]+)(:.*)?$""")
    private val URL = Regex("""\b[a-zA-Z][\w+.-]*://\S+""")
    private val PATH = Regex("""(?<![\w.])(?:/[\w.@+-]+){2,}/?""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val IP = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""")
    private val LONG_TOKEN = Regex("""\b(?:[0-9a-fA-F]{16,}|\d{7,})\b""")

    fun keepClass(cls: String) = KEEP_PREFIXES.any { cls.startsWith(it) }

    fun message(text: String, appPkg: String? = null): String {
        var s = text
        if (!appPkg.isNullOrBlank()) s = s.replace(appPkg, "<app>")
        s = URL.replace(s, "<url>")
        s = EMAIL.replace(s, "<email>")
        s = IP.replace(s, "<ip>")
        s = PATH.replace(s, "<path>")
        s = LONG_TOKEN.replace(s, "<id>")
        // 消息里点名的 App 类（比如 ClassCastException: com.foo.Bar cannot be cast...）
        s = Regex("""\b([a-z][\w]*(?:\.[\w$]+){2,})\b""").replace(s) { m ->
            if (keepClass(m.value + ".") || keepClass(m.value)) m.value else "<app class>"
        }
        return s.take(500)
    }

    fun stack(text: String, appPkg: String? = null): String {
        val out = ArrayList<String>()
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            val frame = FRAME.matchEntire(line)
            if (frame != null) {
                val cls = frame.groupValues[2]
                if (keepClass(cls)) {
                    out += "${frame.groupValues[1]}$cls.${frame.groupValues[3]}(${frame.groupValues[4].let(::fileOnly)})"
                } else if (out.lastOrNull()?.trim() != "at <app>") {
                    out += "${frame.groupValues[1]}<app>"
                }
                continue
            }
            val cause = CAUSE.matchEntire(line)
            if (cause != null) {
                val cls = cause.groupValues[2]
                out += cause.groupValues[1] + (if (keepClass(cls)) cls else "<app exception>") +
                    (cause.groupValues[3].takeIf { it.isNotEmpty() }?.let { ": " + message(it.removePrefix(":").trim(), appPkg) } ?: "")
                continue
            }
            if (line.trim().startsWith("...")) {
                out += line
                continue
            }
            // 第一行：异常类: 消息
            val head = line.substringBefore(':').trim()
            val rest = line.substringAfter(':', "").trim()
            out += (if (keepClass(head)) head else "<app exception>") + if (rest.isNotEmpty()) ": " + message(rest, appPkg) else ""
        }
        return out.joinToString("\n").take(6000)
    }

    /** `Foo.kt:12` 这种原样；`Unknown Source` 原样；带路径的只留文件名。 */
    private fun fileOnly(loc: String) = loc.substringAfterLast('/')

    /**
     * 同一个问题的稳定指纹：位置 + 异常类型 + 前几帧 SDK 自己的代码（不含行号，换个版本行号会变）。
     * Worker 端按它把重复上报归到同一个 issue。
     */
    fun fingerprint(where: String, exception: String, sanitizedStack: String): String {
        val own = sanitizedStack.lines()
            .filter { it.trim().startsWith("at kakacat.android.helper.") }
            .take(5)
            .map { it.trim().substringBefore('(') }
        val raw = (listOf(where, exception) + own).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

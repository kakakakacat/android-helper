package kakacat.android.helper.ide.report

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import kakacat.android.helper.client.errors.ErrorSanitizer
import kakacat.android.helper.client.errors.SdkError
import java.util.concurrent.ConcurrentHashMap

/**
 * 把错误报告发到 reports.kakacat.net（Cloudflare Worker），由它在公开仓库
 * kakakakacat/android-helper 里开 issue（标签 auto-report）或在已有的 issue 下追加。
 *
 * GitHub token 只在 Worker 里，插件里没有任何凭证。上报前一律经过 [ErrorSanitizer]：
 * 报告是公开的，使用方 App 的类名、包名、URL、路径都不能带出去，也不带设备型号。
 *
 * 默认开启，Settings → Tools → DroidLens 里可以关掉。
 */
object ErrorReporter {

    private val LOG = Logger.getInstance(ErrorReporter::class.java)
    private const val ENABLED_KEY = "kakacat.droidlens.reportErrors"
    private val ENDPOINT = System.getProperty("droidlens.reportUrl") ?: "https://reports.kakacat.net/v1/report"

    /** 同一个问题一次 IDE 会话只报一次；次数在 Worker 那边累计。 */
    private val sent = ConcurrentHashMap.newKeySet<String>()

    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(ENABLED_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(ENABLED_KEY, value, true)

    /**
     * 从自己 jar 里的 plugin.xml 读（构建时由 Gradle 插件填进去）。不用 PluginManagerCore.getPlugin：
     * 它是内部 API，PluginId.getId 在 AS 2024.3 上还是 Java 静态方法，Plugin Verifier 两边都不过。
     */
    val pluginVersion: String by lazy {
        runCatching {
            val xml = ErrorReporter::class.java.getResource("/META-INF/plugin.xml")!!.readText()
            Regex("<version>([^<]+)</version>").find(xml)!!.groupValues[1].trim()
        }.getOrDefault("?")
    }

    /** 设备上 SDK 发来的错误。[appPkg] 只用来脱敏，不会被发送。 */
    fun reportSdk(e: SdkError, appPkg: String?) {
        if (!enabled) return
        val stack = ErrorSanitizer.stack(e.stack, appPkg)
        val payload = base("sdk", e.where, e.exception, ErrorSanitizer.message(e.message, appPkg), stack).apply {
            addProperty("feature", e.feature)
            addProperty("count", e.count)
            addProperty("sdk", e.sdk)
            e.api?.let { addProperty("api", it) }
        }
        sendAsync(payload)
    }

    /**
     * 插件自身的异常，由用户在 IDE 的错误对话框里点"报告"触发（见 [DroidLensErrorSubmitter]）。
     * 同步调用，返回 issue 链接。
     */
    fun reportIde(throwableText: String, userMessage: String?): String? {
        val stack = ErrorSanitizer.stack(throwableText)
        val first = stack.lineSequence().firstOrNull().orEmpty()
        val exception = first.substringBefore(':').trim()
        val ownFrame = stack.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("at kakacat.android.helper.") }
            ?.removePrefix("at kakacat.android.helper.")?.substringBefore('(') ?: "unknown"
        val payload = base("ide", "ide.$ownFrame", exception, first.substringAfter(':', "").trim(), stack)
        userMessage?.takeIf { it.isNotBlank() }?.let { payload.addProperty("note", ErrorSanitizer.message(it)) }
        return send(payload)
    }

    private fun base(source: String, where: String, exception: String, message: String, stack: String) = JsonObject().apply {
        addProperty("source", source)
        addProperty("fingerprint", ErrorSanitizer.fingerprint(where, exception, stack))
        addProperty("where", where)
        addProperty("exception", exception)
        addProperty("message", message)
        addProperty("stack", stack)
        addProperty("plugin", pluginVersion)
        addProperty("ide", ApplicationInfo.getInstance().build.asString())
        addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
    }

    private fun sendAsync(payload: JsonObject) {
        val key = payload.get("fingerprint").asString
        if (!sent.add(key)) return
        ApplicationManager.getApplication().executeOnPooledThread { send(payload) }
    }

    private fun send(payload: JsonObject): String? = try {
        val body = Gson().toJson(payload)
        val resp = HttpRequests.post(ENDPOINT, "application/json")
            .connectTimeout(10_000)
            .readTimeout(15_000)
            .connect { req ->
                req.write(body)
                req.readString()
            }
        JsonParser.parseString(resp).asJsonObject.get("url")?.asString
    } catch (t: Throwable) {
        // 上报失败（离线、被限流）不打扰用户
        LOG.info("error report not sent: ${t.message}")
        null
    }
}

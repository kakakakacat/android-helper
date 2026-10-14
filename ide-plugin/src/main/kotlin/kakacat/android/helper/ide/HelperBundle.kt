package kakacat.android.helper.ide

import com.intellij.DynamicBundle
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/**
 * 界面文字。英文是默认（`messages/HelperBundle.properties`），中文在 `_zh` 里。
 *
 * 语言选择：IDE 装了语言包（比如中文语言包）就跟随 IDE；IDE 是默认英文时跟随系统语言 ——
 * 很多中文用户的 Android Studio 没装语言包，但系统是中文，这种情况下给中文更合适。
 * 可以用 `-Dandroidhelper.locale=en` 强制指定。
 */
object HelperBundle {

    private const val NAME = "messages.HelperBundle"

    private val locale: Locale by lazy {
        System.getProperty("androidhelper.locale")?.let { return@lazy Locale.forLanguageTag(it) }
        val ide = runCatching { DynamicBundle.getLocale() }.getOrNull()
        if (ide != null && ide.language != Locale.ENGLISH.language) ide else Locale.getDefault()
    }

    private val bundle: ResourceBundle by lazy {
        // NoFallbackControl：找不到对应语言时回到英文基础包，而不是"系统默认语言"的包
        ResourceBundle.getBundle(
            NAME, locale, HelperBundle::class.java.classLoader,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
        )
    }

    /** 参数一律传字符串：MessageFormat 会把数字格式化成 27,923 这种带千分位的样子。 */
    fun message(key: String, vararg params: Any): String {
        val pattern = runCatching { bundle.getString(key) }.getOrElse { return key }
        return if (params.isEmpty()) pattern.replace("''", "'") else MessageFormat(pattern, locale).format(params)
    }
}

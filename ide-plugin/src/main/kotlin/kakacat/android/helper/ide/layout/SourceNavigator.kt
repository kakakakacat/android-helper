package kakacat.android.helper.ide.layout

import kakacat.android.helper.client.layout.*
import kakacat.android.helper.ide.HelperBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * 把设备端记下的位置跳到源码。
 *
 * 只用文件名索引（平台公开 API），不依赖 Java / Kotlin / Android 插件的 PSI ——
 * 这样插件不会因为那些插件的内部变动而失效。代价是同名文件只能靠包路径来区分。
 */
class SourceNavigator(private val project: Project) {

    /** 栈帧格式：`com.foo.Bar$Inner.method(Bar.kt:42)`。 */
    data class Site(val className: String, val method: String, val file: String, val line: Int) {
        val packagePath: String get() = className.substringBeforeLast('.', "").replace('.', '/')

        companion object {
            private val PATTERN = Regex("""^(.+)\.([^.(]+)\(([^:)]+)(?::(\d+))?\)$""")

            fun parse(s: String): Site? {
                val m = PATTERN.matchEntire(s.trim()) ?: return null
                return Site(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].toIntOrNull() ?: 0)
            }
        }
    }

    fun openSite(site: String) {
        val s = Site.parse(site) ?: return notFound(site)
        open(listOf(s.file), s.packagePath, s.line, site)
    }

    /** 布局文件：`activity_main` → res/layout-xxx 下的 activity_main.xml。 */
    fun openLayout(name: String) {
        findAsync(listOf("$name.xml"), preferPath = "/res/layout") { file ->
            if (file == null) notFound("$name.xml") else navigate(file, 0)
        }
    }

    /** 类名（Activity / Fragment / View 类）：按外部类名找 .kt / .java。 */
    fun openClass(fqn: String) {
        val outer = fqn.substringAfterLast('.').substringBefore('$')
        val pkg = fqn.substringBeforeLast('.', "").replace('.', '/')
        open(listOf("$outer.kt", "$outer.java"), pkg, 0, fqn)
    }

    private fun open(names: List<String>, packagePath: String, line: Int, what: String) {
        findAsync(names, preferPath = if (packagePath.isEmpty()) null else "/$packagePath/") { file ->
            if (file == null) notFound(what) else navigate(file, line)
        }
    }

    /**
     * 在后台读线程上查文件名索引，结果回到 EDT。`inSmartMode`：还在建索引时自动等到建完再查
     * （刚打开项目就点跳转是很常见的情况）。
     */
    private fun findAsync(names: List<String>, preferPath: String?, onFound: (VirtualFile?) -> Unit) {
        if (DumbService.isDumb(project)) notify(HelperBundle.message("nav.indexing"), NotificationType.INFORMATION)
        ReadAction.nonBlocking<VirtualFile?> {
            // 先找项目源码，找不到再找依赖（带源码的三方库）
            for (scope in listOf(GlobalSearchScope.projectScope(project), GlobalSearchScope.allScope(project))) {
                val candidates = names.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
                if (candidates.isEmpty()) continue
                return@nonBlocking candidates.firstOrNull { preferPath != null && it.path.contains(preferPath) }
                    ?: candidates.firstOrNull()
            }
            null
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.nonModal()) { onFound(it) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun navigate(file: VirtualFile, line: Int) {
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun notFound(what: String) = notify(HelperBundle.message("nav.notFound", what), NotificationType.WARNING)

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Android Helper")
            .createNotification(text, type).notify(project)
    }
}

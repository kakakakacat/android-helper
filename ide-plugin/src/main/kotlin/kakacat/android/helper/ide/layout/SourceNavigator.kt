package kakacat.android.helper.ide.layout

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

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

    private fun findAsync(names: List<String>, preferPath: String?, onFound: (VirtualFile?) -> Unit) {
        val dumb = DumbService.getInstance(project)
        if (dumb.isDumb) {
            // 索引还没建好时文件名索引查不了；排队，建好了自动跳过去
            notify("正在建立索引，完成后会自动跳转", NotificationType.INFORMATION)
            dumb.runWhenSmart { findAsync(names, preferPath, onFound) }
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = ReadAction.compute<VirtualFile?, RuntimeException> {
                if (project.isDisposed) return@compute null
                // 先找项目源码，找不到再找依赖（带源码的三方库）
                for (scope in listOf(GlobalSearchScope.projectScope(project), GlobalSearchScope.allScope(project))) {
                    val candidates = names.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
                    if (candidates.isEmpty()) continue
                    return@compute candidates.firstOrNull { preferPath != null && it.path.contains(preferPath) }
                        ?: candidates.firstOrNull()
                }
                null
            }
            ApplicationManager.getApplication().invokeLater { onFound(file) }
        }
    }

    private fun navigate(file: VirtualFile, line: Int) {
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun notFound(what: String) = notify("在项目里找不到 $what", NotificationType.WARNING)

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Android Helper")
            .createNotification(text, type).notify(project)
    }
}

package kakacat.android.helper.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * 插桩报告。
 *
 * 存在的理由很实际：抓包没数据时，第一个要回答的问题是"插桩到底命中了没有"。
 * 没有报告的话，"没命中"和"命中了但设备端没连上"长得一模一样，会白查很久。
 *
 * 报告故意不作为构建失败条件 —— 一个模块本来就可能没有任何网络调用点。
 */
@UntrackedTask(
    // 这里必须是 Untracked + @Internal，不能声明成 @OutputDirectory：那样 Gradle 会在
    // 执行前把目录清空，而目录里正是插桩阶段刚写进去的命中记录 —— 报告会永远是空的
    // （实测踩过）。这些文件的生产者是插桩，不是这个任务。
    because = "读的是插桩阶段写下的记录，自己不产出可追踪的东西"
)
abstract class HelperReportTask : DefaultTask() {

    @get:Internal
    abstract val reportDir: DirectoryProperty

    @TaskAction
    fun report() {
        val dir = reportDir.get().asFile
        val hits = dir.listFiles { f -> f.name.endsWith("-hits.txt") }?.sortedBy { it.name }
        if (hits.isNullOrEmpty()) {
            logger.lifecycle("androidHelper: 没有插桩记录 —— 这个模块的 variant 没被选中，或者没有任何网络调用点")
            return
        }

        for (file in hits) {
            val lines = file.readLines().filter { it.isNotBlank() }.distinct().sorted()
            if (file.name.endsWith("-layout-hits.txt")) {
                reportLayout(file.name.removeSuffix("-layout-hits.txt"), lines)
                continue
            }
            val byHook = lines.groupingBy { it.substringAfterLast("→ ").trim() }.eachCount()
            logger.lifecycle("")
            logger.lifecycle("androidHelper 网络插桩 · ${file.name.removeSuffix("-hits.txt")}")
            logger.lifecycle("  命中 ${lines.size} 处：" +
                byHook.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ×${it.value}" })
            for (line in lines) logger.lifecycle("    $line")
            if (byHook.keys.none { it.startsWith("Hooks.okhttp") }) {
                logger.lifecycle("  提示：没命中 OkHttpClient.Builder.build() —— 如果客户端是在三方 AAR 里创建的，" +
                    "把 androidHelper { scope = \"all\" } 打开")
            }
        }
    }

    /** 布局插桩命中点通常很多（每个 new View 都算），按 hook 汇总，只列前 30 个类。 */
    private fun reportLayout(variant: String, lines: List<String>) {
        val totals = HashMap<String, Int>()
        for (line in lines) {
            for (part in line.substringAfter("→ ").split(", ")) {
                val name = part.substringBefore('×').trim()
                val n = part.substringAfter('×', "1").trim().toIntOrNull() ?: 1
                totals.merge(name, n, Int::plus)
            }
        }
        logger.lifecycle("")
        logger.lifecycle("androidHelper 布局插桩 · $variant")
        logger.lifecycle("  ${lines.size} 个类，" +
            totals.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ×${it.value}" })
        for (line in lines.take(30)) logger.lifecycle("    $line")
        if (lines.size > 30) logger.lifecycle("    …另外 ${lines.size - 30} 个类")
    }
}

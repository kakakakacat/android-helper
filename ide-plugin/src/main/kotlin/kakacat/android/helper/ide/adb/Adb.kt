package kakacat.android.helper.ide.adb

import com.intellij.openapi.project.Project
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/** `adb devices -l` 里的一行。 */
data class Device(val serial: String, val state: String, val model: String?) {
    val online: Boolean get() = state == "device"
    override fun toString(): String = buildString {
        append(model ?: serial)
        if (model != null) append(" (").append(serial).append(')')
        if (!online) append(" [").append(state).append(']')
    }
}

/**
 * 设备上一个正在监听的 SDK 端点。
 *
 * [legacy] 表示是老的 android-netcapture SDK（只绑了 `netcapture.<pkg>`），协议一样，
 * 但探针的 receiver 名字不同。
 */
data class Endpoint(val serial: String, val pkg: String, val socket: String, val legacy: Boolean) {
    override fun toString(): String = if (legacy) "$pkg (netcapture)" else pkg
}

/**
 * 通过外部 `adb` 进程和设备打交道。
 *
 * 刻意不用 Android 插件里的 ddmlib / AdbService：那是 AS 的内部 API，每个大版本都在变，
 * CodeLocator 在新版 AS 上启动就崩，很大一部分就是栽在这上面。外部 adb 的命令行
 * 十几年没变过，而且和 AS 共用同一个 adb server，不会互相抢设备。
 *
 * 所有方法都会阻塞，必须在后台线程调用。
 */
class Adb(val executable: File) {

    class Result(val exit: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exit == 0
    }

    fun run(vararg args: String, timeoutMs: Long = 10_000): Result {
        val process = ProcessBuilder(listOf(executable.absolutePath) + args)
            .redirectErrorStream(false)
            .start()
        // 先读完再 waitFor：输出大于管道缓冲区时，反过来会死锁。
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return Result(-1, out, "timeout: adb ${args.joinToString(" ")}")
        }
        return Result(process.exitValue(), out, err)
    }

    fun devices(): List<Device> {
        val r = run("devices", "-l")
        if (!r.ok) throw AdbException(r.stderr.ifBlank { "adb devices failed" })
        return r.stdout.lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val model = parts.drop(2).firstOrNull { it.startsWith("model:") }
                    ?.removePrefix("model:")?.replace('_', ' ')
                Device(parts[0], parts[1], model)
            }
            .toList()
    }

    /**
     * 列出设备上正在监听的 SDK 端点。
     *
     * 看 `/proc/net/unix` 里的 abstract socket 名就够了：不需要 root，也不需要知道 pid。
     * 同一个包同时有新旧两个名字时只保留新的。
     */
    fun endpoints(serial: String): List<Endpoint> {
        val r = run("-s", serial, "shell", "cat", "/proc/net/unix")
        if (!r.ok) throw AdbException(r.stderr.ifBlank { "读取 /proc/net/unix 失败" })
        val found = LinkedHashMap<String, Endpoint>()
        for (m in SOCKET_PATTERN.findAll(r.stdout)) {
            val prefix = m.groupValues[1]
            val pkg = m.groupValues[2]
            val endpoint = Endpoint(serial, pkg, "$prefix.$pkg", legacy = prefix == "netcapture")
            val existing = found[pkg]
            if (existing == null || (existing.legacy && !endpoint.legacy)) found[pkg] = endpoint
        }
        // 新 SDK 两个名字都绑，所以只要看到过 androidhelper 就不是 legacy
        return found.values.sortedBy { it.pkg }
    }

    /** `adb forward tcp:0 localabstract:<name>`，返回电脑上分到的端口。 */
    fun forward(serial: String, socket: String): Int {
        val r = run("-s", serial, "forward", "tcp:0", "localabstract:$socket")
        val port = r.stdout.trim().toIntOrNull()
        if (!r.ok || port == null) throw AdbException(r.stderr.ifBlank { "adb forward 失败" })
        return port
    }

    fun removeForward(serial: String, port: Int) {
        runCatching { run("-s", serial, "forward", "--remove", "tcp:$port") }
    }

    /** 让 App 自己发一个 HTTPS 请求，用来确认链路是通的。 */
    fun probe(endpoint: Endpoint): Result {
        val (receiver, action) = if (endpoint.legacy) {
            "kakacat.android.netcapture.CaptureProbeReceiver" to "kakacat.android.netcapture.PROBE"
        } else {
            "kakacat.android.helper.network.ProbeReceiver" to "kakacat.android.helper.PROBE"
        }
        return run("-s", endpoint.serial, "shell", "am", "broadcast", "-n", "${endpoint.pkg}/$receiver", "-a", action)
    }

    companion object {
        private val SOCKET_PATTERN = Regex("@(androidhelper|netcapture)\\.([A-Za-z0-9_.]+)\\s*$", RegexOption.MULTILINE)

        /**
         * 找 adb：项目 local.properties 里的 sdk.dir → ANDROID_HOME / ANDROID_SDK_ROOT →
         * 各平台默认 SDK 路径 → PATH。
         */
        fun locate(project: Project?): Adb? {
            val name = if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb"
            val sdkDirs = buildList {
                project?.basePath?.let { base -> sdkDirFromLocalProperties(File(base, "local.properties"))?.let(::add) }
                System.getenv("ANDROID_HOME")?.let(::add)
                System.getenv("ANDROID_SDK_ROOT")?.let(::add)
                val home = System.getProperty("user.home")
                add("$home/Library/Android/sdk")
                add("$home/Android/Sdk")
                System.getenv("LOCALAPPDATA")?.let { add("$it/Android/Sdk") }
            }
            for (dir in sdkDirs) {
                val f = File(dir, "platform-tools/$name")
                if (f.canExecute()) return Adb(f)
            }
            System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
                val f = File(dir, name)
                if (f.canExecute()) return Adb(f)
            }
            return null
        }

        private fun sdkDirFromLocalProperties(file: File): String? {
            if (!file.isFile) return null
            return runCatching {
                Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
            }.getOrNull()
        }
    }
}

class AdbException(message: String) : Exception(message)

package kakacat.android.helper.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File

interface NetworkParams : InstrumentationParameters {
    /**
     * 命中记录的文件路径，供 netCaptureReport 汇总。
     *
     * 用 String 而不是 RegularFileProperty：插桩参数会参与构建缓存键，路径本身就是
     * 我们要的全部信息，而 File/Provider<RegularFile> 在这个接口上没有可用的重载。
     */
    @get:Input
    val hitFilePath: Property<String>

    @get:Input
    val excludes: ListProperty<String>
}

/**
 * 只做一件事：把网络客户端的**创建/调用点**换成对 `Hooks` 的静态调用。
 *
 * 为什么改调用点而不是改 OkHttp / gRPC 自己的方法体：
 *
 *  * 调用点用的是公共 API，签名极其稳定，跨大版本基本不动；改人家的实现要跟着内部
 *    结构走，每次升级都可能失效；
 *  * 改调用点天然幂等 —— 改完之后那条指令已经指向 Hooks，第二遍扫不到原目标；
 *  * 出问题时影响面局限在"这一处没被替换"，而不是"某个库的行为被改了"。
 *
 * 三条替换的栈形状都和原调用一致，所以只换一条 invoke 指令，不动局部变量表：
 *
 * ```
 * builder.build()                     → Hooks.okhttp(builder)
 * client.newWebSocket(req, listener)  → Hooks.newWebSocket(client, req, listener)
 * xxxBuilder.build()  // ManagedChannel → 原调用保留，返回值上追加 Hooks.grpc(ch)
 * ```
 */
abstract class NetworkClassVisitorFactory : AsmClassVisitorFactory<NetworkParams> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = CaptureClassVisitor(
        nextClassVisitor,
        classContext.currentClassData.className,
        parameters.get().hitFilePath.orNull?.let { File(it) }
    )

    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className.replace('.', '/')
        // 自己不能插自己（会变成 Hooks 调 Hooks 的无限递归），
        // 以及那些不可能持有网络客户端、但类数量巨大的包 —— 跳过它们纯赚构建时间。
        if (SKIP_PREFIXES.any { name.startsWith(it) }) return false
        if (isSdkClass(name)) return false
        return parameters.get().excludes.get().none { name.startsWith(it) }
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

    private companion object {
        const val SDK_PACKAGE = "kakacat/android/helper/"
        val SDK_FEATURES = listOf("network", "layout")

        val SKIP_PREFIXES = listOf(
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
    }
}

private const val HOOKS = "kakacat/android/helper/network/Hooks"

private const val OKHTTP_BUILDER = "okhttp3/OkHttpClient\$Builder"
private const val OKHTTP_CLIENT = "okhttp3/OkHttpClient"
private const val MANAGED_CHANNEL = "Lio/grpc/ManagedChannel;"

private class CaptureClassVisitor(
    next: ClassVisitor,
    private val className: String,
    private val hitFile: File?,
) : ClassVisitor(Opcodes.ASM9, next) {

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        return NetworkMethodVisitor(delegate) { what ->
            record("$className#$name$descriptor → $what")
        }
    }

    private fun record(line: String) {
        val file = hitFile ?: return
        try {
            file.parentFile?.mkdirs()
            // 每个 class 各自 append。插桩任务是并行的，所以只 append 单行，不做读改写。
            file.appendText(line + "\n")
        } catch (t: Throwable) {
            // 报告写不出来不该让构建失败
        }
    }
}

private class NetworkMethodVisitor(
    next: MethodVisitor,
    private val onHit: (String) -> Unit,
) : MethodVisitor(Opcodes.ASM9, next) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        // 1) OkHttpClient.Builder.build() → Hooks.okhttp(builder)
        //    栈上原本是 builder，静态方法签名吃掉 builder 返回 client，形状完全一致。
        if (owner == OKHTTP_BUILDER && name == "build" && descriptor == "()Lokhttp3/OkHttpClient;") {
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC, HOOKS, "okhttp",
                "(L$OKHTTP_BUILDER;)Lokhttp3/OkHttpClient;", false
            )
            onHit("Hooks.okhttp")
            return
        }

        // 2) client.newWebSocket(request, listener) → Hooks.newWebSocket(client, request, listener)
        //    包住返回的 WebSocket 才能采到上行帧，这是手工接入做不到的。
        if (owner == OKHTTP_CLIENT && name == "newWebSocket" &&
            descriptor == "(Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;"
        ) {
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC, HOOKS, "newWebSocket",
                "(L$OKHTTP_CLIENT;Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;",
                false
            )
            onHit("Hooks.newWebSocket")
            return
        }

        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)

        // 3) 任意 ChannelBuilder.build() 返回 ManagedChannel → 在返回值上追加 Hooks.grpc()
        //    这里不能替换调用：具体 builder 类型五花八门（OkHttpChannelBuilder、
        //    AndroidChannelBuilder、ManagedChannelBuilder…），但它们的返回类型都是
        //    ManagedChannel，所以按"返回类型 + 方法名"匹配，再包一层最稳。
        if (name == "build" && descriptor == "()$MANAGED_CHANNEL" && isChannelBuilder(owner)) {
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC, HOOKS, "grpc",
                "($MANAGED_CHANNEL)$MANAGED_CHANNEL", false
            )
            onHit("Hooks.grpc($owner)")
        }
    }

    /**
     * 只认名字里带 ChannelBuilder 的 owner。
     *
     * 光看 `()Lio/grpc/ManagedChannel;` 会误伤业务自己那种
     * `fun build(): ManagedChannel` 的工厂方法 —— 那种情况下里面已经调过真正的
     * builder.build()，再包一层就成了双份拦截器。
     */
    private fun isChannelBuilder(owner: String): Boolean =
        owner.endsWith("ChannelBuilder") || owner.contains("ChannelBuilder\$")
}

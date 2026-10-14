package kakacat.android.helper.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File

interface LayoutParams : InstrumentationParameters {
    @get:Input
    val hitFilePath: Property<String>

    @get:Input
    val excludes: ListProperty<String>
}

/**
 * 记录 View 的"来历"：在哪一行被 new 出来、在哪一行设置的点击监听、从哪个布局文件
 * inflate、在哪一行 show 的 Dialog。运行时入口见 runtime-layout 的 LayoutHooks。
 *
 * 这是 CodeLocator 里 Lancet 那部分的替代：Lancet 依赖 AGP 8 删除的 Transform API，
 * 新版 AGP 上用不了；这里用 AGP 的 Instrumentation API + ASM。
 *
 * 源码位置在**编译期**拼成常量字符串（`com.foo.Bar.method(Bar.kt:42)`），运行时零格式化开销。
 */
abstract class LayoutClassVisitorFactory : AsmClassVisitorFactory<LayoutParams> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = LayoutClassVisitor(
        nextClassVisitor,
        classContext.currentClassData.className,
        TypeOracle(classContext),
        parameters.get().hitFilePath.orNull?.let { File(it) },
    )

    override fun isInstrumentable(classData: ClassData): Boolean =
        InstrumentFilter.accepts(classData.className, parameters.get().excludes.get())
}

/** 回答"某个类是不是 View / Activity / Dialog 的子类"。查不到就保守地回答"不是"。 */
internal class TypeOracle(private val ctx: ClassContext) {
    private val cache = HashMap<String, List<String>?>()

    fun isSubclassOf(internalName: String, superDotted: String): Boolean {
        val dotted = internalName.replace('/', '.')
        if (dotted == superDotted) return true
        val supers = cache.getOrPut(dotted) {
            runCatching { ctx.loadClassData(dotted)?.superClasses }.getOrNull()
        }
        return supers?.contains(superDotted) == true
    }

    fun isView(name: String) = isSubclassOf(name, "android.view.View")
    fun isDialog(name: String) = isSubclassOf(name, "android.app.Dialog")
    fun isContentViewHost(name: String) =
        isSubclassOf(name, "android.app.Activity") || isDialog(name) || isSubclassOf(name, "android.view.Window")
}

private const val HOOKS = "kakacat/android/helper/layout/LayoutHooks"

private class LayoutClassVisitor(
    next: ClassVisitor,
    private val className: String,
    private val types: TypeOracle,
    private val hitFile: File?,
) : ClassVisitor(Opcodes.ASM9, next) {

    private var source: String? = null
    private val hits = LinkedHashMap<String, Int>()

    override fun visitSource(source: String?, debug: String?) {
        this.source = source
        super.visitSource(source, debug)
    }

    override fun visitMethod(
        access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?,
    ): MethodVisitor {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        return LayoutMethodVisitor(delegate, types, { line -> site(name, line) }) { hook ->
            hits.merge(hook, 1, Int::plus)
        }
    }

    /** 和异常栈帧同一格式，IDE 那边按它跳转。 */
    private fun site(method: String, line: Int): String {
        val file = source ?: (className.substringAfterLast('.').substringBefore('$') + ".java")
        return if (line > 0) "$className.$method($file:$line)" else "$className.$method($file)"
    }

    override fun visitEnd() {
        super.visitEnd()
        val file = hitFile ?: return
        if (hits.isEmpty()) return
        try {
            file.parentFile?.mkdirs()
            // 一个类一行汇总。插桩任务是并行的，所以只 append 单行，不做读改写。
            val summary = hits.entries.joinToString(", ") { (k, v) -> if (v > 1) "$k×$v" else k }
            file.appendText("$className → $summary\n")
        } catch (t: Throwable) {
            // 报告写不出来不该让构建失败
        }
    }
}

private class LayoutMethodVisitor(
    next: MethodVisitor,
    private val types: TypeOracle,
    private val site: (Int) -> String,
    private val onHit: (String) -> Unit,
) : MethodVisitor(Opcodes.ASM9, next) {

    private var line = 0

    /** 还没执行 `<init>` 的 NEW，用来区分 `new View(...)` 和构造函数里的 `super(...)`。 */
    private val pendingNew = ArrayDeque<String>()

    override fun visitLineNumber(line: Int, start: Label?) {
        this.line = line
        super.visitLineNumber(line, start)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        if (opcode == Opcodes.NEW) pendingNew.addLast(type)
        super.visitTypeInsn(opcode, type)
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
        // ---- new XxxView(...) ----
        if (opcode == Opcodes.INVOKESPECIAL && name == "<init>") {
            val isNew = pendingNew.lastOrNull() == owner
            if (isNew) pendingNew.removeLast()
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            // javac / kotlinc 的 new 都是 NEW, DUP, <args>, INVOKESPECIAL —— 调用后栈顶还剩一份引用
            if (isNew && types.isView(owner)) {
                super.visitInsn(Opcodes.DUP)
                super.visitLdcInsn(site(line))
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "created", "(Ljava/lang/Object;Ljava/lang/String;)V", false)
                onHit("created")
            }
            return
        }

        if (opcode == Opcodes.INVOKEVIRTUAL) {
            // ---- setOnXxxListener：原调用前记录，原调用不动 ----
            val listenerHook = when {
                name == "setOnClickListener" && descriptor == "(Landroid/view/View\$OnClickListener;)V" -> "click"
                name == "setOnLongClickListener" && descriptor == "(Landroid/view/View\$OnLongClickListener;)V" -> "longClick"
                name == "setOnTouchListener" && descriptor == "(Landroid/view/View\$OnTouchListener;)V" -> "touch"
                else -> null
            }
            if (listenerHook != null && types.isView(owner)) {
                // 栈：view, listener → DUP2 → view, listener, view, listener
                super.visitInsn(Opcodes.DUP2)
                super.visitLdcInsn(site(line))
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, HOOKS, listenerHook,
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V", false
                )
                onHit(listenerHook)
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }

            // ---- LayoutInflater.inflate：owner 精确匹配，换成带 site 的版本 ----
            if (owner == "android/view/LayoutInflater" && name == "inflate") {
                val hookDesc = when (descriptor) {
                    "(ILandroid/view/ViewGroup;)Landroid/view/View;" ->
                        "(Landroid/view/LayoutInflater;ILandroid/view/ViewGroup;Ljava/lang/String;)Landroid/view/View;"
                    "(ILandroid/view/ViewGroup;Z)Landroid/view/View;" ->
                        "(Landroid/view/LayoutInflater;ILandroid/view/ViewGroup;ZLjava/lang/String;)Landroid/view/View;"
                    else -> null
                }
                if (hookDesc != null) {
                    super.visitLdcInsn(site(line))
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "inflate", hookDesc, false)
                    onHit("inflate")
                    return
                }
            }

            // ---- setContentView(int) ----
            if (name == "setContentView" && descriptor == "(I)V" && types.isContentViewHost(owner)) {
                super.visitLdcInsn(site(line))
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "setContentView", "(Ljava/lang/Object;ILjava/lang/String;)V", false)
                onHit("setContentView")
                return
            }

            // ---- dialog.show() ----
            if (name == "show" && descriptor == "()V" && types.isDialog(owner)) {
                super.visitLdcInsn(site(line))
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "show", "(Ljava/lang/Object;Ljava/lang/String;)V", false)
                onHit("show")
                return
            }

            // ---- AlertDialog.Builder.show() 之类：返回 Dialog，原调用后记录 ----
            if (name == "show" && descriptor.startsWith("()L") && owner.endsWith("\$Builder")) {
                val ret = descriptor.substring(3, descriptor.length - 1)
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                if (types.isDialog(ret)) {
                    super.visitInsn(Opcodes.DUP)
                    super.visitLdcInsn(site(line))
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "dialogShown", "(Ljava/lang/Object;Ljava/lang/String;)V", false)
                    onHit("dialogShown")
                }
                return
            }
        }

        // ---- View.inflate(ctx, res, root) ----
        if (opcode == Opcodes.INVOKESTATIC && owner == "android/view/View" && name == "inflate" &&
            descriptor == "(Landroid/content/Context;ILandroid/view/ViewGroup;)Landroid/view/View;"
        ) {
            super.visitLdcInsn(site(line))
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC, HOOKS, "inflate",
                "(Landroid/content/Context;ILandroid/view/ViewGroup;Ljava/lang/String;)Landroid/view/View;", false
            )
            onHit("inflate")
            return
        }

        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }
}

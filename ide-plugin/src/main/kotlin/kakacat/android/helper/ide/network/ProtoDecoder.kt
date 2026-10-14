package kakacat.android.helper.ide.network

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.TextFormat

/**
 * protobuf 解码。
 *
 * 设备端用的通常是 protobuf-lite，运行时没有 descriptor，所以消息以裸字节送过来：
 *
 *  * 有 descriptor set（使用方构建期生成、挂在 debug assets 里）时，按 gRPC 方法名找到
 *    入参/出参类型，用 DynamicMessage 还原出带字段名的文本；
 *  * 没有时退回 [heuristic]：只按 wire 格式拆，显示字段号。
 */
class ProtoRegistry private constructor(
    private val methods: Map<String, Descriptors.MethodDescriptor>,
) {

    /** [grpcMethod] 形如 `pkg.Service/Method`；[dir] 是 "up"（请求）或 "down"（响应）。 */
    fun decode(grpcMethod: String, dir: String, bytes: ByteArray): String? {
        val m = methods[grpcMethod] ?: return null
        val type = if (dir == "up") m.inputType else m.outputType
        return runCatching {
            val msg = DynamicMessage.parseFrom(type, bytes)
            "# ${type.fullName}\n" + TextFormat.printer().escapingNonAscii(false).printToString(msg)
        }.getOrNull()
    }

    val methodCount: Int get() = methods.size

    companion object {
        /** 解析失败返回 null —— 退回启发式解码，不影响其它功能。 */
        fun from(descriptorSet: ByteArray): ProtoRegistry? = try {
            val set = DescriptorProtos.FileDescriptorSet.parseFrom(descriptorSet)
            val protos = set.fileList.associateBy { it.name }
            val built = HashMap<String, Descriptors.FileDescriptor>()

            fun build(name: String): Descriptors.FileDescriptor? {
                built[name]?.let { return it }
                val proto = protos[name] ?: return null
                val deps = proto.dependencyList.mapNotNull { build(it) }.toTypedArray()
                // allowUnknownDependencies：descriptor set 没带全 import 时也尽量建出来
                val fd = Descriptors.FileDescriptor.buildFrom(proto, deps, true)
                built[name] = fd
                return fd
            }

            val methods = HashMap<String, Descriptors.MethodDescriptor>()
            for (name in protos.keys) {
                val fd = runCatching { build(name) }.getOrNull() ?: continue
                for (service in fd.services) {
                    for (method in service.methods) {
                        methods["${service.fullName}/${method.name}"] = method
                    }
                }
            }
            ProtoRegistry(methods)
        } catch (t: Throwable) {
            // 包括 IDE 里 protobuf 版本不兼容导致的 LinkageError
            null
        }

        /**
         * 没有 descriptor 时的启发式解码：按 wire 格式逐字段拆开，长度前缀的字段先试着当
         * 嵌套消息解，不行再当 UTF-8 字符串，最后才是十六进制。
         *
         * 拆不动（不是合法的 protobuf）时返回 null。
         */
        fun heuristic(bytes: ByteArray): String? {
            val sb = StringBuilder()
            return if (decodeFields(bytes, 0, bytes.size, 0, sb)) sb.toString() else null
        }

        private fun decodeFields(b: ByteArray, from: Int, to: Int, depth: Int, out: StringBuilder): Boolean {
            if (depth > 16) return false
            var i = from
            val indent = "  ".repeat(depth)
            val start = out.length
            while (i < to) {
                val (tag, n1) = varint(b, i, to) ?: return rollback(out, start)
                i += n1
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (field <= 0) return rollback(out, start)
                when (wire) {
                    0 -> {
                        val (v, n) = varint(b, i, to) ?: return rollback(out, start)
                        i += n
                        out.append(indent).append(field).append(": ").append(v).append('\n')
                    }
                    1 -> {
                        if (i + 8 > to) return rollback(out, start)
                        var v = 0L
                        for (k in 7 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xff)
                        i += 8
                        out.append(indent).append(field).append(": ").append(v)
                            .append("  (double ").append(java.lang.Double.longBitsToDouble(v)).append(")\n")
                    }
                    5 -> {
                        if (i + 4 > to) return rollback(out, start)
                        var v = 0
                        for (k in 3 downTo 0) v = (v shl 8) or (b[i + k].toInt() and 0xff)
                        i += 4
                        out.append(indent).append(field).append(": ").append(v)
                            .append("  (float ").append(java.lang.Float.intBitsToFloat(v)).append(")\n")
                    }
                    2 -> {
                        val (len, n) = varint(b, i, to) ?: return rollback(out, start)
                        i += n
                        if (len < 0 || i + len > to) return rollback(out, start)
                        val end = i + len.toInt()
                        val mark = out.length
                        out.append(indent).append(field).append(" {\n")
                        if (len > 0 && decodeFields(b, i, end, depth + 1, out)) {
                            out.append(indent).append("}\n")
                        } else {
                            out.setLength(mark)
                            out.append(indent).append(field).append(": ")
                            val s = String(b, i, len.toInt(), Charsets.UTF_8)
                            if (s.all { it == '\n' || it == '\t' || !it.isISOControl() } && !s.contains('�')) {
                                out.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"')
                            } else {
                                out.append("0x").append(hex(b, i, end))
                            }
                            out.append('\n')
                        }
                        i = end
                    }
                    else -> return rollback(out, start)
                }
            }
            return true
        }

        private fun rollback(out: StringBuilder, start: Int): Boolean {
            out.setLength(start)
            return false
        }

        private fun varint(b: ByteArray, from: Int, to: Int): Pair<Long, Int>? {
            var result = 0L
            var shift = 0
            var i = from
            while (i < to && shift < 64) {
                val x = b[i].toInt() and 0xff
                result = result or ((x and 0x7f).toLong() shl shift)
                i++
                if (x and 0x80 == 0) return result to (i - from)
                shift += 7
            }
            return null
        }

        private fun hex(b: ByteArray, from: Int, to: Int): String {
            val sb = StringBuilder()
            for (k in from until minOf(to, from + 64)) sb.append("%02x".format(b[k]))
            if (to - from > 64) sb.append("…")
            return sb.toString()
        }
    }
}

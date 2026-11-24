package app.kaze.offline

import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap


















class MarshalException(message: String) : Exception(message)

sealed class RValue

object RNil : RValue()
object RTrue : RValue()
object RFalse : RValue()

class RInt(val value: Long) : RValue()
class RBignum(val negative: Boolean, val words: IntArray) : RValue()
class RFloat(val text: String) : RValue()

class RString(var bytes: ByteArray) : RValue() {
    val ivars = mutableListOf<Pair<RSymbol, RValue>>()
}

class RSymbol(val name: String) : RValue()

class RArray(val items: MutableList<RValue> = mutableListOf()) : RValue() {
    val ivars = mutableListOf<Pair<RSymbol, RValue>>()
}

class RHash(val pairs: MutableList<Pair<RValue, RValue>> = mutableListOf()) : RValue() {
    var default: RValue? = null
    val ivars = mutableListOf<Pair<RSymbol, RValue>>()
}

class RObject(val className: String) : RValue() {
    val attrs = mutableListOf<Pair<RSymbol, RValue>>()
    val ivars = mutableListOf<Pair<RSymbol, RValue>>()
}

class RUserDef(val className: String, var data: ByteArray) : RValue() {
    val ivars = mutableListOf<Pair<RSymbol, RValue>>()
}

class RUserMarshal(val className: String, var data: RValue) : RValue() {
    val ivars = mutableListOf<Pair<RSymbol, RValue>>()
}

class RRegexp(var source: ByteArray, val flags: Int) : RValue() {
    val ivars = mutableListOf<Pair<RSymbol, RValue>>()
}

class RClassRef(val name: String, val isModule: Boolean) : RValue() {
    val ivars = mutableListOf<Pair<RSymbol, RValue>>()
}

class RStructLike(val className: String, val isData: Boolean) : RValue() {
    val attrs = mutableListOf<Pair<RSymbol, RValue>>()
    val ivars = mutableListOf<Pair<RSymbol, RValue>>()
}

class RUClass(val className: String, val value: RValue) : RValue()

class RExtended(val moduleName: String, val value: RValue) : RValue()

object RubyMarshal {
    private const val HEADER_MAJOR = 4
    private const val HEADER_MINOR = 8
    private const val MAX_COUNT = 4_000_000

    fun decode(bytes: ByteArray): RValue = Decoder(bytes).decode()

    fun encode(value: RValue): ByteArray = Encoder().encode(value)

    
    fun isLossless(bytes: ByteArray, value: RValue = decode(bytes)): Boolean =
        runCatching { encode(value).contentEquals(bytes) }.getOrDefault(false)

    

    private class Decoder(private val data: ByteArray) {
        private var pos = 0
        private val links = ArrayList<RValue>()
        private val symbols = ArrayList<RSymbol>()

        fun decode(): RValue {
            if (readByte() != HEADER_MAJOR || readByte() != HEADER_MINOR) {
                throw MarshalException("不是 Marshal 4.8 数据")
            }
            val value = try {
                readValue()
            } catch (error: MarshalException) {
                throw MarshalException("${error.message}（偏移 $pos，共 ${data.size} 字节）")
            }
            if (pos != data.size) {
                throw MarshalException("尾部有 ${data.size - pos} 字节未解析")
            }
            return value
        }

        private fun readByte(): Int {
            if (pos >= data.size) throw MarshalException("数据提前结束")
            return data[pos++].toInt() and 0xFF
        }

        private fun readSignedByte(): Int {
            val raw = readByte()
            return if (raw >= 128) raw - 256 else raw
        }

        private fun readBytes(count: Int): ByteArray {
            if (count < 0 || pos + count > data.size) throw MarshalException("长度越界：$count")
            val slice = data.copyOfRange(pos, pos + count)
            pos += count
            return slice
        }

        
        private fun readLong(): Long {
            val first = readSignedByte()
            if (first == 0) return 0L
            if (first in 6..127) return (first - 5).toLong()
            if (first in -128..-6) return (first + 5).toLong()
            val size = if (first > 0) first else -first
            if (size > 8) throw MarshalException("整数过长：$size 字节")
            var value = 0L
            for (index in 0 until size) {
                value = value or ((readByte().toLong()) shl (8 * index))
            }
            if (first < 0 && size < 8) {
                value -= 1L shl (8 * size)
            }
            return value
        }

        private fun readCount(what: String): Int {
            val raw = readLong()
            if (raw < 0 || raw > MAX_COUNT) throw MarshalException("$what 数量异常：$raw")
            return raw.toInt()
        }

        private fun readSymbol(): RSymbol {
            val type = readByte()
            return when (type) {
                ':' .code -> {
                    val name = String(readBytes(readCount("符号")), Charsets.UTF_8)
                    RSymbol(name).also { symbols.add(it) }
                }

                ';'.code -> {
                    val index = readLong().toInt()
                    symbols.getOrNull(index) ?: throw MarshalException("符号链接越界：$index")
                }

                else -> throw MarshalException("这里应当是符号，实际是 0x${type.toString(16)}")
            }
        }

        private fun readIvars(): MutableList<Pair<RSymbol, RValue>> {
            val count = readCount("实例变量")
            val ivars = ArrayList<Pair<RSymbol, RValue>>(count)
            repeat(count) { ivars.add(readSymbol() to readValue()) }
            return ivars
        }

        private fun attachIvars(value: RValue, ivars: MutableList<Pair<RSymbol, RValue>>) {
            when (value) {
                is RString -> value.ivars.addAll(ivars)
                is RArray -> value.ivars.addAll(ivars)
                is RHash -> value.ivars.addAll(ivars)
                is RObject -> value.ivars.addAll(ivars)
                is RUserDef -> value.ivars.addAll(ivars)
                is RUserMarshal -> value.ivars.addAll(ivars)
                is RRegexp -> value.ivars.addAll(ivars)
                is RClassRef -> value.ivars.addAll(ivars)
                is RStructLike -> value.ivars.addAll(ivars)
                else -> throw MarshalException("类型 ${value::class.simpleName} 不支持实例变量")
            }
        }

        fun readValue(): RValue {
            val type = readByte().toChar()
            return when (type) {
                '0' -> RNil
                'T' -> RTrue
                'F' -> RFalse

                'i' -> RInt(readLong())

                'l' -> {
                    val sign = readByte()
                    val words = readCount("大整数")
                    RBignum(
                        sign == '-'.code,
                        IntArray(words) { (readByte() or (readByte() shl 8)) },
                    ).also { links.add(it) }
                }

                'f' -> {
                    val text = String(readBytes(readCount("浮点")), Charsets.UTF_8)
                    RFloat(text).also { links.add(it) }
                }

                '"' -> {
                    val length = readCount("字符串")
                    RString(readBytes(length)).also { links.add(it) }
                }

                ':' -> {
                    val name = String(readBytes(readCount("符号")), Charsets.UTF_8)
                    RSymbol(name).also { symbols.add(it) }
                }

                ';' -> {
                    val index = readLong().toInt()
                    symbols.getOrNull(index) ?: throw MarshalException("符号链接越界：$index")
                }

                '@' -> {
                    val index = readLong().toInt()
                    links.getOrNull(index) ?: throw MarshalException("对象链接越界：$index")
                }

                '[' -> {
                    val array = RArray()
                    links.add(array)
                    repeat(readCount("数组")) { array.items.add(readValue()) }
                    array
                }

                '{', '}' -> {
                    val hash = RHash()
                    links.add(hash)
                    repeat(readCount("哈希")) { hash.pairs.add(readValue() to readValue()) }
                    if (type == '}') hash.default = readValue()
                    hash
                }

                'o' -> {
                    val className = readSymbol().name
                    val obj = RObject(className)
                    links.add(obj)
                    repeat(readCount("成员")) { obj.attrs.add(readSymbol() to readValue()) }
                    obj
                }

                'S', 'd' -> {
                    val className = readSymbol().name
                    val struct = RStructLike(className, type == 'd')
                    links.add(struct)
                    repeat(readCount("成员")) { struct.attrs.add(readSymbol() to readValue()) }
                    struct
                }

                'U' -> {
                    val className = readSymbol().name
                    val data = readBytes(readCount("用户数据"))
                    RUserDef(className, data).also { links.add(it) }
                }

                'u' -> {
                    val className = readSymbol().name
                    val obj = RUserMarshal(className, RNil)
                    links.add(obj)
                    obj.data = readValue()
                    obj
                }

                '/' -> {
                    val source = readBytes(readCount("正则"))
                    RRegexp(source, readByte()).also { links.add(it) }
                }

                'c', 'm' -> {
                    val name = String(readBytes(readCount("类名")), Charsets.UTF_8)
                    RClassRef(name, type == 'm').also { links.add(it) }
                }

                'C' -> RUClass(readSymbol().name, readValue())

                'e' -> RExtended(readSymbol().name, readValue())

                'I' -> {
                    val inner = readValue()
                    attachIvars(inner, readIvars())
                    inner
                }

                else -> throw MarshalException("不支持的 Marshal 类型：$type")
            }
        }
    }

    

    private class Encoder {
        private val out = ByteArrayOutputStream()
        private val links = IdentityHashMap<RValue, Int>()
        private val symbols = LinkedHashMap<String, Int>()

        fun encode(value: RValue): ByteArray {
            out.write(HEADER_MAJOR)
            out.write(HEADER_MINOR)
            writeValue(value)
            return out.toByteArray()
        }

        private fun writeByte(value: Int) = out.write(value and 0xFF)

        private fun writeLong(value: Long) {
            when {
                value == 0L -> writeByte(0)
                value in 1..122 -> writeByte((value + 5).toInt())
                value in -123..-1 -> writeByte((value - 5).toInt())
                else -> {
                    val bytes = ArrayList<Int>()
                    var rest = value
                    if (value > 0) {
                        
                        
                        while (rest != 0L) {
                            bytes.add((rest and 0xFF).toInt())
                            rest = rest ushr 8
                        }
                        writeByte(bytes.size)
                    } else {
                        while (true) {
                            bytes.add((rest and 0xFF).toInt())
                            rest = rest shr 8
                            if (rest == -1L && (bytes.last() and 0x80) != 0) break
                        }
                        writeByte(-bytes.size)
                    }
                    bytes.forEach { writeByte(it) }
                }
            }
        }

        private fun writeBignum(value: Long) {
            val signedBytes = java.math.BigInteger.valueOf(value).abs().toByteArray()
            val magnitude = if (signedBytes.size > 1 && signedBytes[0] == 0.toByte()) {
                signedBytes.copyOfRange(1, signedBytes.size)
            } else {
                signedBytes
            }
            val words = (magnitude.size + 1) / 2
            writeByte('l'.code)
            writeByte(if (value < 0) '-'.code else '+'.code)
            writeLong(words.toLong())
            repeat(words) { word ->
                val lowIndex = magnitude.size - 1 - word * 2
                val low = magnitude[lowIndex].toInt() and 0xFF
                val high = if (lowIndex > 0) magnitude[lowIndex - 1].toInt() and 0xFF else 0
                writeByte(low)
                writeByte(high)
            }
        }

        private fun writeSymbol(symbol: RSymbol) {
            val known = symbols[symbol.name]
            if (known != null) {
                writeByte(';'.code)
                writeLong(known.toLong())
            } else {
                symbols[symbol.name] = symbols.size
                writeByte(':'.code)
                val bytes = symbol.name.toByteArray(Charsets.UTF_8)
                writeLong(bytes.size.toLong())
                out.write(bytes)
            }
        }

        
        private fun claim(value: RValue): Boolean {
            val known = links[value]
            if (known != null) {
                writeByte('@'.code)
                writeLong(known.toLong())
                return false
            }
            links[value] = links.size
            return true
        }

        private fun ivarsOf(value: RValue): MutableList<Pair<RSymbol, RValue>>? = when (value) {
            is RString -> value.ivars
            is RArray -> value.ivars
            is RHash -> value.ivars
            is RObject -> value.ivars
            is RUserDef -> value.ivars
            is RUserMarshal -> value.ivars
            is RRegexp -> value.ivars
            is RClassRef -> value.ivars
            is RStructLike -> value.ivars
            else -> null
        }

        private fun writeIvars(ivars: List<Pair<RSymbol, RValue>>) {
            writeLong(ivars.size.toLong())
            ivars.forEach { (name, value) ->
                writeSymbol(name)
                writeValue(value)
            }
        }

        



        private fun begin(value: RValue): Boolean {
            if (!claim(value)) return false
            val ivars = ivarsOf(value)
            if (ivars != null && ivars.isNotEmpty()) writeByte('I'.code)
            return true
        }

        private fun finish(value: RValue) {
            val ivars = ivarsOf(value)
            if (ivars != null && ivars.isNotEmpty()) writeIvars(ivars)
        }

        private fun writeValue(value: RValue) {
            when (value) {
                is RNil -> writeByte('0'.code)
                is RTrue -> writeByte('T'.code)
                is RFalse -> writeByte('F'.code)

                is RInt -> {
                    if (value.value in -1_073_741_824L..1_073_741_823L) {
                        writeByte('i'.code)
                        writeLong(value.value)
                    } else {
                        if (!begin(value)) return
                        writeBignum(value.value)
                        finish(value)
                    }
                }

                is RSymbol -> {
                    
                    writeSymbol(value)
                }

                is RUClass -> {
                    writeByte('C'.code)
                    writeSymbol(RSymbol(value.className))
                    writeValue(value.value)
                }

                is RExtended -> {
                    writeByte('e'.code)
                    writeSymbol(RSymbol(value.moduleName))
                    writeValue(value.value)
                }

                is RBignum -> {
                    if (!begin(value)) return
                    writeByte('l'.code)
                    writeByte(if (value.negative) '-'.code else '+'.code)
                    writeLong(value.words.size.toLong())
                    value.words.forEach { word ->
                        writeByte(word and 0xFF)
                        writeByte((word shr 8) and 0xFF)
                    }
                    finish(value)
                }

                is RFloat -> {
                    if (!begin(value)) return
                    val text = value.text.toByteArray(Charsets.UTF_8)
                    writeByte('f'.code)
                    writeLong(text.size.toLong())
                    out.write(text)
                    finish(value)
                }

                is RString -> {
                    if (!begin(value)) return
                    writeByte('"'.code)
                    writeLong(value.bytes.size.toLong())
                    out.write(value.bytes)
                    finish(value)
                }

                is RArray -> {
                    if (!begin(value)) return
                    writeByte('['.code)
                    writeLong(value.items.size.toLong())
                    value.items.forEach { writeValue(it) }
                    finish(value)
                }

                is RHash -> {
                    if (!begin(value)) return
                    val withDefault = value.default != null
                    writeByte(if (withDefault) '}'.code else '{'.code)
                    writeLong(value.pairs.size.toLong())
                    value.pairs.forEach { (key, item) ->
                        writeValue(key)
                        writeValue(item)
                    }
                    if (withDefault) writeValue(value.default!!)
                    finish(value)
                }

                is RObject -> {
                    if (!begin(value)) return
                    writeByte('o'.code)
                    writeSymbol(RSymbol(value.className))
                    writeLong(value.attrs.size.toLong())
                    value.attrs.forEach { (name, item) ->
                        writeSymbol(name)
                        writeValue(item)
                    }
                    finish(value)
                }

                is RStructLike -> {
                    if (!begin(value)) return
                    writeByte(if (value.isData) 'd'.code else 'S'.code)
                    writeSymbol(RSymbol(value.className))
                    writeLong(value.attrs.size.toLong())
                    value.attrs.forEach { (name, item) ->
                        writeSymbol(name)
                        writeValue(item)
                    }
                    finish(value)
                }

                is RUserDef -> {
                    if (!begin(value)) return
                    writeByte('U'.code)
                    writeSymbol(RSymbol(value.className))
                    writeLong(value.data.size.toLong())
                    out.write(value.data)
                    finish(value)
                }

                is RUserMarshal -> {
                    if (!begin(value)) return
                    writeByte('u'.code)
                    writeSymbol(RSymbol(value.className))
                    writeValue(value.data)
                    finish(value)
                }

                is RRegexp -> {
                    if (!begin(value)) return
                    writeByte('/'.code)
                    writeLong(value.source.size.toLong())
                    out.write(value.source)
                    writeByte(value.flags)
                    finish(value)
                }

                is RClassRef -> {
                    if (!begin(value)) return
                    writeByte(if (value.isModule) 'm'.code else 'c'.code)
                    val name = value.name.toByteArray(Charsets.UTF_8)
                    writeLong(name.size.toLong())
                    out.write(name)
                    finish(value)
                }
            }
        }
    }
}

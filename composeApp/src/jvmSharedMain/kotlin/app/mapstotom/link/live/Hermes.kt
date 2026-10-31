package app.mapstotom.link.live

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * Hermes: TomTom's binary value codec used by Hercules (POST /h204). Port of server/hermes.py.
 * Values: null, Boolean, Long, String, ByteArray (buffer), List<Any?>, HStruct (ordered tag -> value).
 * A body is 1 flag byte (0x00 raw, 0x10 zlib) + one value.
 */
class HStruct(val items: List<Pair<Int, Any?>>) {
    constructor(vararg items: Pair<Int, Any?>) : this(items.toList())
    operator fun get(tag: Int): Any? = items.firstOrNull { it.first == tag }?.second
    override fun equals(other: Any?) = other is HStruct && items.size == other.items.size &&
        items.zip(other.items).all { (a, b) -> a.first == b.first && Hermes.same(a.second, b.second) }
    override fun hashCode() = items.size
    override fun toString() = "HStruct(" + items.joinToString { (t, v) -> "$t=${Hermes.show(v)}" } + ")"
}

object Hermes {
    private const val MAX_STR = 0x3FFF

    fun encode(v: Any?): ByteArray = ByteArrayOutputStream().also { enc(v, it) }.toByteArray()

    private fun enc(v: Any?, o: ByteArrayOutputStream) {
        when (v) {
            null -> o.write(0x0f)
            is Boolean -> o.write(if (v) 0xc1 else 0xc0)
            is Int -> encInt(v.toLong(), o)
            is Long -> encInt(v, o)
            is String -> {
                val b = v.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_STR) it.copyOf(MAX_STR) else it }
                o.write(0x40 or (b.size shr 8)); o.write(b.size and 0xff); o.write(b)
            }
            is ByteArray -> {
                require(v.size <= MAX_STR) { "buffer too long" }
                o.write(0x80 or (v.size shr 8)); o.write(v.size and 0xff); o.write(v)
            }
            is List<*> -> { count(0x20, v.size, o); v.forEach { enc(it, o) } }
            is HStruct -> { count(0x10, v.items.size, o); v.items.forEach { (t, x) -> o.write(t); enc(x, o) } }
            else -> error("can't encode ${v::class}")
        }
    }

    private fun encInt(n: Long, o: ByteArrayOutputStream) {
        val m = if (n < 0) -n else n
        val size = if (m < 0x100) 1 else if (m < 0x10000) 2 else if (m < 0x80000000L) 4 else 8
        val base = when (size) { 1 -> 0xC2; 2 -> 0xC4; 4 -> 0xC6; else -> 0xC8 }
        o.write(base or (if (n < 0) 1 else 0))
        for (i in size - 1 downTo 0) o.write(((m ushr (8 * i)) and 0xff).toInt())
    }

    private fun count(bits: Int, n: Int, o: ByteArrayOutputStream) {
        if (n <= 7) { o.write(bits or n); return }
        require(n <= 0x7FF) { "too many children" }
        o.write(bits or (n shr 8) or 8); o.write(n and 0xff)
    }

    fun decode(d: ByteArray): Any? = Dec(d).value(false).second

    private class Dec(val d: ByteArray) {
        var p = 0
        fun u(i: Int) = d[i].toInt() and 0xff
        fun take(n: Int): ByteArray { require(p + n <= d.size) { "truncated" }; return d.copyOfRange(p, p + n).also { p += n } }
        fun value(tagged: Boolean): Pair<Int?, Any?> {
            val tag = if (tagged) u(p++) else null
            val b = u(p)
            when (b and 0xC0) {
                0x40 -> { val n = ((b and 0x3F) shl 8) or u(p + 1); p += 2; return tag to String(take(n), Charsets.UTF_8) }
                0x80 -> { val n = ((b and 0x3F) shl 8) or u(p + 1); p += 2; return tag to take(n) }
                0x00 -> {
                    if (b == 0x0F) { p++; return tag to null }
                    val t = b and 0x30
                    require(t == 0x10 || t == 0x20) { "bad type byte 0x%02x".format(b) }
                    val n: Int
                    if (b and 8 != 0) { n = ((b and 7) shl 8) or u(p + 1); p += 2 } else { n = b and 7; p++ }
                    return if (t == 0x10) tag to HStruct((0 until n).map { val (tg, v) = value(true); tg!! to v })
                    else tag to (0 until n).map { value(false).second }
                }
                else -> {
                    val kind = b and 0xFE; val neg = b and 1 == 1
                    if (kind == 0xC0) { p++; return tag to (b == 0xC1) }
                    val size = when (kind) { 0xC2 -> 1; 0xC4 -> 2; 0xC6 -> 4; 0xC8 -> 8; else -> error("bad type byte 0x%02x".format(b)) }
                    p++
                    var m = 0L; take(size).forEach { m = (m shl 8) or (it.toLong() and 0xff) }
                    return tag to (if (neg) -m else m)
                }
            }
        }
    }

    fun packBody(msg: Any?, compress: Boolean = true): ByteArray {
        val raw = encode(msg)
        if (compress) {
            val z = zlib(raw)
            if (z.size < raw.size) return byteArrayOf(0x10) + z
        }
        return byteArrayOf(0x00) + raw
    }

    fun unpackBody(body: ByteArray): Any? = when (val flag = (body[0].toInt() and 0xff) shr 4) {
        0 -> decode(body.copyOfRange(1, body.size))
        1 -> decode(unzlib(body.copyOfRange(1, body.size)))
        else -> error("unknown compression algorithm $flag")
    }

    private fun zlib(b: ByteArray): ByteArray {
        val d = Deflater(6); d.setInput(b); d.finish()
        val o = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) o.write(buf, 0, d.deflate(buf))
        d.end(); return o.toByteArray()
    }

    private fun unzlib(b: ByteArray): ByteArray {
        val i = Inflater(); i.setInput(b)
        val o = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!i.finished()) { val n = i.inflate(buf); if (n == 0 && (i.needsInput() || i.needsDictionary())) break; o.write(buf, 0, n) }
        i.end(); return o.toByteArray()
    }

    internal fun same(a: Any?, b: Any?): Boolean = when {
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        a is List<*> && b is List<*> -> a.size == b.size && a.zip(b).all { (x, y) -> same(x, y) }
        a is Number && b is Number -> a.toLong() == b.toLong()
        else -> a == b
    }

    fun show(v: Any?): String = when (v) {
        is ByteArray -> "bytes:" + v.joinToString("") { "%02x".format(it) }
        is List<*> -> v.joinToString(prefix = "[", postfix = "]") { show(it) }
        is String -> "\"$v\""
        else -> v.toString()
    }
}

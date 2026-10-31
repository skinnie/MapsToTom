package app.mapstotom.link.live

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * XMsg: the ASCII reply format of the TomTom's GET services (port of server/weather.py helpers).
 * Header "%08d" total length, "%04d" error, "%04d" argument count, then the arguments.
 */
object XMsg {
    private fun a(s: String) = s.toByteArray(Charsets.US_ASCII)

    fun int(v: Long): ByteArray = a("1" + "%012d".format(v))
    fun str(t: String): ByteArray = str(t.toByteArray(Charsets.UTF_8))
    fun str(t: ByteArray): ByteArray = a("2" + "%06d".format(t.size)) + t
    fun buf(b: ByteArray): ByteArray = a("7" + "%08d".format(b.size)) + b

    fun list(items: List<ByteArray>, sep: Byte = ';'.code.toByte()): ByteArray {
        val p = join(items, byteArrayOf(sep))
        return byteArrayOf('8'.code.toByte(), sep) + a("%06d".format(p.size)) + p
    }

    fun table(rows: List<List<ByteArray>>, cs: Byte = '\t'.code.toByte(), rs: Byte = '\n'.code.toByte()): ByteArray {
        val p = join(rows.map { join(it, byteArrayOf(cs)) }, byteArrayOf(rs))
        return byteArrayOf('4'.code.toByte(), cs, rs) + a("%06d".format(p.size)) + p
    }

    fun reply(args: List<ByteArray>, err: Int = 0): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(a("%04d%04d".format(err, args.size)))
        args.forEach { body.write(it) }
        return a("%08d".format(body.size() + 8)) + body.toByteArray()
    }

    internal fun join(parts: List<ByteArray>, sep: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        parts.forEachIndexed { i, p -> if (i > 0) o.write(sep); o.write(p) }
        return o.toByteArray()
    }

    /** Parsed reply, for tests: error code and arguments as ("int", Long) / ("str"|"buf", ByteArray) / ("table", rows). */
    fun parse(b: ByteArray): Pair<Int, List<Pair<String, Any>>> {
        fun num(from: Int, n: Int) = String(b, from, n, Charsets.US_ASCII).toLong()
        val err = num(8, 4).toInt(); val n = num(12, 4).toInt()
        var p = 16; val out = ArrayList<Pair<String, Any>>()
        repeat(n) {
            when (b[p].toInt().toChar()) {
                '1' -> { out += "int" to num(p + 1, 12); p += 13 }
                '2' -> { val l = num(p + 1, 6).toInt(); out += "str" to b.copyOfRange(p + 7, p + 7 + l); p += 7 + l }
                '7' -> { val l = num(p + 1, 8).toInt(); out += "buf" to b.copyOfRange(p + 9, p + 9 + l); p += 9 + l }
                '4' -> {
                    val cs = b[p + 1]; val rs = b[p + 2]; val l = num(p + 3, 6).toInt()
                    val data = b.copyOfRange(p + 9, p + 9 + l); p += 9 + l
                    out += "table" to (if (data.isEmpty()) emptyList() else split(data, rs).map { split(it, cs) })
                }
                '8' -> { val l = num(p + 2, 6).toInt(); out += "list" to split(b.copyOfRange(p + 8, p + 8 + l), b[p + 1]); p += 8 + l }
                else -> error("unknown XMsg argument type at $p")
            }
        }
        return err to out
    }

    private fun split(d: ByteArray, sep: Byte): List<ByteArray> {
        val out = ArrayList<ByteArray>(); var st = 0
        for (i in d.indices) if (d[i] == sep) { out += d.copyOfRange(st, i); st = i + 1 }
        out += d.copyOfRange(st, d.size)
        return out
    }
}

/** Python's "%.Nf": rounds the exact binary value, half to even (String.format can differ in the last digit). */
internal fun pyFixed(v: Double, digits: Int): String {
    val s = BigDecimal(v).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
    return if (v < 0 && !s.startsWith("-")) "-$s" else s
}

/** Python's round() to an integer: half to even. */
internal fun pyRound(v: Double): Long = Math.rint(v).toLong()

/** urllib.parse.parse_qs, first value of each key. */
internal fun parseQuery(q: String, keepBlank: Boolean = false): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (part in q.split('&')) {
        if (part.isEmpty()) continue
        val k = part.substringBefore('='); val v = if ('=' in part) part.substringAfter('=') else ""
        if (v.isEmpty() && !keepBlank) continue
        val key = java.net.URLDecoder.decode(k, "UTF-8")
        if (key !in out) out[key] = java.net.URLDecoder.decode(v, "UTF-8")
    }
    return out
}

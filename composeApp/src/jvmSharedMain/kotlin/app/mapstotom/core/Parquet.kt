package app.mapstotom.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * A small Parquet reader: enough to read selected columns of selected row groups from a file on a web server,
 * a range at a time, without downloading the whole file. Handles what open place data sets use: Snappy, Zstandard
 * (through [ParquetCodecs]), gzip or no compression, PLAIN and dictionary values, data pages v1 and v2, optional columns and one level of lists.
 * Spec: github.com/apache/parquet-format.
 */

/** Reads byte ranges of a file. */
interface RangeSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

/** The server refused the request: a login token is missing, wrong, or hasn't been given access yet. */
class AccessDenied(message: String) : Exception(message)

/**
 * A file on a web server that answers range requests. [auth] headers (a login token) go only to [url]; when it
 * redirects to a download server, as Hugging Face does, the parts are fetched from there without them.
 */
class HttpRangeSource(private val net: Net, url: String, auth: Map<String, String> = emptyMap()) : RangeSource {
    override val size: Long
    private var target = url
    private var headers = auth
    private val name = url.substringAfterLast('/')

    init {
        if (auth.isNotEmpty()) {
            val r = net.get(url, auth + ("Range" to "bytes=-8"), followRedirects = false, timeoutMs = 60_000)
            if (r.code == 401 || r.code == 403) throw AccessDenied("the server refused the login token for $name (HTTP ${r.code})")
            if (r.code in 300..399) {
                val loc = r.header("Location") ?: error("redirect without a target for $name")
                target = java.net.URI(url).resolve(loc).toString()
                headers = emptyMap()
            }
        }
        val r = net.get(target, headers + ("Range" to "bytes=-8"), timeoutMs = 60_000)
        if (r.code == 401 || r.code == 403) throw AccessDenied("the server refused access to $name (HTTP ${r.code})")
        require(r.code == 206) { "the server doesn't send parts of files (HTTP ${r.code})" }
        size = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: error("no file size in the server's reply")
    }

    override fun read(offset: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val r = net.get(target, headers + ("Range" to "bytes=$offset-${offset + length - 1}"), timeoutMs = 60_000)
        require(r.code == 206 && r.body.size == length) { "download of part of $name failed (HTTP ${r.code})" }
        return r.body
    }
}

/**
 * Decompressors that need a native library, set by each app at start (desktop and Android both use zstd-jni).
 * [zstd] gets the compressed bytes and the size they unpack to.
 */
object ParquetCodecs {
    @Volatile var zstd: ((ByteArray, Int) -> ByteArray)? = null
}

class ParquetColumn(
    val path: String, val type: Int, val codec: Int, val start: Long, val length: Long, val numValues: Long,
    val maxDef: Int, val maxRep: Int, val min: ByteArray?, val max: ByteArray?,
)

class ParquetRowGroup(val numRows: Long, val columns: Map<String, ParquetColumn>)

/**
 * A Parquet file read through [src]. Its footer (the file's index, often over a megabyte) is kept in [footerCache]
 * when given, so reading the same release again doesn't download it again.
 */
class ParquetFile(private val src: RangeSource, footerCache: java.io.File? = null) {
    val rowGroups: List<ParquetRowGroup>

    init {
        require(src.size >= 12) { "not a Parquet file" }
        val tail = src.read(src.size - 8, 8)
        require(String(tail, 4, 4, Charsets.ISO_8859_1) == "PAR1") { "not a Parquet file" }
        val len = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        require(len > 0 && len <= src.size - 12) { "damaged Parquet footer" }
        val cached = footerCache?.takeIf { it.length() == len.toLong() }?.let { runCatching { it.readBytes() }.getOrNull() }
        val footer = cached ?: src.read(src.size - 8 - len, len).also { b ->
            footerCache?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeBytes(b) } }
        }
        val meta = Thrift(footer).struct()
        val levels = schemaLevels(meta.list(2).map { it as TStruct })
        rowGroups = meta.list(4).map { rg ->
            rg as TStruct
            val cols = rg.list(1).map { cc ->
                val m = (cc as TStruct).struct(3) ?: error("column without metadata")
                val path = m.list(3).joinToString(".") { String(it as ByteArray, Charsets.UTF_8) }
                val dict = m.long(11)
                val data = m.long(9) ?: error("column without data")
                val start = if (dict != null && dict > 0 && dict < data) dict else data
                val stats = m.struct(12)
                val (def, rep) = levels[path] ?: error("column $path isn't in the schema")
                ParquetColumn(path, m.int(1) ?: 0, m.int(4) ?: 0, start, m.long(7) ?: 0, m.long(5) ?: 0, def, rep,
                    stats?.bytes(6) ?: stats?.bytes(2), stats?.bytes(5) ?: stats?.bytes(1))
            }
            ParquetRowGroup(rg.long(3) ?: 0, cols.associateBy { it.path })
        }
    }

    /**
     * The values of column [path] in row group [rg], one per row. Strings for text, Double, Long, Int or Float for
     * numbers, null when empty. A list column (like "tags.list.element") gives a List per row.
     */
    fun read(rg: Int, path: String): List<Any?> {
        val c = rowGroups[rg].columns[path] ?: error("no column $path")
        require(c.length in 1..Int.MAX_VALUE) { "column $path is too big" }
        return readColumn(c, src.read(c.start, c.length.toInt()))
    }

    companion object {
        /** Max definition and repetition level per leaf column path. */
        private fun schemaLevels(schema: List<TStruct>): Map<String, Pair<Int, Int>> {
            val out = HashMap<String, Pair<Int, Int>>()
            var i = 1 // 0 is the root
            fun walk(prefix: String, def: Int, rep: Int, count: Int) {
                repeat(count) {
                    val e = schema[i++]
                    val name = String(e.bytes(4) ?: ByteArray(0), Charsets.UTF_8)
                    val r = e.int(3) ?: 0
                    val d2 = def + if (r != 0) 1 else 0
                    val r2 = rep + if (r == 2) 1 else 0
                    val path = if (prefix.isEmpty()) name else "$prefix.$name"
                    val kids = e.int(5) ?: 0
                    if (kids > 0) walk(path, d2, r2, kids) else out[path] = d2 to r2
                }
            }
            walk("", 0, 0, schema.firstOrNull()?.int(5) ?: 0)
            return out
        }

        internal fun readColumn(c: ParquetColumn, chunk: ByteArray): List<Any?> {
            require(c.maxRep <= 1) { "nested lists aren't supported" }
            var pos = 0
            var dict: List<Any?>? = null
            val rows = ArrayList<Any?>()
            var current: MutableList<Any?>? = null
            var seen = 0L
            while (seen < c.numValues && pos < chunk.size) {
                val t = Thrift(chunk, pos)
                val h = t.struct()
                pos = t.pos
                val type = h.int(1) ?: error("bad page")
                val compressed = h.int(3) ?: error("bad page")
                val uncompressed = h.int(2) ?: error("bad page")
                val body = chunk.copyOfRange(pos, pos + compressed)
                pos += compressed
                when (type) {
                    2 -> { // dictionary
                        val dh = h.struct(7) ?: error("bad dictionary page")
                        val data = decompress(c.codec, body, uncompressed)
                        dict = plain(c.type, data, 0, dh.int(1) ?: 0).first
                    }
                    0, 3 -> {
                        val n: Int; val enc: Int; val levels: ByteArray; var lp = 0; val values: ByteArray; var vp: Int
                        val rep: IntArray; val def: IntArray
                        if (type == 0) {
                            val dh = h.struct(5) ?: error("bad data page")
                            n = dh.int(1) ?: 0; enc = dh.int(2) ?: 0
                            levels = decompress(c.codec, body, uncompressed)
                            rep = if (c.maxRep > 0) { val l = le32(levels, lp); lp += 4; hybrid(levels, lp, bits(c.maxRep), n).also { lp += l } } else IntArray(0)
                            def = if (c.maxDef > 0) { val l = le32(levels, lp); lp += 4; hybrid(levels, lp, bits(c.maxDef), n).also { lp += l } } else IntArray(0)
                            values = levels; vp = lp
                        } else {
                            val dh = h.struct(8) ?: error("bad data page")
                            n = dh.int(1) ?: 0; enc = dh.int(4) ?: 0
                            val defLen = dh.int(5) ?: 0; val repLen = dh.int(6) ?: 0
                            rep = if (c.maxRep > 0) hybrid(body, 0, bits(c.maxRep), n) else IntArray(0)
                            def = if (c.maxDef > 0) hybrid(body, repLen, bits(c.maxDef), n) else IntArray(0)
                            val rest = body.copyOfRange(repLen + defLen, body.size)
                            val isCompressed = dh.bool(7) ?: true
                            values = if (isCompressed) decompress(c.codec, rest, uncompressed - repLen - defLen) else rest
                            vp = 0
                        }
                        val present = if (c.maxDef == 0) n else def.count { it == c.maxDef }
                        val vals: List<Any?> = when (enc) {
                            0 -> plain(c.type, values, vp, present).first
                            2, 8 -> {
                                val d = dict ?: error("dictionary values without a dictionary")
                                if (present == 0) emptyList() else {
                                    val w = values[vp].toInt() and 0xff
                                    hybrid(values, vp + 1, w, present).map { d[it] }
                                }
                            }
                            else -> error("Parquet encoding $enc isn't supported")
                        }
                        var vi = 0
                        for (k in 0 until n) {
                            val d = if (c.maxDef == 0) 0 else def[k]
                            val v = if (c.maxDef == 0 || d == c.maxDef) vals[vi++] else null
                            if (c.maxRep == 0) rows.add(v)
                            else {
                                if (rep[k] == 0) { current = ArrayList(); rows.add(current) }
                                // Below maxDef - 1 the list itself is null or empty: no element.
                                if (d == c.maxDef) current!! += v else if (d == c.maxDef - 1) current!! += null
                            }
                        }
                        seen += n
                    }
                    else -> {} // index pages: skip
                }
            }
            return rows
        }

        private fun bits(max: Int) = 32 - Integer.numberOfLeadingZeros(max)
        private fun le32(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int

        /** The RLE / bit-packed hybrid encoding: [n] values of [width] bits starting at [start]. */
        internal fun hybrid(b: ByteArray, start: Int, width: Int, n: Int): IntArray {
            val out = IntArray(n)
            if (width == 0) return out
            var p = start; var k = 0
            val byteWidth = (width + 7) / 8
            while (k < n) {
                var header = 0L; var shift = 0
                while (true) { val x = b[p++].toInt() and 0xff; header = header or ((x and 0x7f).toLong() shl shift); if (x < 0x80) break; shift += 7 }
                if (header and 1L == 0L) {
                    val count = (header ushr 1).toInt()
                    var v = 0
                    for (i in 0 until byteWidth) v = v or ((b[p + i].toInt() and 0xff) shl (8 * i))
                    p += byteWidth
                    repeat(minOf(count, n - k)) { out[k++] = v }
                } else {
                    val count = (header ushr 1).toInt() * 8
                    var bit = 0L
                    val base = p
                    for (i in 0 until count) {
                        var v = 0
                        for (j in 0 until width) {
                            val at = bit + j
                            if ((b[base + (at ushr 3).toInt()].toInt() ushr (at and 7).toInt()) and 1 == 1) v = v or (1 shl j)
                        }
                        bit += width
                        if (k < n) out[k++] = v
                    }
                    p = base + count / 8 * width
                }
            }
            return out
        }

        /** [n] PLAIN values of physical [type] from [start]; returns them and the end position. */
        private fun plain(type: Int, b: ByteArray, start: Int, n: Int): Pair<List<Any?>, Int> {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            var p = start
            val out = ArrayList<Any?>(n)
            repeat(n) {
                out += when (type) {
                    0 -> ((b[start + it / 8].toInt() ushr (it % 8)) and 1 == 1)
                    1 -> bb.getInt(p).also { p += 4 }
                    2 -> bb.getLong(p).also { p += 8 }
                    4 -> bb.getFloat(p).also { p += 4 }
                    5 -> bb.getDouble(p).also { p += 8 }
                    6 -> { val l = bb.getInt(p); p += 4; String(b, p, l, Charsets.UTF_8).also { p += l } }
                    else -> error("Parquet type $type isn't supported")
                }
            }
            return out to p
        }

        private fun decompress(codec: Int, b: ByteArray, size: Int): ByteArray = when (codec) {
            0 -> b
            1 -> snappy(b, size)
            2 -> java.util.zip.GZIPInputStream(b.inputStream()).use { it.readBytes() }
            6 -> (ParquetCodecs.zstd ?: error("Zstandard isn't available in this build"))(b, size)
            else -> error("Parquet compression $codec isn't supported")
        }

        /** Snappy raw format (github.com/google/snappy/blob/main/format_description.txt). */
        internal fun snappy(b: ByteArray, expected: Int): ByteArray {
            var p = 0; var len = 0; var shift = 0
            while (true) { val x = b[p++].toInt() and 0xff; len = len or ((x and 0x7f) shl shift); if (x < 0x80) break; shift += 7 }
            require(len == expected || expected < 0) { "damaged Snappy data" }
            val out = ByteArray(len); var o = 0
            while (p < b.size) {
                val tag = b[p++].toInt() and 0xff
                when (tag and 3) {
                    0 -> {
                        var n = tag ushr 2
                        if (n >= 60) { val k = n - 59; n = 0; for (i in 0 until k) n = n or ((b[p + i].toInt() and 0xff) shl (8 * i)); p += k }
                        n += 1
                        System.arraycopy(b, p, out, o, n); p += n; o += n
                    }
                    else -> {
                        val n: Int; val off: Int
                        when (tag and 3) {
                            1 -> { n = ((tag ushr 2) and 7) + 4; off = ((tag ushr 5) shl 8) or (b[p++].toInt() and 0xff) }
                            2 -> { n = (tag ushr 2) + 1; off = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8); p += 2 }
                            else -> { n = (tag ushr 2) + 1; off = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int; p += 4 }
                        }
                        require(off in 1..o) { "damaged Snappy data" }
                        for (i in 0 until n) { out[o] = out[o - off]; o++ }
                    }
                }
            }
            require(o == len) { "damaged Snappy data" }
            return out
        }
    }
}

/** A Thrift struct read with the compact protocol: field id → value. */
internal class TStruct(val fields: Map<Int, Any>) {
    fun long(id: Int) = fields[id] as? Long
    fun int(id: Int) = (fields[id] as? Long)?.toInt()
    fun bool(id: Int) = fields[id] as? Boolean
    fun bytes(id: Int) = fields[id] as? ByteArray
    fun struct(id: Int) = fields[id] as? TStruct
    fun list(id: Int) = fields[id] as? List<*> ?: emptyList<Any>()
}

/** Thrift compact protocol, the encoding of Parquet's metadata. */
internal class Thrift(private val b: ByteArray, var pos: Int = 0) {
    private fun byte() = b[pos++].toInt() and 0xff
    private fun varint(): Long {
        var r = 0L; var s = 0
        while (true) { val x = byte(); r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 }
    }
    private fun zigzag(): Long { val v = varint(); return (v ushr 1) xor -(v and 1) }

    fun struct(): TStruct {
        val f = HashMap<Int, Any>()
        var last = 0
        while (true) {
            val h = byte()
            if (h == 0) return TStruct(f)
            val delta = h ushr 4
            val id = if (delta == 0) zigzag().toInt() else last + delta
            last = id
            f[id] = value(h and 0x0f)
        }
    }

    private fun value(type: Int): Any = when (type) {
        1 -> true
        2 -> false
        3 -> b[pos++].toLong()
        4, 5, 6 -> zigzag()
        7 -> ByteBuffer.wrap(b, pos, 8).order(ByteOrder.LITTLE_ENDIAN).double.also { pos += 8 }
        8 -> { val n = varint().toInt(); b.copyOfRange(pos, pos + n).also { pos += n } }
        9, 10 -> {
            val h = byte()
            val n = if (h ushr 4 == 15) varint().toInt() else h ushr 4
            val et = h and 0x0f
            List(n) { if (et == 1 || et == 2) byte() == 1 else value(et) }
        }
        11 -> {
            val n = varint().toInt()
            if (n == 0) emptyMap<Any, Any>() else {
                val kv = byte()
                (0 until n).associate { value(kv ushr 4) to value(kv and 0x0f) }
            }
        }
        12 -> struct()
        else -> error("damaged Parquet metadata")
    }
}

package app.mapstotom.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Garmin POI files (.gpi), as made by Garmin POI Loader and sites like iOverlander or the camper
 * POI packs. Encrypted GPIs (some paid map add-ons) can't be read.
 *
 * Layout: a tree of records, each `u16 type, u16 flags, u32 size`, plus `u32 mainSize` when
 * flags has 0x8; then the record's own data (mainSize bytes) and its child records. Groups (9)
 * hold areas (8) inside their own data and categories (7) and bitmaps (5) as children. Areas nest
 * and hold places (2). A place has lat/lon in "semicircles" and points to its category (6).
 */
class GpiFile(val title: String, val categories: List<GpiCategory>)

class GpiCategory(val group: String, val name: String, val icon: GpiIcon?, val points: List<Point>)

/** An icon as ARGB pixels, top row first; alpha 0 = transparent. */
class GpiIcon(val width: Int, val height: Int, val argb: IntArray)

fun readGpi(data: ByteArray): GpiFile {
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    require(data.size > 20 && (8..12).any { String(data, it, 6, Charsets.ISO_8859_1) == "GRMREC" }) { "Not a Garmin POI (.gpi) file" }
    var charset: Charset = Charsets.UTF_8
    var title: String? = null

    fun u16(o: Int) = bb.getShort(o).toInt() and 0xffff
    fun s32(o: Int) = bb.getInt(o)
    fun deg(semi: Int) = semi * (180.0 / (1L shl 31))

    /** A list of (language, text) pairs; English preferred. Returns the text and the bytes used. */
    fun lstring(o: Int): Pair<String, Int> {
        val total = s32(o)
        if (total < 0 || o + 4 + total > data.size) return "" to 4
        var p = o + 4; var first: String? = null; var en: String? = null
        while (p + 4 <= o + 4 + total) {
            val lang = String(data, p, 2, Charsets.ISO_8859_1); val n = u16(p + 2)
            if (p + 4 + n > o + 4 + total) break
            val s = String(data, p + 4, n, charset).trim()
            if (first == null) first = s
            if (lang == "EN" && en == null) en = s
            p += 4 + n
        }
        return (en ?: first ?: "") to 4 + total
    }

    class Rec(val type: Int, val main: Int, val mainEnd: Int, val end: Int)

    fun records(start: Int, end: Int): Sequence<Rec> = sequence {
        var o = start
        while (o + 8 <= end) {
            val type = u16(o); val flags = u16(o + 2); val size = s32(o + 4)
            val head = if (flags and 8 != 0) 12 else 8
            val p = o + head
            if (size < 0 || p.toLong() + size > end) break
            val mainSize = if (flags and 8 != 0) s32(o + 8).coerceIn(0, size) else size
            yield(Rec(type, p, p + mainSize, p + size))
            if (type == 0xffff) break
            o = p + size
        }
    }

    class RawCat(val id: Int, val name: String, val bitmaps: List<Int>)
    class Group(val name: String) {
        val cats = mutableListOf<RawCat>()
        val bitmaps = mutableMapOf<Int, GpiIcon>()
        val points = mutableMapOf<Int, MutableList<Point>>() // by category id; -1 = none
    }
    val groups = mutableListOf<Group>()

    fun place(r: Rec, g: Group) {
        if (r.mainEnd - r.main < 15) return
        val lat = deg(s32(r.main)); val lon = deg(s32(r.main + 4))
        val name = lstring(r.main + 11).first
        var cat = -1
        for (c in records(r.mainEnd, r.end)) if (c.type == 6 && c.mainEnd - c.main >= 2) cat = u16(c.main)
        if (lat in -90.0..90.0 && lon in -180.0..180.0) g.points.getOrPut(cat) { mutableListOf() } += Point(name.ifEmpty { pretty(lat, lon) }, lat, lon)
    }

    fun area(start: Int, end: Int, g: Group) {
        for (r in records(start, end)) when (r.type) {
            2 -> place(r, g)
            8 -> area(r.mainEnd, r.end, g)
        }
    }

    fun bitmap(r: Rec): Pair<Int, GpiIcon>? {
        if (r.mainEnd - r.main < 36) return null
        val id = u16(r.main); val h = u16(r.main + 2); val w = u16(r.main + 4); val line = u16(r.main + 6); val bpp = u16(r.main + 8)
        val palCount = s32(r.main + 20); val trans = s32(r.main + 24) and 0xffffff
        if (w !in 1..256 || h !in 1..256 || palCount !in 0..256) return null
        val pal = r.main + 36; val pix = pal + palCount * 4
        if (pix.toLong() + line.toLong() * h > r.mainEnd) return null
        fun rgbAt(o: Int) = (data[o + 2].toInt() and 0xff shl 16) or (data[o + 1].toInt() and 0xff shl 8) or (data[o].toInt() and 0xff)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val row = pix + y * line
            val rgb = when (bpp) {
                8 -> if (palCount > 0) rgbAt(pal + 4 * ((data[row + x].toInt() and 0xff).coerceAtMost(palCount - 1))) else return null
                4 -> if (palCount > 0) rgbAt(pal + 4 * (((data[row + x / 2].toInt() shr (if (x % 2 == 0) 4 else 0)) and 0xf).coerceAtMost(palCount - 1))) else return null
                24 -> rgbAt(row + x * 3)
                32 -> rgbAt(row + x * 4)
                else -> return null
            }
            out[y * w + x] = if (rgb == trans) 0 else (0xff shl 24) or rgb
        }
        return id to GpiIcon(w, h, out)
    }

    fun group(r: Rec) {
        val (name, n) = lstring(r.main)
        val g = Group(name.ifEmpty { "POIs" }).also { groups += it }
        area(r.main + n, r.mainEnd, g)
        for (c in records(r.mainEnd, r.end)) when (c.type) {
            2 -> place(c, g)
            8 -> area(c.mainEnd, c.end, g)
            5 -> bitmap(c)?.let { (id, icon) -> g.bitmaps[id] = icon }
            7 -> if (c.mainEnd - c.main >= 6) {
                val id = u16(c.main); val cname = lstring(c.main + 2).first
                val refs = records(c.mainEnd, c.end).filter { it.type == 4 }
                    .flatMap { b -> (b.main until b.mainEnd - 1 step 2).map { u16(it) } }.toList()
                g.cats += RawCat(id, cname, refs)
            }
        }
    }

    for (r in records(0, data.size)) when (r.type) {
        1 -> {
            if (r.mainEnd - r.main >= 10) {
                val cp = u16(r.main + 8)
                charset = when (cp) {
                    65001, 0 -> Charsets.UTF_8
                    else -> runCatching { Charset.forName("windows-$cp") }.getOrDefault(Charsets.ISO_8859_1)
                }
            }
            for (c in records(r.mainEnd, r.end)) if (c.type == 17 && c.mainEnd - c.main > 12) title = lstring(c.main + 8).first.ifEmpty { null }
        }
        9 -> group(r)
        2 -> place(r, groups.lastOrNull() ?: Group("POIs").also { groups += it })
    }

    val cats = mutableListOf<GpiCategory>()
    for (g in groups) {
        for ((id, pts) in g.points) {
            val c = g.cats.firstOrNull { it.id == id }
            val icon = c?.bitmaps?.mapNotNull { g.bitmaps[it] }?.minByOrNull { kotlin.math.abs(it.width - 22) + (if (it.width < 22) 1 else 0) }
            cats += GpiCategory(g.name, c?.name?.ifEmpty { null } ?: g.name, icon, pts)
        }
    }
    if (cats.isEmpty()) error("No places found. The file may be encrypted (some paid Garmin add-ons are).")
    return GpiFile(title ?: "Garmin POIs", cats.sortedWith(compareBy({ it.group.lowercase() }, { it.name.lowercase() })))
}

/** Rough boxes (south, north, west, east) for the countries the OSM screen uses, to keep a GPI's places to the map's area. */
private val COUNTRY_BOXES: Map<String, List<DoubleArray>> = mapOf(
    "PT" to listOf(doubleArrayOf(36.8, 42.2, -9.6, -6.1), doubleArrayOf(36.8, 39.8, -31.4, -24.9), doubleArrayOf(32.3, 33.2, -17.4, -16.2)),
    // Spain: several boxes so the strip of France north of the Pyrenees and northern Morocco stay out.
    "ES" to listOf(
        doubleArrayOf(36.0, 42.35, -9.4, 3.4), doubleArrayOf(42.35, 43.8, -9.4, -1.79), doubleArrayOf(42.35, 43.0, -1.79, -0.7),
        doubleArrayOf(42.35, 42.85, -0.7, 1.45), doubleArrayOf(42.35, 42.5, 1.45, 2.9), doubleArrayOf(42.35, 42.43, 2.9, 3.4), doubleArrayOf(38.6, 40.1, 1.1, 4.4),
        doubleArrayOf(35.86, 35.92, -5.4, -5.27), doubleArrayOf(35.26, 35.32, -2.97, -2.92), doubleArrayOf(27.5, 29.5, -18.2, -13.3),
    ),
    "AD" to listOf(doubleArrayOf(42.4, 42.7, 1.4, 1.8)),
    "GI" to listOf(doubleArrayOf(36.1, 36.2, -5.4, -5.3)),
    "FR" to listOf(doubleArrayOf(41.3, 51.1, -5.2, 9.6)),
    "MC" to listOf(doubleArrayOf(43.7, 43.8, 7.4, 7.5)),
    "BE" to listOf(doubleArrayOf(49.5, 51.5, 2.5, 6.4)),
    "NL" to listOf(doubleArrayOf(50.7, 53.6, 3.3, 7.3)),
    "LU" to listOf(doubleArrayOf(49.4, 50.2, 5.7, 6.6)),
    "IT" to listOf(doubleArrayOf(35.4, 47.1, 6.6, 18.6)),
    "SM" to listOf(doubleArrayOf(43.9, 44.0, 12.4, 12.6)),
    "VA" to listOf(doubleArrayOf(41.9, 41.91, 12.44, 12.46)),
    "DE" to listOf(doubleArrayOf(47.2, 55.1, 5.8, 15.1)),
    "AT" to listOf(doubleArrayOf(46.3, 49.1, 9.5, 17.2)),
    "CH" to listOf(doubleArrayOf(45.8, 47.9, 5.9, 10.5)),
    "GB" to listOf(doubleArrayOf(49.8, 60.9, -8.7, 1.8)),
    "IE" to listOf(doubleArrayOf(51.4, 55.4, -10.7, -5.9)),
)

/** A filter for these countries, or null when none of them has a known box (then keep everything). */
fun areaFilter(countries: List<String>): ((Point) -> Boolean)? {
    val boxes = countries.mapNotNull { COUNTRY_BOXES[it.trim().uppercase()] }.flatten()
    if (boxes.isEmpty()) return null
    return { p -> boxes.any { b -> p.lat in b[0]..b[1] && p.lon in b[2]..b[3] } }
}

/** A GPI icon as a 22x22 TomTom .bmp (transparent → magenta); null to use a plain icon. */
fun gpiIconBmp(icon: GpiIcon?, size: Int = 22): ByteArray? {
    icon ?: return null
    val px = IntArray(size * size) { i ->
        val x = (i % size) * icon.width / size; val y = (i / size) * icon.height / size
        val c = icon.argb[y * icon.width + x]
        if (c ushr 24 < 128) 0xFF00FF else c and 0xffffff
    }
    return bmp24(size, size, px)
}

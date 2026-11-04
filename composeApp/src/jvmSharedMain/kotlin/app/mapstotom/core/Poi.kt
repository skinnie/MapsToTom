package app.mapstotom.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot

/** One place in an .ov2 file. [raw] keeps an untouched extended (type 3) record as it was. */
class Ov2Entry(val name: String, val lat: Double, val lon: Double, internal val raw: ByteArray? = null) {
    fun toPoint() = Point(name, lat, lon)
}

/**
 * Contents of an .ov2 file. [other] keeps records this app doesn't edit (unknown types) so a
 * rewrite doesn't lose them; skippers (bounding boxes) are dropped on rewrite. [heads] are type-100
 * records, which the TomTom writes at the start of its own PA files; they are written back first, as
 * they were. [complete] is false when reading stopped at a record that doesn't parse: such files are
 * shown but not edited.
 */
class Ov2Contents(val entries: List<Ov2Entry>, val other: List<ByteArray>, val complete: Boolean, val heads: List<ByteArray> = emptyList())

fun readOv2Contents(data: ByteArray): Ov2Contents {
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val out = mutableListOf<Ov2Entry>()
    val other = mutableListOf<ByteArray>()
    val heads = mutableListOf<ByteArray>()
    var i = 0
    while (i < data.size) {
        if (i + 5 > data.size) return Ov2Contents(out, other, false, heads)
        val type = data[i].toInt() and 0xff
        val size = if (type == 1) 21 else bb.getInt(i + 1)
        if (size < 5 || i.toLong() + size > data.size) return Ov2Contents(out, other, false, heads)
        when (type) {
            0, 1 -> {} // deleted record, skipper
            100 -> heads += data.copyOfRange(i, i + size)
            2, 3 -> {
                if (size < 14) return Ov2Contents(out, other, false, heads)
                var end = i + 13
                while (end < i + size && data[end] != 0.toByte()) end++
                out += Ov2Entry(String(data, i + 13, end - (i + 13), CP1252), bb.getInt(i + 9) / 1e5, bb.getInt(i + 5) / 1e5,
                    if (type == 3) data.copyOfRange(i, i + size) else null)
            }
            else -> other += data.copyOfRange(i, i + size)
        }
        i += size
    }
    return Ov2Contents(out, other, true, heads)
}

/**
 * The places in an .ov2 file, or null when it can't be read as plain OV2, e.g. TomTom's
 * encrypted safety camera files (those come with a .dct signature file).
 */
fun parseOv2(data: ByteArray): List<Ov2Entry>? {
    val c = readOv2Contents(data)
    return if (c.entries.isEmpty() && (!c.complete || c.other.isNotEmpty())) null else c.entries
}

/** A plain .ov2 file: type-2 records, extended records and unknown ones kept as they were. */
fun buildOv2(entries: List<Ov2Entry>, other: List<ByteArray> = emptyList(), heads: List<ByteArray> = emptyList()): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (h in heads) out.write(h)
    for (e in entries) out.write(e.raw ?: ov2Record(e.toPoint()))
    for (o in other) out.write(o)
    return out.toByteArray()
}

/**
 * Change one category: [edit] gets the current places and returns the new list.
 * The first edit keeps the original as <name>.ov2.mapstotom-backup (the TomTom ignores it).
 */
fun editPoiFile(fs: DeviceFs, path: String, edit: (List<Ov2Entry>) -> List<Ov2Entry>): Int {
    val data = fs.read(path) ?: error("$path is gone")
    val c = readOv2Contents(data)
    require(c.complete && fs.list(parentOf(path)).none { it.equals(nameOf(path) + ".dct", true) }) {
        "${nameOf(path)} isn't plain OV2, so it can't be edited safely"
    }
    val backup = "$path.mapstotom-backup"
    if (!fs.exists(backup)) fs.write(backup, data)
    val changed = edit(c.entries)
    fs.write(path, buildOv2(changed, c.other, c.heads))
    return changed.size
}

fun sameEntry(a: Ov2Entry, b: Ov2Entry) = a.name == b.name && Math.rint(a.lat * 1e5) == Math.rint(b.lat * 1e5) && Math.rint(a.lon * 1e5) == Math.rint(b.lon * 1e5)

/** Replace a whole category with these places, plus its icon and optional warning sound (all in the map folder). */
fun writePoiCategory(fs: DeviceFs, mapDir: String, category: String, points: List<Point>, icon: ByteArray, sound: ByteArray? = null): String {
    val base = join(mapDir, safeFilename(category))
    val out = java.io.ByteArrayOutputStream()
    points.forEach { out.write(ov2Record(it)) }
    fs.write("$base.ov2", out.toByteArray())
    fs.write("$base.bmp", icon)
    if (sound != null) fs.write("$base.ogg", sound)
    return "$base.ov2"
}

// ------------------------------------------------------------------ icons

/** 5x7 glyphs for category icons. */
private val GLYPHS = mapOf(
    'F' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    'C' to listOf("01110", "10001", "10000", "10000", "10000", "10001", "01110"),
    'M' to listOf("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    'S' to listOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    'P' to listOf("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    'L' to listOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    'G' to listOf("01110", "10001", "10000", "10111", "10001", "10001", "01111"),
    '+' to listOf("00000", "00100", "00100", "11111", "00100", "00100", "00000"),
    '!' to listOf("00100", "00100", "00100", "00100", "00100", "00000", "00100"),
    'B' to listOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    'T' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    'W' to listOf("10001", "10001", "10001", "10101", "10101", "11011", "10001"),
    'V' to listOf("10001", "10001", "10001", "10001", "01010", "01010", "00100"),
    '~' to listOf("00000", "01001", "10110", "00000", "01001", "10110", "00000"),
)

/**
 * A 22x22 24-bit BMP: a coloured disc with a white letter (magenta = transparent on the TomTom).
 * [rgb] is 0xRRGGBB.
 */
fun categoryIconBmp(rgb: Int, glyph: Char, size: Int = 22): ByteArray {
    val g = GLYPHS[glyph] ?: return poiIconBmp(size)
    val c = (size - 1) / 2.0
    val scale = 2
    val gx0 = (size - 5 * scale) / 2; val gy0 = (size - 7 * scale) / 2
    val px = IntArray(size * size) { i ->
        val x = i % size; val y = i / size
        val d = hypot(x - c, y - c)
        val gx = (x - gx0) / scale; val gy = (y - gy0) / scale
        val ink = x >= gx0 && y >= gy0 && gx in 0..4 && gy in 0..6 && g[gy][gx] == '1'
        when {
            d > c -> 0xFF00FF
            ink -> 0xFFFFFF
            d > c - 1 -> (rgb shr 1) and 0x7F7F7F
            else -> rgb
        }
    }
    return bmp24(size, size, px)
}

/** A 24-bit BMP from 0xRRGGBB pixels, top row first. */
fun bmp24(w: Int, h: Int, rgb: IntArray): ByteArray {
    val rowBytes = (w * 3 + 3) and 3.inv()
    val pix = ByteArray(rowBytes * h)
    for (y in 0 until h) for (x in 0 until w) {
        val c = rgb[y * w + x]; val o = (h - 1 - y) * rowBytes + x * 3 // BMP rows are bottom-up
        pix[o] = c.toByte(); pix[o + 1] = (c shr 8).toByte(); pix[o + 2] = (c shr 16).toByte()
    }
    return ByteBuffer.allocate(54 + pix.size).order(ByteOrder.LITTLE_ENDIAN)
        .put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + pix.size).putShort(0).putShort(0).putInt(54)
        .putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(pix.size)
        .putInt(2835).putInt(2835).putInt(0).putInt(0).put(pix).array()
}

// ------------------------------------------------------------------ overlap with the map's own POIs

/**
 * The built-in TomTom map category that a POI file most likely repeats, judged by its name
 * (e.g. "Gasolina_Galp" → "Petrol station"). Null when the map has nothing similar, like LPG
 * stations, motorhome areas or beaches. A guess for the user, never used to delete anything.
 */
fun builtInEquivalent(category: String): String? {
    val n = category.lowercase().replace('_', ' ')
    val rules = listOf(
        listOf("gpl", "lpg", "autogas", "autocaravan", "motorhome", "caravan", "naturis", "nudis", "praia", "playa", "beach",
            "speed", "radar", "safety cam", "camera", "descaracteriz", "barrage", "osm ", "google", "bicing", "ev charg") to null,
        listOf("gasolin", "fuel", "galp", "repsol", "cepsa", "prio", "bp ", "eess", "bomba", "petrol") to "Petrol station",
        listOf("hotel", "hoteis", "pousada", "dormida", "pens") to "Hotel/motel",
        listOf("mcdonald", "restaura", "burger") to "Restaurant",
        listOf("farmac", "pharma") to "Pharmacy",
        listOf("hospita", "saude", "sa de", "clinic", "cl nica") to "Hospital/polyclinic",
        listOf("multibanco", "atm", "caixa") to "Cash dispenser",
        listOf("policia", "police") to "Police station",
        listOf("aeropu", "aeroport", "airport") to "Airport",
        listOf("correios", "ctt") to "Post office",
        listOf("campismo", "camping", "camp site") to "Campground",
        listOf("concession", "fiat", "alfaromeo", "dealer") to "Car dealer",
        listOf("oficina", "repair") to "Car repair facility",
        listOf("caminhos de ferro", "comboio", "railway") to "Railway station",
        listOf("area descanso", "area de servi", "areaservico", "rest area") to "Rest area",
        listOf("continente", "jumbo", "lidl", "minipreco", "pingo doce", "pd -", "supermerc", "hiper", "decathlon") to "Shop",
    )
    for ((keys, cat) in rules) if (keys.any { it in n }) return cat
    return null
}

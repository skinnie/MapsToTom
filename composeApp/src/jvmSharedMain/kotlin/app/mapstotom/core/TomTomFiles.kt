package app.mapstotom.core

import app.mapstotom.core.Point.Kind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import kotlin.math.hypot
import kotlin.math.roundToLong

val CP1252: Charset = Charset.forName("windows-1252")

/** Safe limit for older TomTom itineraries (newer models allow 255). */
const val ITN_MAX = 48

private fun r5(v: Double) = Math.rint(v * 100000).roundToLong()

fun safeName(name: String): String = name.replace(Regex("[|\r\n]"), " ").trim().take(60).ifEmpty { "Point" }

fun safeFilename(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim(' ', '.').take(40).ifEmpty { "route" }

/** TomTom itinerary: lon|lat|name|flag| with coordinates x100000.
 *  Flags: 4 departure (first line only), 2 destination/stopover, 0 pass-through waypoint. */
fun buildItn(points: List<Point>, firstIsDeparture: Boolean = true): ByteArray {
    val sb = StringBuilder()
    points.forEachIndexed { i, p ->
        val flag = when {
            i == 0 && firstIsDeparture -> 4
            p.kind == Kind.STOP || i == points.size - 1 -> 2
            else -> 0
        }
        sb.append("${r5(p.lon)}|${r5(p.lat)}|${safeName(p.name)}|$flag|\r\n")
    }
    return sb.toString().toByteArray(CP1252)
}

fun readItn(data: ByteArray): List<Point> = data.toString(CP1252).lines().mapNotNull { line ->
    val f = line.split('|')
    if (f.size < 4 || f[0].trimStart('+', '-').let { it.isEmpty() || !it.all(Char::isDigit) }) return@mapNotNull null
    val lon = f[0].toLongOrNull() ?: return@mapNotNull null
    val lat = f[1].toLongOrNull() ?: return@mapNotNull null
    val flag = f[3].trim().toIntOrNull() ?: 2
    Point(f[2], lat / 100000.0, lon / 100000.0, if (flag in 2..4) Kind.STOP else Kind.VIA)
}

/** One type-2 POI record: type, total size, lon, lat (x100000, little endian), zero-terminated name. */
fun ov2Record(p: Point): ByteArray {
    val name = safeName(p.name).toByteArray(CP1252) + 0
    return ByteBuffer.allocate(13 + name.size).order(ByteOrder.LITTLE_ENDIAN)
        .put(2).putInt(13 + name.size).putInt(r5(p.lon).toInt()).putInt(r5(p.lat).toInt()).put(name).array()
}

fun readOv2(data: ByteArray): List<Point> {
    val pts = mutableListOf<Point>()
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    var i = 0
    while (i + 5 <= data.size) {
        val typ = data[i].toInt() and 0xff
        var size = bb.getInt(i + 1)
        if (size < 5 || i.toLong() + size > data.size) break
        if ((typ == 2 || typ == 3) && size >= 14) {
            val lon = bb.getInt(i + 5); val lat = bb.getInt(i + 9)
            var end = i + 13
            while (end < i + size && data[end] != 0.toByte()) end++
            pts += Point(String(data, i + 13, end - (i + 13), CP1252), lat / 100000.0, lon / 100000.0)
        } else if (typ == 1) {
            size = 21 // skipper record: fixed 21 bytes
        }
        i += size
    }
    return pts
}

/** A small red pin as a 24-bit BMP (the TomTom shows it next to the POI category). Magenta = transparent. */
fun poiIconBmp(size: Int = 22): ByteArray {
    val rowBytes = (size * 3 + 3) and 3.inv()
    val px = java.io.ByteArrayOutputStream()
    val c = (size - 1) / 2.0
    for (y in size - 1 downTo 0) { // BMP rows are bottom-up
        var n = 0
        for (x in 0 until size) {
            val d = hypot(x - c, y - c)
            val bgr = when {
                d <= c - 1 -> if (d > 3) intArrayOf(40, 40, 220) else intArrayOf(255, 255, 255)
                d <= c -> intArrayOf(20, 20, 120)
                else -> intArrayOf(255, 0, 255)
            }
            bgr.forEach { px.write(it) }; n += 3
        }
        repeat(rowBytes - n) { px.write(0) }
    }
    val pix = px.toByteArray()
    return ByteBuffer.allocate(54 + pix.size).order(ByteOrder.LITTLE_ENDIAN)
        .put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + pix.size).putShort(0).putShort(0).putInt(54)
        .putInt(40).putInt(size).putInt(size).putShort(1).putShort(24).putInt(0).putInt(pix.size)
        .putInt(2835).putInt(2835).putInt(0).putInt(0).put(pix).array()
}

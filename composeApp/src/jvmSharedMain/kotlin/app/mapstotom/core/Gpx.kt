package app.mapstotom.core

import app.mapstotom.core.Point.Kind
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlin.math.hypot

/** What a GPX file holds: its name, the route or track line, and loose waypoints. */
class GpxData(val name: String?, val line: List<Point>, val waypoints: List<Point>)

fun readGpx(data: ByteArray): GpxData {
    val f = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }
    val doc = f.newDocumentBuilder().parse(data.inputStream())
    fun Element.kids(tag: String) = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        .filter { it.tagName.substringAfter(':') == tag }
    fun all(tag: String) = doc.getElementsByTagName("*").let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
        .filter { it.tagName.substringAfter(':') == tag }
    fun Element.text(tag: String) = kids(tag).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    fun Element.pt(fallback: String): Point? {
        val lat = getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = getAttribute("lon").toDoubleOrNull() ?: return null
        return Point(text("name") ?: fallback, lat, lon)
    }
    val root = doc.documentElement
    val name = root.kids("metadata").firstOrNull()?.text("name") ?: all("trk").firstOrNull()?.text("name") ?: all("rte").firstOrNull()?.text("name")
    // A planned route (rtept) is preferred over a recorded track (trkpt).
    val route = all("rtept").mapIndexedNotNull { i, e -> e.pt("Via ${i + 1}") }
    val track = all("trkpt").mapIndexedNotNull { i, e -> e.pt("Via ${i + 1}") }
    val wpts = all("wpt").mapIndexedNotNull { i, e -> e.pt("Waypoint ${i + 1}") }
    return GpxData(name, route.ifEmpty { track }, wpts)
}

/**
 * Keep at most [max] points of a line, start and end included, choosing the ones that bend it most
 * (Ramer-Douglas-Peucker with the tolerance searched to hit the budget).
 */
fun thinLine(line: List<Point>, max: Int): List<Point> {
    if (line.size <= max) return line
    val lat0 = Math.toRadians(line.map { it.lat }.average())
    val xs = line.map { it.lon * cos(lat0) }; val ys = line.map { it.lat }
    fun keep(eps: Double): BooleanArray {
        val k = BooleanArray(line.size); k[0] = true; k[line.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to line.size - 1) }
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var best = -1; var bestD = eps
            val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]; val len = hypot(dx, dy)
            for (i in a + 1 until b) {
                val d = if (len == 0.0) hypot(xs[i] - xs[a], ys[i] - ys[a]) else kotlin.math.abs(dy * xs[i] - dx * ys[i] + xs[b] * ys[a] - ys[b] * xs[a]) / len
                if (d > bestD) { bestD = d; best = i }
            }
            if (best > 0) { k[best] = true; stack.add(a to best); stack.add(best to b) }
        }
        return k
    }
    var lo = 0.0; var hi = 1.0
    while (keep(hi).count { it } > max) hi *= 2
    repeat(40) { val mid = (lo + hi) / 2; if (keep(mid).count { it } > max) lo = mid else hi = mid }
    val k = keep(hi)
    return line.filterIndexed { i, _ -> k[i] }
}

/**
 * Points for the Send list: start and finish as stops, the shape points in between as
 * pass-through points (TomTom follows them without announcing them). Loose waypoints only
 * become stops when the file has no route or track.
 */
fun gpxToPoints(g: GpxData, max: Int = ITN_MAX): List<Point> {
    if (g.line.size < 2) return g.waypoints.take(max).map { it.copy(kind = Kind.STOP) }
    val thin = thinLine(g.line, max)
    val last = thin.lastIndex
    return thin.mapIndexed { i, p ->
        when (i) {
            0 -> p.copy(name = if (p.name.startsWith("Via ")) "Start" else p.name, kind = Kind.STOP)
            last -> p.copy(name = if (p.name.startsWith("Via ")) (g.name ?: "Finish") else p.name, kind = Kind.STOP)
            else -> p.copy(name = "Via $i", kind = Kind.VIA)
        }
    }
}

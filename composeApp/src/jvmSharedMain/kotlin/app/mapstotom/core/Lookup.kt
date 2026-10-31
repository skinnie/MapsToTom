package app.mapstotom.core

import app.mapstotom.core.Point.Kind
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val USER_AGENT = "MapsToTom/1.0 (personal route transfer tool; https://github.com/skinnie/MapsToTom)"

// ------------------------------------------------------------------ OpenStreetMap geocoding

private val geocodeLock = Any()
private var lastGeocode = 0L

/** Look up a place name with OpenStreetMap Nominatim (at most one request per second). */
fun geocode(name: String, net: Net): Pair<Double, Double>? {
    synchronized(geocodeLock) {
        val wait = 1100 - (System.currentTimeMillis() - lastGeocode)
        if (wait > 0) Thread.sleep(wait)
        lastGeocode = System.currentTimeMillis()
    }
    val url = "https://nominatim.openstreetmap.org/search?" +
        urlEncode(listOf("q" to name, "format" to "json", "limit" to "1"))
    val r = net.get(url, mapOf("User-Agent" to USER_AGENT), timeoutMs = 10_000)
    if (r.code != 200) throw java.io.IOException("OpenStreetMap HTTP ${r.code}")
    val res = Json.parse(r.text) as? List<*> ?: return null
    val first = res.firstOrNull() as? Map<*, *> ?: return null
    val lat = (first["lat"] as? String)?.toDoubleOrNull() ?: return null
    val lon = (first["lon"] as? String)?.toDoubleOrNull() ?: return null
    return lat to lon
}

/** True for names that are only coordinates, like "40.09835, -8.50711". */
fun isCoordName(name: String) = Regex("""\s*-?\d{1,3}\.\d+\s*,\s*-?\d{1,3}\.\d+\s*""").matches(name)

/**
 * A readable name for a position, from OpenStreetMap Nominatim reverse lookup:
 * "Place name, Town" when there is a named place, else "Street 12, Town". Null if nothing useful.
 */
fun reverseGeocode(lat: Double, lon: Double, net: Net): String? {
    synchronized(geocodeLock) {
        val wait = 1100 - (System.currentTimeMillis() - lastGeocode)
        if (wait > 0) Thread.sleep(wait)
        lastGeocode = System.currentTimeMillis()
    }
    val url = "https://nominatim.openstreetmap.org/reverse?" + urlEncode(listOf(
        "lat" to "%.6f".format(java.util.Locale.ROOT, lat), "lon" to "%.6f".format(java.util.Locale.ROOT, lon),
        "format" to "jsonv2", "zoom" to "18", "addressdetails" to "1"))
    val r = net.get(url, mapOf("User-Agent" to USER_AGENT), timeoutMs = 10_000)
    if (r.code != 200) return null
    val res = Json.parse(r.text) as? Map<*, *> ?: return null
    return reverseName(res)
}

internal fun reverseName(res: Map<*, *>): String? {
    val a = res["address"] as? Map<*, *> ?: emptyMap<Any, Any>()
    fun f(vararg k: String) = k.firstNotNullOfOrNull { (a[it] as? String)?.takeIf { v -> v.isNotBlank() } }
    val town = f("city", "town", "village", "municipality", "suburb", "county")
    val place = (res["name"] as? String)?.takeIf { it.isNotBlank() }
    val street = f("road", "pedestrian", "footway", "path")?.let { road -> f("house_number")?.let { "$road $it" } ?: road }
    val head = place ?: street ?: f("neighbourhood", "hamlet", "suburb") ?: return town
    return listOfNotNull(head, town?.takeIf { it != head }).joinToString(", ")
}

/** OpenStreetMap lookup that retries without a leading business name: "Restaurante X, Rua Y 79, Town"
 *  fails as a whole, but dropping the first part finds the street. */
fun geocodeSmart(text: String, net: Net): Pair<Double, Double>? {
    val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val tries = (0 until max(1, parts.size - 1)).map { parts.drop(it).joinToString(", ") }
    for (q in tries.take(3)) geocode(q, net)?.let { return it }
    return null
}

// ------------------------------------------------------------------ Google place lookup

/** "Reject all" consent cookie, so Google serves the map page instead of the consent form. */
val GOOGLE_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
    "Cookie" to "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg",
    "Accept-Language" to "en",
)

private fun findLatLon(x: Any?): Pair<Double, Double>? {
    if (x is List<*>) {
        if (x.size == 4 && x[0] == null && x[1] == null && x[2] is Double && x[3] is Double) return x[2] as Double to x[3] as Double
        for (v in x) findLatLon(v)?.let { return it }
    }
    return null
}

/** (name, lat, lon) for a Google Maps link that names a place but has no coordinates.
 *  Phone share links look like maps.google.com?q=<name, address>&ftid=<place id>. Google's map
 *  page for such a link embeds the request it uses for the place details, which hold the pin. */
fun googlePlaceLookup(url: String, net: Net): Triple<String, Double, Double>? {
    val u = Url.parse(url)
    val keep = LinkedHashMap<String, String>()
    for ((k, v) in u.params) if (k in setOf("q", "query", "ftid", "cid", "daddr")) keep[k] = v
    if (keep.isEmpty()) return null
    keep["hl"] = "en"
    val page = net.get("https://www.google.com/maps?" + urlEncode(keep.toList()), GOOGLE_HEADERS).text
    val m = Regex("""href="(/maps/preview/place\?[^"]+)"""").find(page) ?: return null
    val body = net.get("https://www.google.com" + htmlUnescape(m.groupValues[1]), GOOGLE_HEADERS).text
    val data = Json.parse(if (body.startsWith(")]}'")) body.substringAfter('\n') else body)
    val place: Any? = if (data is List<*> && data.size > 6 && data[6] is List<*>) data[6] else data
    var ll: Pair<Double, Double>? = null
    if (place is List<*> && place.size > 9) {
        val c9 = place[9]
        if (c9 is List<*> && c9.size == 4 && c9[2] is Double && c9[3] is Double) ll = c9[2] as Double to c9[3] as Double
    }
    ll = ll ?: findLatLon(place) ?: return null
    val name = if (place is List<*> && place.size > 11 && place[11] is String) place[11] as String else ""
    return Triple(name, ll.first, ll.second)
}

// ------------------------------------------------------------------ shaping points (OSRM)

private fun haversine(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val r = 6371000.0
    val la1 = Math.toRadians(a.first); val lo1 = Math.toRadians(a.second)
    val la2 = Math.toRadians(b.first); val lo2 = Math.toRadians(b.second)
    val h = sin((la2 - la1) / 2).let { it * it } + cos(la1) * cos(la2) * sin((lo2 - lo1) / 2).let { it * it }
    return 2 * r * asin(sqrt(h))
}

/** Driving route line (lat, lon) and leg lengths in metres from the public OSRM demo server (OpenStreetMap data). */
private fun osrmLine(base: List<Point>, net: Net): Pair<List<Pair<Double, Double>>, List<Double>> {
    val coords = base.joinToString(";") { "%.6f,%.6f".format(java.util.Locale.ROOT, it.lon, it.lat) }
    val r = net.get("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson",
        mapOf("User-Agent" to USER_AGENT), timeoutMs = 20_000)
    val res = Json.parse(r.text) as? Map<*, *> ?: error("routing failed")
    if (res["code"] != "Ok") error(res["message"]?.toString() ?: "routing failed")
    val route = (res["routes"] as List<*>)[0] as Map<*, *>
    val line = ((route["geometry"] as Map<*, *>)["coordinates"] as List<*>).map {
        val c = it as List<*>; (c[1] as Number).toDouble() to (c[0] as Number).toDouble()
    }
    val legLen = (route["legs"] as List<*>).map { max(((it as Map<*, *>)["distance"] as Number).toDouble(), 1.0) }
    return line to legLen
}

/** Insert extra pass-through points along the driving route so the TomTom follows the same roads.
 *  Road geometry from OSRM, or from Valhalla when roads must be avoided (both OpenStreetMap data). */
fun addShapingPoints(points: List<Point>, total: Int, net: Net, avoid: Avoid = Avoid()): List<Point> {
    val base = points.filter { it.kind != Kind.SHAPE }
    if (base.size < 2 || total <= base.size) return base
    val (line, legLen) = if (avoid.any) valhallaLine(base, avoid, net) else osrmLine(base, net)
    val totalLen = legLen.sum()
    val extra = total - base.size
    val wpIdx = mutableListOf(0)
    var j = 0
    for (p in base.drop(1)) {
        var best = j; var bestD = Double.MAX_VALUE
        for (k in j until line.size) {
            val d = haversine(line[k], p.lat to p.lon)
            if (d < bestD) { best = k; bestD = d }
        }
        wpIdx += best; j = best
    }
    fun share(li: Int) = Math.rint(extra * legLen[li] / totalLen).roundToInt()
    val out = mutableListOf(base[0])
    for (li in 0 until base.size - 1) {
        val seg = line.subList(wpIdx[li], minOf(wpIdx[li + 1] + 1, line.size))
        val n = max(0, if (li < base.size - 2) share(li) else extra - (0 until base.size - 2).sumOf { share(it) })
        if (n > 0 && seg.size > 2) {
            val cum = mutableListOf(0.0)
            for (k in 1 until seg.size) cum += cum.last() + haversine(seg[k - 1], seg[k])
            val len = cum.last()
            var k = 0
            for (s in 1..n) {
                val target = len * s / (n + 1)
                while (k < cum.size - 1 && cum[k + 1] < target) k++
                val (lat, lon) = seg[minOf(k + 1, seg.size - 1)]
                out += Point("Shape ${li + 1}.$s", lat, lon, Kind.SHAPE)
            }
        }
        out += base[li + 1]
    }
    return out
}

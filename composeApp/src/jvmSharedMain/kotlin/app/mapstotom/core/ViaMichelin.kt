package app.mapstotom.core

import java.util.Locale

/** Roads a route should stay off. When any is set, "Follow the same roads" routes with Valhalla (OSRM can't avoid). */
data class Avoid(val tolls: Boolean = false, val motorways: Boolean = false, val ferries: Boolean = false, val borders: Boolean = false) {
    val any get() = tolls || motorways || ferries || borders
    fun label() = listOfNotNull("tolls".takeIf { tolls }, "motorways".takeIf { motorways },
        "ferries".takeIf { ferries }, "border crossings".takeIf { borders }).joinToString(", ")
}

// ------------------------------------------------------------------ ViaMichelin route links

private val VIAMICHELIN_HOST_RE = Regex("""(^|\.)viamichelin\.[a-z.]+$""")

fun isViaMichelinUrl(url: String) = VIAMICHELIN_HOST_RE.containsMatchIn(Url.parse(url).host)

class LinkRoute(val points: List<Point>, val avoid: Avoid, val warnings: List<String>)

/**
 * A ViaMichelin route link (the address bar of viamichelin.xx/routes/results?...).
 * `itinerary` holds the stops as JSON objects joined by '~':
 * {"l": label, "c": {"lng", "lat"}, "isDeparture"/"isArrival": true}; steps in between have no flag.
 * `tripConstraint` is the route option (NO_TOLL, NO_MOTORWAY, ...). Like Google links, the road shape isn't in it.
 */
fun parseViaMichelinUrl(url: String): LinkRoute {
    val u = Url.parse(url)
    val warnings = mutableListOf<String>()
    val points = mutableListOf<Point>()
    val itinerary = u.first("itinerary")
    if (itinerary == null) {
        warnings += "No route in this ViaMichelin link: open the route on ViaMichelin and copy the address bar."
        return LinkRoute(points, Avoid(), warnings)
    }
    for (obj in jsonObjects(itinerary)) {
        val m = runCatching { Json.parse(obj) as? Map<*, *> }.getOrNull() ?: continue
        val name = (m["l"] as? String)?.trim().orEmpty()
        val c = m["c"] as? Map<*, *>
        val lat = (c?.get("lat") as? Number)?.toDouble() ?: Double.NaN
        val lon = (c?.get("lng") as? Number)?.toDouble() ?: Double.NaN
        if (name.isEmpty() && lat.isNaN()) continue
        points += Point(name.ifEmpty { pretty(lat, lon) }, lat, lon)
    }
    if (points.isEmpty()) warnings += "No stops found in the ViaMichelin link."
    val (avoid, note) = viaMichelinAvoid(u.first("tripConstraint"))
    note?.let { warnings += it }
    val mode = u.first("travelMode")
    if (mode != null && mode != "CAR" && mode != "MOTORCYCLE")
        warnings += "The ViaMichelin route is for ${mode.lowercase(Locale.ROOT)}; the TomTom plans for a car."
    return LinkRoute(points, avoid, warnings)
}

/** ViaMichelin's route options (tripConstraint) as roads to avoid, plus a note for those we can't follow. */
internal fun viaMichelinAvoid(constraint: String?): Pair<Avoid, String?> = when (constraint) {
    null, "", "NONE" -> Avoid() to null
    "NO_TOLL", "NO_TOLL_AND_NO_VIGNETTE" -> Avoid(tolls = true) to null
    "NO_VIGNETTE" -> Avoid(tolls = true) to "Vignette roads are avoided as toll roads."
    "NO_MOTORWAY" -> Avoid(motorways = true) to null
    "NO_FERRY" -> Avoid(ferries = true) to null
    "NO_BORDER_CROSSING" -> Avoid(borders = true) to null
    "NO_MOTORWAY_AND_NO_BORDER_CROSSING" -> Avoid(motorways = true, borders = true) to null
    "NO_LOW_EMISSION_ZONE" -> Avoid() to "Low-emission zones can't be avoided: the TomTom doesn't know them."
    else -> Avoid() to "ViaMichelin route option $constraint isn't known; ignored."
}

/** The top-level {...} objects in [s], skipping braces inside JSON strings (labels may hold '~' or '}'). */
internal fun jsonObjects(s: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0; var start = -1; var inStr = false; var esc = false
    for ((i, ch) in s.withIndex()) {
        if (inStr) {
            if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
            continue
        }
        when (ch) {
            '"' -> inStr = true
            '{' -> { if (depth == 0) start = i; depth++ }
            '}' -> if (depth > 0) { depth--; if (depth == 0 && start >= 0) { out += s.substring(start, i + 1); start = -1 } }
        }
    }
    return out
}

// ------------------------------------------------------------------ Valhalla (routing that can avoid roads)

/** Road line (lat, lon) and leg lengths in metres from the FOSSGIS Valhalla server, avoiding [avoid]. */
internal fun valhallaLine(base: List<Point>, avoid: Avoid, net: Net): Pair<List<Pair<Double, Double>>, List<Double>> {
    val r = net.get("https://valhalla1.openstreetmap.de/route?" + urlEncode(listOf("json" to valhallaRequest(base, avoid))),
        mapOf("User-Agent" to USER_AGENT), timeoutMs = 30_000)
    val res = Json.parse(r.text) as? Map<*, *> ?: error("routing failed")
    val trip = res["trip"] as? Map<*, *> ?: error(res["error"]?.toString() ?: "no route found")
    val line = mutableListOf<Pair<Double, Double>>()
    val legLen = mutableListOf<Double>()
    for (leg in trip["legs"] as List<*>) {
        leg as Map<*, *>
        val pts = decodePolyline6(leg["shape"] as String)
        line += if (line.isEmpty()) pts else pts.drop(1)
        legLen += kotlin.math.max(((leg["summary"] as Map<*, *>)["length"] as Number).toDouble() * 1000, 1.0)
    }
    if (line.size < 2) error("no route found")
    return line to legLen
}

internal fun valhallaRequest(base: List<Point>, avoid: Avoid): String {
    fun f(v: Double) = "%.6f".format(Locale.ROOT, v)
    val locs = base.mapIndexed { i, p ->
        val type = if (p.kind == Point.Kind.VIA && i > 0 && i < base.size - 1) "through" else "break"
        """{"lat":${f(p.lat)},"lon":${f(p.lon)},"type":"$type"}"""
    }
    val opts = listOfNotNull(
        "\"exclude_tolls\":true".takeIf { avoid.tolls },
        "\"use_highways\":0".takeIf { avoid.motorways },
        "\"use_ferry\":0".takeIf { avoid.ferries },
        "\"country_crossing_penalty\":3600".takeIf { avoid.borders },
    )
    return """{"locations":[${locs.joinToString(",")}],"costing":"auto","costing_options":{"auto":{${opts.joinToString(",")}}},""" +
        """"units":"kilometers","directions_type":"none"}"""
}

/** Google's polyline encoding with 6 decimals (Valhalla's shape). */
internal fun decodePolyline6(s: String): List<Pair<Double, Double>> {
    val out = mutableListOf<Pair<Double, Double>>()
    var i = 0; var lat = 0L; var lon = 0L
    fun next(): Long {
        var shift = 0; var result = 0L
        while (true) {
            val b = s[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
    while (i < s.length) {
        lat += next(); lon += next()
        out += lat / 1e6 to lon / 1e6
    }
    return out
}

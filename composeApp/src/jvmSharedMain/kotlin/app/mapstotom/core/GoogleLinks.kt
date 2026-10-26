package app.mapstotom.core

import app.mapstotom.core.Point.Kind

// ------------------------------------------------------------------ coordinates

private const val NUM = """[-+]?\d{1,3}(?:\.\d+)?"""
private val COORD_RE = Regex("""^\s*\(?\s*($NUM)\s*[,;\s]\s*($NUM)\s*\)?\s*$""")
private val DMS_RE = Regex("""(\d{1,3})\s*°\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*(?:"|″|'')\s*([NSEW])""", RegexOption.IGNORE_CASE)

/** (lat, lon) for "38.7, -9.1" style text or DMS like 38°42'05.0"N 9°08'21.6"W. */
fun parseCoords(input: String): Pair<Double, Double>? {
    val text = percentDecode(input, plus = true).trim()
    COORD_RE.matchEntire(text)?.let { m ->
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }
    val parts = DMS_RE.findAll(text).toList()
    if (parts.size == 2) {
        var lat: Double? = null
        var lon: Double? = null
        for (m in parts) {
            val (d, mi, s, h) = m.destructured
            var v = d.toInt() + mi.toInt() / 60.0 + s.toDouble() / 3600
            val hu = h.uppercase()
            if (hu == "S" || hu == "W") v = -v
            if (hu == "N" || hu == "S") lat = v else lon = v
        }
        if (lat != null && lon != null) return lat to lon
    }
    return null
}

// ------------------------------------------------------------------ Google "data=" blob

/** Google's !-encoded protobuf: "4m3" = message holding the next 3 tokens. */
class Node(val f: Int, val t: Char, val v: String, val children: List<Node>)

private val TOKEN_RE = Regex("""(\d+)([a-zA-Z])(.*)""")

private fun parseTokens(tokens: List<String>, start: Int, end: Int): List<Node> {
    val items = mutableListOf<Node>()
    var i = start
    while (i < end && i < tokens.size) {
        val m = TOKEN_RE.matchEntire(tokens[i])
        if (m == null) { i++; continue }
        val f = m.groupValues[1].toInt()
        val t = m.groupValues[2][0]
        val v = m.groupValues[3]
        if (t == 'm') {
            val n = if (v.isNotEmpty() && v.all { it.isDigit() }) v.toInt() else 0
            items += Node(f, t, v, parseTokens(tokens, i + 1, i + 1 + n))
            i += 1 + n
        } else {
            items += Node(f, t, v, emptyList())
            i++
        }
    }
    return items
}

fun parseDataBlob(data: String): List<Node> {
    val tokens = percentDecode(data).split('!').filter { it.isNotEmpty() }
    return parseTokens(tokens, 0, tokens.size)
}

private fun latLonOf(children: List<Node>): Pair<Double, Double>? {
    val vals = HashMap<Int, String>()
    children.filter { it.t == 'd' }.forEach { vals[it.f] = it.v }
    val lat = vals[2]?.toDoubleOrNull() ?: return null // 2d = lat
    val lon = vals[1]?.toDoubleOrNull() ?: return null // 1d = lon
    return lat to lon
}

private fun child(children: List<Node>, f: Int) = children.firstOrNull { it.f == f && it.t == 'm' }?.children

private fun isStop(children: List<Node>): Boolean {
    if (children.isEmpty()) return true // "!1m0" = "Your location"
    val c2 = child(children, 2)
    return (c2 != null && latLonOf(c2) != null) || child(children, 1) != null
}

/** The message whose repeated field-1 entries are the route stops. */
private fun findStopList(tree: List<Node>): List<List<Node>>? {
    for (n in tree) {
        if (n.t != 'm') continue
        val stops = n.children.filter { it.f == 1 && it.t == 'm' }.map { it.children }
        if (stops.isNotEmpty() && stops.all { isStop(it) } && stops.any { child(it, 2) != null }) return stops
        findStopList(n.children)?.let { return it }
    }
    return null
}

// ------------------------------------------------------------------ Google URLs

private val GOOGLE_HOST_RE = Regex("""(^|\.)google\.[a-z.]+$|^maps\.google\.[a-z.]+$""")
val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl", "g.co")

fun isGoogleMapsUrl(url: String): Boolean {
    val u = Url.parse(url)
    return GOOGLE_HOST_RE.containsMatchIn(u.host) && (u.path.startsWith("/maps") || u.host.startsWith("maps."))
}

fun isShortLink(url: String): Boolean {
    val h = Url.parse(url).host
    return h in SHORT_HOSTS || h.endsWith(".goo.gl")
}

/** Follow maps.app.goo.gl redirects by hand, stopping at the first Google Maps URL
 *  (this avoids Google's cookie-consent page, which hides the real link). */
fun resolveShortUrl(start: String, net: Net): String {
    var url = start
    repeat(6) {
        if (isGoogleMapsUrl(url)) return url
        val u = Url.parse(url)
        if (u.host.startsWith("consent.")) u.first("continue")?.let { return it }
        val r = net.get(url, mapOf("User-Agent" to "Mozilla/5.0"), followRedirects = false, timeoutMs = 10_000)
        val loc = r.header("Location")
        if (r.code in listOf(301, 302, 303, 307, 308) && loc != null) {
            url = java.net.URL(java.net.URL(url), loc).toString()
        } else if (r.code >= 400) {
            throw java.io.IOException("HTTP ${r.code}")
        } else {
            return url
        }
    }
    return url
}

class GoogleParse(val points: MutableList<Point>, val unresolved: MutableList<String>, val warnings: MutableList<String>)

/** Points from a Google Maps URL. Points without coordinates are listed in [GoogleParse.unresolved]. */
fun parseGoogleUrl(url: String): GoogleParse {
    val u = Url.parse(url)
    var path = u.path
    var data = ""
    if ("/data=" in path) {
        data = path.substringAfter("/data=")
        path = path.substringBefore("/data=")
    }
    val tree = if (data.isNotEmpty()) parseDataBlob(data) else emptyList()
    val segs = path.split('/')
    val points = mutableListOf<Point>()
    val unresolved = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    fun addNamed(label: String, kind: Kind = Kind.STOP, coords: Pair<Double, Double>? = null) {
        val c = coords ?: parseCoords(label)
        if (c != null) {
            points += Point(if (parseCoords(label) == null) label else pretty(c.first, c.second), c.first, c.second, kind)
        } else if (label.isNotEmpty()) {
            points += Point(label, Double.NaN, Double.NaN, kind)
            unresolved += label
        }
    }
    fun clean(seg: String) = percentDecode(seg, plus = true).trim()

    // 1. /maps/dir/A/B/C/@center/data=...
    val dirIdx = segs.indexOf("dir")
    if (dirIdx >= 0) {
        val labels = mutableListOf<String>()
        for (s in segs.drop(dirIdx + 1)) {
            if (s.startsWith("@")) break
            labels += clean(s)
        }
        while (labels.isNotEmpty() && labels.last().isEmpty()) labels.removeAt(labels.size - 1)
        val stops = findStopList(tree) ?: emptyList()
        for (i in 0 until maxOf(labels.size, stops.size)) {
            var label = labels.getOrElse(i) { "" }
            var coords: Pair<Double, Double>? = null
            val vias = mutableListOf<Pair<Double, Double>>()
            if (i < stops.size) {
                coords = child(stops[i], 2)?.let { latLonOf(it) }
                for (n in stops[i]) {
                    if (n.f == 3 && n.t == 'm') child(n.children, 1)?.let { latLonOf(it) }?.let { vias += it }
                }
            }
            if (label.isEmpty() && coords == null) {
                warnings += "Stop ${i + 1} is 'your location'; the TomTom will start from its GPS position."
            } else {
                if (coords != null && label.isEmpty()) label = pretty(coords.first, coords.second)
                addNamed(label, Kind.STOP, coords)
            }
            for (ll in vias) {
                val nVia = points.count { it.kind == Kind.VIA } + 1
                points += Point("Via $nVia", ll.first, ll.second, Kind.VIA)
            }
        }
    }
    // 2. /maps/dir/?api=1&origin=..&destination=..&waypoints=a|b  and  ?saddr=&daddr=
    if (points.isEmpty() && (u.has("destination") || u.has("daddr"))) {
        val seq = mutableListOf<String>()
        u.first("origin")?.let { seq += it }
        u.first("saddr")?.let { seq += it }
        u.first("waypoints")?.let { seq += it.split('|') }
        u.first("destination")?.let { seq += it }
        u.first("daddr")?.let { seq += it.split(Regex("""\s+to:""")) }
        seq.forEach { addNamed(it.trim()) }
    }
    // 3. /maps/place/NAME/@.../data=...!3d<lat>!4d<lon>
    val placeIdx = segs.indexOf("place")
    if (points.isEmpty() && placeIdx >= 0) {
        val name = if (segs.size > placeIdx + 1) clean(segs[placeIdx + 1]) else ""
        val m = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""").find(percentDecode(data))
        var coords = m?.let { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() } ?: parseCoords(name)
        if (coords == null) {
            coords = atCenter(segs)
            if (coords != null) warnings += "'$name': no exact pin in link, used the map centre."
        }
        if (coords != null) {
            val label = if (name.isNotEmpty() && parseCoords(name) == null) name else pretty(coords.first, coords.second)
            points += Point(label, coords.first, coords.second, Kind.STOP)
        } else if (name.isNotEmpty()) {
            addNamed(name)
        }
    }
    // 4. ?q=, ?query=, ?ll=, ?center=, place IDs, /maps/search/<text>
    if (points.isEmpty()) {
        for (key in listOf("q", "query", "ll", "center")) {
            val v = u.first(key) ?: continue
            addNamed(v.trim()); break
        }
    }
    if (points.isEmpty() && (u.has("cid") || u.has("ftid"))) {
        points += Point("Google place", Double.NaN, Double.NaN, Kind.STOP)
        unresolved += "Google place"
    }
    val searchIdx = segs.indexOf("search")
    if (points.isEmpty() && searchIdx >= 0 && segs.size > searchIdx + 1 && !segs[searchIdx + 1].startsWith("@")) {
        addNamed(clean(segs[searchIdx + 1]))
    }
    // 5. bare map view /maps/@lat,lon,zoom
    if (points.isEmpty()) {
        atCenter(segs)?.let {
            points += Point(pretty(it.first, it.second), it.first, it.second, Kind.STOP)
            warnings += "Link only had a map view; used the map centre."
        }
    }
    return GoogleParse(points, unresolved, warnings)
}

private fun atCenter(segs: List<String>): Pair<Double, Double>? {
    for (s in segs) {
        if (s.startsWith("@")) {
            val parts = s.substring(1).split(',')
            if (parts.size >= 2) return parseCoords(parts[0] + "," + parts[1])
        }
    }
    return null
}

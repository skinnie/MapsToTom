package app.mapstotom.core

private val URL_RE = Regex("""https?://\S+""")

/**
 * Parse pasted text: one or more Google Maps links, short links, coordinates or addresses.
 * With [net] = null nothing goes online (short links and addresses are then skipped with a warning).
 */
fun parseInput(text: String, net: Net?): ParseResult {
    val out = ParseResult()
    for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
        val urls = URL_RE.findAll(line).map { it.value }.toList()
        if (urls.isEmpty()) {
            val c = parseCoords(line)
            when {
                c != null -> out.points += Point(pretty(c.first, c.second), c.first, c.second)
                net != null -> try {
                    val g = geocodeSmart(line, net)
                    if (g != null) out.points += Point(line, g.first, g.second)
                    else out.warnings += "Address not found: '$line'"
                } catch (e: Exception) {
                    out.warnings += "Could not look up '$line': ${e.message}"
                }
                else -> out.warnings += "Not understood (offline): '$line'"
            }
            continue
        }
        for (raw in urls) {
            var url = raw.trimEnd(')', '.', ',', ';', '\'', '"')
            if (isShortLink(url)) {
                if (net == null) { out.warnings += "Short link needs internet: $url"; continue }
                try {
                    url = resolveShortUrl(url, net)
                } catch (e: Exception) {
                    out.warnings += "Could not open short link $url: ${e.message}"; continue
                }
            }
            if (isViaMichelinUrl(url)) {
                val v = parseViaMichelinUrl(url)
                out.warnings += v.warnings
                if (v.avoid.any) out.avoid = v.avoid
                for (pt in v.points) {
                    if (!pt.hasCoords) {
                        val c = if (net == null) null else try {
                            geocodeSmart(pt.name, net)
                        } catch (e: Exception) {
                            out.warnings += "Could not look up '${pt.name}': ${e.message}"; null
                        }
                        if (c == null) { out.warnings += "Skipped '${pt.name}': no coordinates in the link."; continue }
                        pt.lat = c.first; pt.lon = c.second
                    }
                    out.points += pt
                }
                continue
            }
            if (!isGoogleMapsUrl(url)) { out.warnings += "Not a Google Maps or ViaMichelin link: $url"; continue }
            val g = parseGoogleUrl(url)
            out.warnings += g.warnings
            val single = g.points.size == 1
            for (pt in g.points) {
                if (!pt.hasCoords) {
                    var c: Pair<Double, Double>? = null
                    if (net != null && single) {
                        val found = try {
                            googlePlaceLookup(url, net)
                        } catch (e: Exception) {
                            out.warnings += "Google lookup failed (${e.message}); trying OpenStreetMap."; null
                        }
                        if (found != null) {
                            c = found.second to found.third
                            if (found.first.isNotEmpty()) pt.name = found.first
                        }
                    }
                    if (c == null && net != null && pt.name != "Google place") {
                        try {
                            c = geocodeSmart(pt.name, net)
                            if (c != null) out.warnings += "'${pt.name}': position from OpenStreetMap address search, check it."
                        } catch (e: Exception) {
                            out.warnings += "Could not look up '${pt.name}': ${e.message}"
                        }
                    }
                    if (c == null) { out.warnings += "Skipped '${pt.name}': no coordinates in the link."; continue }
                    pt.lat = c.first; pt.lon = c.second
                }
                out.points += pt
            }
            if (g.points.isEmpty()) out.warnings += "No points found in link: ${url.take(80)}..."
        }
    }
    if (net != null) nameCoordinatePoints(out.points, net)
    return out
}

/** Give stops that are only coordinates a readable name (the TomTom shows it in its POI and route lists). */
fun nameCoordinatePoints(points: List<Point>, net: Net, max: Int = 10) {
    points.filter { it.kind == Point.Kind.STOP && it.hasCoords && isCoordName(it.name) }.take(max).forEach { p ->
        runCatching { reverseGeocode(p.lat, p.lon, net) }.getOrNull()?.let { p.name = it }
    }
}

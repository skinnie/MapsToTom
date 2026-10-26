package app.mapstotom.core

/**
 * POI categories built from OpenStreetMap (Overpass API). Data © OpenStreetMap contributors, ODbL.
 * [filter] is an Overpass selector run against the chosen countries.
 */
enum class OsmPoiType(val label: String, val category: String, val filter: String, val rgb: Int, val glyph: Char, val warns: Boolean = false) {
    FUEL("Fuel stations", "OSM Fuel", "nwr[amenity=fuel]", 0xE07000, 'F'),
    EV("EV chargers", "OSM EV chargers", "nwr[amenity=charging_station]", 0x1A8F3C, 'E'),
    MOTORHOME("Motorhome areas", "OSM Motorhome areas", "nwr[tourism=caravan_site]", 0x1F5FBF, 'M'),
    CAMPSITE("Campsites", "OSM Campsites", "nwr[tourism=camp_site]", 0x2E6B2E, 'C'),
    SUPERMARKET("Supermarkets", "OSM Supermarkets", "nwr[shop=supermarket]", 0x7A3FB0, 'S'),
    PHARMACY("Pharmacies", "OSM Pharmacies", "nwr[amenity=pharmacy]", 0x0A9A6A, '+'),
    SPEED_CAMERA("Speed cameras", "OSM Speed cameras", "node[highway=speed_camera]", 0xD21F1F, '!', warns = true);

    /** Countries where this category may not be used (France bans warnings of camera positions). */
    val blockedIn: Set<String> get() = if (this == SPEED_CAMERA) setOf("FR") else emptySet()
}

/** Countries covered by a TomTom map folder name, as ISO codes (a best guess; the user can change it). */
fun countriesForMap(map: String?): List<String> {
    val m = map.orEmpty().lowercase()
    return when {
        "iberia" in m -> listOf("PT", "ES", "AD", "GI")
        "portugal" in m -> listOf("PT")
        "spain" in m || "espa" in m -> listOf("ES")
        "france" in m -> listOf("FR", "MC")
        "benelux" in m -> listOf("BE", "NL", "LU")
        "italy" in m -> listOf("IT", "SM", "VA")
        "germany" in m || "dach" in m -> listOf("DE", "AT", "CH")
        "uk" in m || "britain" in m -> listOf("GB", "IE")
        else -> listOf("PT")
    }
}

/** Public Overpass servers, tried in order: they are often busy, so a busy one is skipped. */
private val OVERPASS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)

fun overpassQuery(type: OsmPoiType, countries: List<String>): String {
    val areas = countries.joinToString("") { "area[\"ISO3166-1\"=\"$it\"][admin_level=2];" }
    return "[out:json][timeout:300];($areas)->.a;(${type.filter}(area.a););out center tags;"
}

/** Download one category for these countries. Tries a second server when the first is busy. */
fun fetchOsmPois(type: OsmPoiType, countries: List<String>, net: Net): List<Point> {
    val cc = countries.map { it.trim().uppercase() }.filter { it.matches(Regex("[A-Z]{2}")) }.distinct()
    require(cc.isNotEmpty()) { "Choose at least one country (2-letter code, like PT)" }
    val blocked = cc.filter { it in type.blockedIn }
    require(blocked.isEmpty()) { "${type.label} can't be installed for ${blocked.joinToString()}: warning of camera positions is illegal there." }
    val q = overpassQuery(type, cc)
    var last: String? = null
    for (server in OVERPASS) {
        val r = runCatching { net.get("$server?" + urlEncode(listOf("data" to q)), mapOf("User-Agent" to USER_AGENT), timeoutMs = 300_000) }
            .getOrElse { last = it.message; null } ?: continue
        if (r.code == 200) return parseOverpass(r.text, type)
        last = if (r.code == 429 || r.code == 504) "server busy (HTTP ${r.code})" else "HTTP ${r.code}"
    }
    error("OpenStreetMap download failed: $last. Try again in a minute.")
}

internal fun parseOverpass(json: String, type: OsmPoiType): List<Point> {
    val root = Json.parse(json) as? Map<*, *> ?: error("Unexpected answer from OpenStreetMap")
    val out = mutableListOf<Point>()
    for (e in root["elements"] as? List<*> ?: emptyList<Any>()) {
        val el = e as? Map<*, *> ?: continue
        val center = el["center"] as? Map<*, *>
        val lat = (el["lat"] ?: center?.get("lat")) as? Number ?: continue
        val lon = (el["lon"] ?: center?.get("lon")) as? Number ?: continue
        val tags = (el["tags"] as? Map<*, *>).orEmpty()
        out += Point(osmName(tags, type), lat.toDouble(), lon.toDouble())
    }
    return out
}

/** "Galp, Leiria", "Speed camera 50", or the category label when nothing better is tagged. */
internal fun osmName(tags: Map<*, *>, type: OsmPoiType): String {
    fun t(k: String) = (tags[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    if (type == OsmPoiType.SPEED_CAMERA) {
        val speed = t("maxspeed")?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
        return listOfNotNull("Speed camera", speed?.let { "$it km/h" }).joinToString(" ")
    }
    val base = when (type) {
        OsmPoiType.FUEL, OsmPoiType.SUPERMARKET -> t("brand") ?: t("name")
        OsmPoiType.EV -> listOfNotNull(t("operator") ?: t("brand") ?: t("name"), t("capacity")?.let { "x$it" }).joinToString(" ").ifEmpty { null }
        else -> t("name") ?: t("brand")
    } ?: type.label.removeSuffix("s")
    val town = t("addr:city") ?: t("addr:place")
    return if (town != null && !base.contains(town, ignoreCase = true)) "$base, $town" else base
}

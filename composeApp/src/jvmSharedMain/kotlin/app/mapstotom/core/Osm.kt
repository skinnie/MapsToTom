package app.mapstotom.core

/**
 * POI categories built from OpenStreetMap (Overpass API). Data © OpenStreetMap contributors, ODbL.
 * [filter] is an Overpass selector run against the chosen countries; several selectors are separated by ';'.
 * [tomtom] lists the TomTom Search categories that mean the same place, for skipping what the TomTom's own map
 * already has (see [MapOverlap]); null = TomTom's map has no such category, so nothing is skipped.
 * [matchMeters]: how close a TomTom place must be to count as the same one (large sites get more room).
 */
enum class OsmPoiType(val label: String, val category: String, val filter: String, val rgb: Int, val glyph: Char,
                      val warns: Boolean = false, val tomtom: String? = null, val matchMeters: Double = 120.0,
                      val looseTomtom: Set<Int> = emptySet(), val nameHint: Regex? = null) {
    FUEL("Fuel stations", "OSM Fuel", "nwr[amenity=fuel]", 0xE07000, 'F', tomtom = "7311"),
    // TomTom doesn't say which stations sell LPG, so an LPG station is never "already on the map".
    LPG("LPG (autogas)", "OSM LPG", "nwr[amenity=fuel][\"fuel:lpg\"=yes]", 0x0077AA, 'L'),
    EV("EV chargers", "OSM EV chargers", "nwr[amenity=charging_station]", 0x1A8F3C, 'E', tomtom = "7309"),
    MOTORHOME("Motorhome areas", "OSM Motorhome areas", "nwr[tourism=caravan_site]", 0x1F5FBF, 'M', tomtom = "7360003", matchMeters = 250.0),
    CAMPSITE("Campsites", "OSM Campsites", "nwr[tourism=camp_site]", 0x2E6B2E, 'C', tomtom = "7360", matchMeters = 250.0),
    SUPERMARKET("Supermarkets", "OSM Supermarkets", "nwr[shop=supermarket]", 0x7A3FB0, 'S', tomtom = "7332005"),
    PHARMACY("Pharmacies", "OSM Pharmacies", "nwr[amenity=pharmacy]", 0x0A9A6A, '+', tomtom = "7326"),
    BEACH("Beaches", "OSM Beaches", "nwr[natural=beach]", 0xD9A400, 'B', tomtom = "9357", matchMeters = 300.0),
    TOILETS("Public toilets", "OSM Toilets", "nwr[amenity=toilets][access!=private][access!=customers]", 0x3366CC, 'T', tomtom = "9932005", matchMeters = 60.0),
    DRINKING_WATER("Drinking water", "OSM Drinking water", "node[amenity=drinking_water][access!=private]", 0x1E90FF, 'W'),
    VIEWPOINT("Viewpoints", "OSM Viewpoints", "node[tourism=viewpoint]", 0x8B5A2B, 'V', tomtom = "7337", matchMeters = 150.0),
    PICNIC("Picnic sites", "OSM Picnic sites", "nwr[tourism=picnic_site]", 0x4F8A2B, 'P', tomtom = "9362033", matchMeters = 150.0),
    // Public pools are tagged as a swimming sports centre, or as a pool with a name that isn't private;
    // unnamed leisure=swimming_pool areas are mostly garden pools, so those are left out. TomTom files some
    // municipal pools as sports centres (a gym inside the municipal pools), so both count.
    POOL("Swimming pools (public)", "OSM Swimming pools",
        "nwr[leisure=sports_centre][sport~\"swimming\"];nwr[leisure=swimming_pool][name][access!~\"^(private|no)$\"]", 0x0099CC, '~',
        tomtom = "7338,7320", matchMeters = 150.0, looseTomtom = setOf(7320),
        nameHint = Regex("piscin|swim|pool|aqua|nata[cç]|zwem|schwimm|hallenbad|freibad|baln", RegexOption.IGNORE_CASE)),
    // TomTom's Search API has no cameras; its own camera service is a separate paid file on the card.
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

private val ISO_COUNTRIES = java.util.Locale.getISOCountries().toSet()

/**
 * "PT, ES" → [PT, ES]. Anything that isn't a 2-letter country code is an error that names it,
 * so a typo like "PTES" isn't dropped without a word.
 */
fun parseCountries(text: String): List<String> {
    val parts = text.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    val bad = parts.filterNot { it.length == 2 && it in ISO_COUNTRIES }
    require(bad.isEmpty()) { "Not a country code: ${bad.joinToString()}. Use 2-letter codes separated by commas, like PT, ES." }
    require(parts.isNotEmpty()) { "Choose at least one country (2-letter code, like PT)" }
    return parts.distinct()
}

/** What's wrong with the countries typed, or null when they're fine. */
fun countriesProblem(text: String): String? = runCatching { parseCountries(text) }.exceptionOrNull()?.message

/** Public Overpass servers, tried in order: they are often busy, so a busy one is skipped. */
private val OVERPASS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)

fun overpassQuery(type: OsmPoiType, countries: List<String>): String {
    val areas = countries.joinToString("") { "area[\"ISO3166-1\"=\"$it\"][admin_level=2];" }
    val selectors = type.filter.split(';').joinToString("") { "$it(area.a);" }
    return "[out:json][timeout:300];($areas)->.a;($selectors);out center tags;"
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
    if (type == OsmPoiType.TOILETS) {
        val fee = when (t("fee")?.lowercase()) { "no" -> "free"; "yes" -> "paid"; else -> null }
        val access = if (t("wheelchair") == "yes") "wheelchair" else null
        val extra = listOfNotNull(fee, access).joinToString(", ").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        val where = t("name") ?: t("addr:city") ?: t("addr:place")
        return "Toilets" + extra + (where?.let { ", $it" } ?: "")
    }
    val base = when (type) {
        OsmPoiType.FUEL, OsmPoiType.LPG, OsmPoiType.SUPERMARKET -> t("brand") ?: t("name")
        OsmPoiType.EV -> listOfNotNull(t("operator") ?: t("brand") ?: t("name"), t("capacity")?.let { "x$it" }).joinToString(" ").ifEmpty { null }
        else -> t("name") ?: t("brand")
    } ?: when (type) {
        OsmPoiType.BEACH -> "Beach"; OsmPoiType.DRINKING_WATER -> "Drinking water"
        OsmPoiType.PHARMACY -> "Pharmacy"; OsmPoiType.POOL -> "Swimming pool"
        else -> type.label.removeSuffix("s")
    }
    val town = t("addr:city") ?: t("addr:place")
    return if (town != null && !base.contains(town, ignoreCase = true)) "$base, $town" else base
}

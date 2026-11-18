package app.mapstotom.core

/*
 * Places from Foursquare Open Source Places (Apache 2.0): about 100 million places worldwide, published as
 * Parquet files. The copy used here is the one on Source.coop, split by area, so a country needs only one or
 * two files, and only parts of those. files/fsq/index.json (made by tools/fsq/fsqcountryindex.py) says which
 * files and row groups hold each country, so the app reads only those: about 40 MB for Portugal.
 *
 * Foursquare keeps places nobody has confirmed in years. Each place has the date it was last confirmed
 * (date_refreshed) and, when known, the date it closed; closed places are always left out, old ones by choice.
 */

/** A kind of place. [prefixes] are Foursquare category paths ("Dining and Drinking > Restaurant"); the first kind that matches wins. */
enum class FsqPoiType(val label: String, val rgb: Int, val glyph: Char, val tomtomCategory: Int?, val prefixes: List<String>) {
    FUEL("Fuel stations", 0x2E7D32, 'F', TomTomCategory.PETROL_STATION, listOf("Travel and Transportation > Fuel Station")),
    PHARMACY("Pharmacies", 0x00897B, '+', TomTomCategory.PHARMACY, listOf("Retail > Pharmacy")),
    HOSPITAL("Hospitals and emergency", 0xC62828, '+', TomTomCategory.HOSPITAL, listOf("Health and Medicine > Hospital",
        "Health and Medicine > Emergency Service", "Health and Medicine > Urgent Care Center")),
    CAMPGROUND("Campgrounds", 0x6D4C41, 'C', TomTomCategory.CAMPGROUND, listOf("Travel and Transportation > Lodging > Campground",
        "Landmarks and Outdoors > Campground")),
    LODGING("Hotels and lodging", 0x5E35B1, 'H', TomTomCategory.HOTEL, listOf("Travel and Transportation > Lodging")),
    RESTAURANT("Restaurants", 0xE65100, 'R', TomTomCategory.RESTAURANT, listOf("Dining and Drinking > Restaurant", "Dining and Drinking > Steakhouse")),
    CAFE("Cafés and bakeries", 0x8D6E63, 'C', null, listOf("Dining and Drinking > Cafe, Coffee, and Tea House", "Dining and Drinking > Bakery",
        "Dining and Drinking > Dessert Shop", "Dining and Drinking > Breakfast Spot", "Dining and Drinking > Snack Place")),
    BAR("Bars", 0xAD1457, 'B', null, listOf("Dining and Drinking > Bar", "Dining and Drinking > Pub", "Dining and Drinking > Brewery",
        "Dining and Drinking > Winery")),
    SUPERMARKET("Supermarkets", 0x1565C0, 'S', null, listOf("Retail > Food and Beverage Retail > Supermarket",
        "Retail > Food and Beverage Retail > Grocery Store", "Retail > Convenience Store")),
    VIEWPOINT("Viewpoints", 0x00838F, 'V', TomTomCategory.SCENIC_VIEW, listOf("Landmarks and Outdoors > Scenic Lookout")),
    BEACH("Beaches", 0xF9A825, '~', TomTomCategory.BEACH, listOf("Landmarks and Outdoors > Beach")),
    MUSEUM("Museums", 0x6A1B9A, 'M', TomTomCategory.MUSEUM, listOf("Arts and Entertainment > Museum")),
    MONUMENT("Monuments and historic sites", 0x7B1FA2, 'M', TomTomCategory.TOURIST_ATTRACTION, listOf(
        "Landmarks and Outdoors > Historic and Protected Site", "Landmarks and Outdoors > Monument", "Landmarks and Outdoors > Castle",
        "Landmarks and Outdoors > Palace")),
    PARKING("Parking", 0x37474F, 'P', TomTomCategory.OPEN_PARKING, listOf("Travel and Transportation > Parking")),
    ATM("Banks and cash machines", 0x283593, 'B', TomTomCategory.CASH_DISPENSER, listOf(
        "Business and Professional Services > Financial Service > ATM",
        "Business and Professional Services > Financial Service > Banking and Finance > Bank")),
    CAR_REPAIR("Car repair", 0x455A64, 'G', TomTomCategory.CAR_REPAIR, listOf(
        "Business and Professional Services > Automotive Service > Automotive Repair Shop",
        "Business and Professional Services > Automotive Service > Tire Repair Shop"));

    val source: String get() = "Foursquare $name"

    companion object {
        /** The kind of a place with these Foursquare category paths, or null. */
        fun of(labels: List<*>): FsqPoiType? = entries.firstOrNull { t ->
            labels.any { l -> l is String && t.prefixes.any { p -> l == p || l.startsWith("$p >") } }
        }
    }
}

/** Which files and row groups hold each country (2-letter code), from files/fsq/index.json. */
class FsqIndex(val release: String, val base: String, val countries: Map<String, Map<String, List<Int>>>) {
    companion object {
        fun parse(json: String): FsqIndex {
            val m = Json.parse(json) as Map<*, *>
            val c = (m["countries"] as Map<*, *>).entries.associate { (k, v) ->
                k as String to (v as Map<*, *>).entries.associate { (f, rgs) -> f as String to (rgs as List<*>).map { (it as Long).toInt() } }
            }
            return FsqIndex(m["release"] as String, m["base"] as String, c)
        }
    }
}

/** Places refreshed in or after this year count as current, unless the user picks another. */
const val FSQ_DEFAULT_SINCE = 2020

/**
 * Downloads the places of [types] in [countries], leaving out closed places and those not confirmed since [since]
 * (a year). [progress] gets (done, of) row groups.
 */
fun fetchFoursquare(
    index: FsqIndex, countries: List<String>, types: Set<FsqPoiType>, since: Int, net: Net,
    progress: (Int, Int) -> Unit = { _, _ -> },
): Map<FsqPoiType, List<Point>> {
    val cc = countries.map { it.uppercase() }.toSet()
    val missing = cc.filter { it !in index.countries }
    require(missing.size < cc.size) { "Foursquare has no places for ${cc.joinToString()}" }
    val parts = HashMap<String, MutableSet<Int>>()
    for (c in cc) index.countries[c]?.forEach { (f, rgs) -> parts.getOrPut(f) { sortedSetOf() } += rgs }
    val total = parts.values.sumOf { it.size }
    var done = 0
    val out = types.associateWith { LinkedHashMap<String, Point>() }
    val sinceText = "%04d".format(since)
    for ((file, rgs) in parts.toSortedMap()) {
        val pf = ParquetFile(HttpRangeSource(net, index.base + file))
        for (rg in rgs) {
            progress(done++, total)
            val g = pf.rowGroups.getOrNull(rg) ?: continue
            val country = g.columns["country"] ?: continue
            // Row groups are sorted by place, so the country range in the stats is usually exact.
            val lo = country.min?.toString(Charsets.UTF_8); val hi = country.max?.toString(Charsets.UTF_8)
            if (lo != null && hi != null && cc.none { it in lo..hi }) continue
            val ctry = pf.read(rg, "country")
            val wanted = ctry.indices.filter { ctry[it] in cc }
            if (wanted.isEmpty()) continue
            val labels = pf.read(rg, "fsq_category_labels.list.element")
            val kinds = arrayOfNulls<FsqPoiType>(ctry.size)
            for (i in wanted) kinds[i] = FsqPoiType.of(labels[i] as? List<*> ?: continue)?.takeIf { it in types }
            if (kinds.all { it == null }) continue
            val closed = pf.read(rg, "date_closed"); val refreshed = pf.read(rg, "date_refreshed")
            val name = pf.read(rg, "name"); val lat = pf.read(rg, "latitude"); val lon = pf.read(rg, "longitude")
            for (i in wanted) {
                val k = kinds[i] ?: continue
                if (closed[i] != null) continue
                if (((refreshed[i] as? String) ?: "0000") < sinceText) continue
                val la = lat[i] as? Double ?: continue; val lo2 = lon[i] as? Double ?: continue
                val n = (name[i] as? String)?.trim().orEmpty()
                if (n.isEmpty()) continue
                out.getValue(k).putIfAbsent("${Math.rint(la * 1e5)},${Math.rint(lo2 * 1e5)},$n", Point(n, la, lo2))
            }
        }
    }
    progress(total, total)
    return out.mapValues { it.value.values.toList() }
}

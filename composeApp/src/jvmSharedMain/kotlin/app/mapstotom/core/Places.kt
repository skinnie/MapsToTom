package app.mapstotom.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Kinds of place from the open place data sets (Foursquare OS Places, Overture Maps), and what both have in common:
 * the boxes that cover a country, picking the parts of area-sorted files that touch them, and installing a kind on
 * the TomTom. Both sources fill the same file per kind, each with its own source name in the list of added
 * places, so a café from both isn't shown twice (places within SAME_PLACE_METERS of another source are left out).
 */

/**
 * A kind of place. [fsq] and [overture] are category paths in each data set ("Dining and Drinking > Restaurant",
 * "food_and_drink > restaurant"); a place matches when one of its paths starts with one of them. The first kind
 * that matches wins. Kinds with a [tomtomCategory] go into the TomTom's own category; the others get a named file.
 */
enum class PlaceKind(val label: String, val rgb: Int, val glyph: Char, val tomtomCategory: Int?, val fsq: List<String>, val overture: List<String>) {
    FUEL("Fuel stations", 0x2E7D32, 'F', TomTomCategory.PETROL_STATION, listOf("Travel and Transportation > Fuel Station"),
        listOf("travel_and_transportation > fueling_station")),
    PHARMACY("Pharmacies", 0x00897B, '+', TomTomCategory.PHARMACY, listOf("Retail > Pharmacy"),
        listOf("shopping > specialty_store > pharmacy_and_drug_store > pharmacy")),
    HOSPITAL("Hospitals and emergency", 0xC62828, '+', TomTomCategory.HOSPITAL, listOf("Health and Medicine > Hospital",
        "Health and Medicine > Emergency Service", "Health and Medicine > Urgent Care Center"),
        listOf("health_care > hospital", "health_care > emergency_or_urgent_care_facility")),
    CAMPGROUND("Campgrounds", 0x6D4C41, 'C', TomTomCategory.CAMPGROUND, listOf("Travel and Transportation > Lodging > Campground",
        "Landmarks and Outdoors > Campground"), listOf("lodging > campground", "lodging > rv_park")),
    LODGING("Hotels and lodging", 0x5E35B1, 'H', TomTomCategory.HOTEL, listOf("Travel and Transportation > Lodging"),
        listOf("lodging")), // like Foursquare's, holiday rentals included
    RESTAURANT("Restaurants", 0xE65100, 'R', TomTomCategory.RESTAURANT, listOf("Dining and Drinking > Restaurant", "Dining and Drinking > Steakhouse"),
        listOf("food_and_drink > restaurant", "food_and_drink > casual_eatery > fast_food_restaurant")),
    CAFE("Cafés and bakeries", 0x8D6E63, 'C', null, listOf("Dining and Drinking > Cafe, Coffee, and Tea House", "Dining and Drinking > Bakery",
        "Dining and Drinking > Dessert Shop", "Dining and Drinking > Breakfast Spot", "Dining and Drinking > Snack Place"),
        listOf("food_and_drink > casual_eatery > cafe", "food_and_drink > non_alcoholic_beverage_venue > coffee_shop",
            "food_and_drink > casual_eatery > bakery", "food_and_drink > casual_eatery > dessert_shop")),
    BAR("Bars", 0xAD1457, 'B', null, listOf("Dining and Drinking > Bar", "Dining and Drinking > Pub", "Dining and Drinking > Brewery",
        "Dining and Drinking > Winery"), listOf("food_and_drink > alcoholic_beverage_venue", "food_and_drink > casual_eatery > gastropub")),
    SUPERMARKET("Supermarkets", 0x1565C0, 'S', null, listOf("Retail > Food and Beverage Retail > Supermarket",
        "Retail > Food and Beverage Retail > Grocery Store", "Retail > Convenience Store"),
        listOf("shopping > food_and_beverage_store > grocery_store", "shopping > superstore", "shopping > convenience_store")),
    // Overture has no viewpoint category: it files them as historic sites.
    VIEWPOINT("Viewpoints", 0x00838F, 'V', TomTomCategory.SCENIC_VIEW, listOf("Landmarks and Outdoors > Scenic Lookout"), emptyList()),
    BEACH("Beaches", 0xF9A825, '~', TomTomCategory.BEACH, listOf("Landmarks and Outdoors > Beach"), listOf("geographic_entities > land_feature > beach")),
    MUSEUM("Museums", 0x6A1B9A, 'M', TomTomCategory.MUSEUM, listOf("Arts and Entertainment > Museum"), listOf("arts_and_entertainment > museum")),
    MONUMENT("Monuments and historic sites", 0x7B1FA2, 'M', TomTomCategory.TOURIST_ATTRACTION, listOf(
        "Landmarks and Outdoors > Historic and Protected Site", "Landmarks and Outdoors > Monument", "Landmarks and Outdoors > Castle",
        "Landmarks and Outdoors > Palace"), listOf("cultural_and_historic > historic_site", "cultural_and_historic > memorial_site > monument")),
    PARKING("Parking", 0x37474F, 'P', TomTomCategory.OPEN_PARKING, listOf("Travel and Transportation > Parking"), listOf("travel_and_transportation > parking")),
    ATM("Banks and cash machines", 0x283593, 'B', TomTomCategory.CASH_DISPENSER, listOf(
        "Business and Professional Services > Financial Service > ATM",
        "Business and Professional Services > Financial Service > Banking and Finance > Bank"),
        listOf("services_and_business > financial_service > atm", "services_and_business > financial_service > bank_or_credit_union")),
    CAR_REPAIR("Car repair", 0x455A64, 'G', TomTomCategory.CAR_REPAIR, listOf(
        "Business and Professional Services > Automotive Service > Automotive Repair Shop",
        "Business and Professional Services > Automotive Service > Tire Repair Shop"),
        listOf("travel_and_transportation > vehicle_service > automotive_service > automotive_repair"));

    val fsqSource: String get() = "Foursquare $name"
    val overtureSource: String get() = "Overture $name"

    companion object {
        /** The kind of a Foursquare place with these category paths ("A > B > C"), or null. */
        fun ofFsq(labels: List<*>): PlaceKind? = entries.firstOrNull { t -> labels.any { l -> l is String && t.fsq.any { startsPath(l, it) } } }

        /** The kind of an Overture place with this taxonomy hierarchy (["food_and_drink", "restaurant", …]), or null. */
        fun ofOverture(hierarchy: List<*>): PlaceKind? {
            val path = hierarchy.filterIsInstance<String>().joinToString(" > ")
            if (path.isEmpty()) return null
            return entries.firstOrNull { t -> t.overture.any { startsPath(path, it) } }
        }

        private fun startsPath(path: String, prefix: String) = path == prefix || path.startsWith("$prefix >")
    }
}

/** Boxes [west, south, east, north] that cover each country's land, from files/country-boxes.json (Natural Earth). */
class CountryBoxes(private val boxes: Map<String, List<DoubleArray>>) {
    fun of(country: String): List<DoubleArray> = boxes[country.uppercase()].orEmpty()
    fun has(country: String) = country.uppercase() in boxes

    companion object {
        fun parse(json: String) = CountryBoxes((Json.parse(json) as Map<*, *>).entries.associate { (k, v) ->
            k as String to (v as List<*>).map { b -> (b as List<*>).map { (it as Number).toDouble() }.toDoubleArray() }
        })
    }
}

/** A download that would be too big for a phone or a slow line; the message says how big. */
class TooBigDownload(val megabytes: Long, what: String) : Exception("$what would mean downloading about $megabytes MB, so it was stopped")

/** Stop a download plan above this. */
const val PLACES_MAX_MB = 600L

/** The row groups whose longitude/latitude statistics touch one of [boxes]. Row groups without statistics count as touching. */
internal fun rowGroupsTouching(pf: ParquetFile, lonMin: String, lonMax: String, latMin: String, latMax: String, boxes: List<DoubleArray>): List<Int> =
    pf.rowGroups.indices.filter { i ->
        val c = pf.rowGroups[i].columns
        val w = c[lonMin]?.min?.let(::statDouble); val e = c[lonMax]?.max?.let(::statDouble)
        val s = c[latMin]?.min?.let(::statDouble); val n = c[latMax]?.max?.let(::statDouble)
        if (w == null || e == null || s == null || n == null) true
        else boxes.any { b -> e >= b[0] && w <= b[2] && n >= b[1] && s <= b[3] }
    }

/** A DOUBLE or FLOAT statistic as stored (little-endian). */
internal fun statDouble(b: ByteArray): Double? = when (b.size) {
    8 -> ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).double
    4 -> ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()
    else -> null
}

/** Bytes to download for [columns] of row groups [rgs]. */
internal fun plannedBytes(pf: ParquetFile, rgs: Collection<Int>, columns: List<String>): Long =
    rgs.sumOf { rg -> columns.sumOf { pf.rowGroups[rg].columns[it]?.length ?: 0L } }

/**
 * Puts [points] of [kind] from [source] on the TomTom: into its own category, or into the kind's named file in
 * [lang] (with its icon). Places [source] added before are replaced; the user's own and other sources' stay.
 */
fun installPlaceKind(fs: DeviceFs, mapDir: String, kind: PlaceKind, source: String, points: List<Point>, lang: String): CategoryInstall {
    val cat = kind.tomtomCategory
    if (cat != null) return installIntoTomTomCategory(fs, mapDir, cat, source, points)
    val base = join(mapDir, (kind.translated ?: error("no name for ${kind.label}")).nameIn(lang))
    val r = installIntoCategoryFile(fs, "$base.ov2", source, points)
    if (fs.exists("$base.ov2")) fs.write("$base.bmp", categoryIconBmp(kind.rgb, kind.glyph)) else fs.delete("$base.bmp")
    return r
}

/** "Restaurants 1,234 (5 next to your own POIs)" for an install summary. */
fun placeInstallNote(kind: PlaceKind, r: CategoryInstall): String =
    "${kind.label} %,d".format(r.added) + categoryNearNote(r).takeIf { it.isNotEmpty() }?.let { " (${it.removePrefix(", ")})" }.orEmpty()

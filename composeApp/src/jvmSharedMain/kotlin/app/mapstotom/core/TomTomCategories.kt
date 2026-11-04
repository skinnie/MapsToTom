package app.mapstotom.core

import kotlin.math.cos
import kotlin.math.sqrt

/*
 * POIs added to the TomTom's own categories. When a POI is added on the TomTom to a built-in category, NavCore
 * keeps it in the map folder as PA<category number>.ov2 (a swimming pool goes in PA7338.ov2). The TomTom names
 * and draws those categories itself, in its own language. So places of a kind the TomTom already has go in there
 * instead of a file of their own; categories the TomTom lacks (LPG, motorhome areas, iOverlander's wild camping…)
 * stay as named files (see PoiNames.kt).
 *
 * A PA file can also hold the user's own POIs, and places from more than one source (OpenStreetMap campsites and
 * iOverlander campgrounds both go in PA7360). So the app keeps a list of what it added, per source, next to the
 * file (PA7360.ov2.mapstotom, which the TomTom ignores). An install replaces only that source's places, never the
 * user's, and leaves out places within [SAME_PLACE_METERS] of the user's own or of another source's.
 */

/** The categories the app fills, by number. All are in NavCore 9.541's category list (ttn 0x7c09a8). */
object TomTomCategory {
    const val PETROL_STATION = 7311
    const val PHARMACY = 7326
    const val SCENIC_VIEW = 7337
    const val SWIMMING_POOL = 7338
    const val CAMPGROUND = 7360
    const val BEACH = 9357
}

/** Places this close count as the same one. */
const val SAME_PLACE_METERS = 150.0

/** The TomTom category this OpenStreetMap type goes into, or null when it gets a file of its own. */
val OsmPoiType.tomtomCategory: Int? get() = when (this) {
    OsmPoiType.FUEL -> TomTomCategory.PETROL_STATION
    OsmPoiType.PHARMACY -> TomTomCategory.PHARMACY
    OsmPoiType.VIEWPOINT -> TomTomCategory.SCENIC_VIEW
    OsmPoiType.POOL -> TomTomCategory.SWIMMING_POOL
    OsmPoiType.CAMPSITE -> TomTomCategory.CAMPGROUND
    OsmPoiType.BEACH -> TomTomCategory.BEACH
    else -> null
}

/** The source name kept in the list of added places. */
val OsmPoiType.source: String get() = "OSM $name"

/** The TomTom category for a category of a .gpi file titled [fileTitle], or null. */
fun gpiTomtomCategory(fileTitle: String?, category: String): Int? =
    if (fileTitle.orEmpty().contains("ioverlander", ignoreCase = true) && category.trim().equals("Established Campground", true))
        TomTomCategory.CAMPGROUND else null

fun gpiSource(fileTitle: String?, category: String) = "${fileTitle.orEmpty().substringBefore(' ')} ${category.trim()}".trim()

fun tomtomCategoryFile(mapDir: String, id: Int) = join(mapDir, "PA$id.ov2")
/** The app's list of what it added to the category file [ov2]. */
fun listFile(ov2: String) = "$ov2.mapstotom"

private val CATEGORY_NAMES = mapOf(
    TomTomCategory.PETROL_STATION to "Petrol stations", TomTomCategory.PHARMACY to "Pharmacies",
    TomTomCategory.SCENIC_VIEW to "Scenic views", TomTomCategory.SWIMMING_POOL to "Swimming pools",
    TomTomCategory.CAMPGROUND to "Campgrounds", TomTomCategory.BEACH to "Beaches",
)

/** "Swimming pools (TomTom category)" for a file named PA7338, or null when [fileBase] isn't a PA file. */
fun tomtomCategoryLabel(fileBase: String): String? {
    val id = Regex("PA(\\d{4,7})", RegexOption.IGNORE_CASE).matchEntire(fileBase)?.groupValues?.get(1)?.toInt() ?: return null
    return (CATEGORY_NAMES[id] ?: "Category $id") + " (TomTom category)"
}

/** What an install into a TomTom category did. */
class CategoryInstall(val file: String, val added: Int, val nearYours: Int, val nearOther: Int, val yours: Int)

/**
 * Puts [points] from [source] into the TomTom's category [id]: the places [source] added before are replaced,
 * the user's own and other sources' stay. New places close to one of those are left out.
 */
fun installIntoTomTomCategory(fs: DeviceFs, mapDir: String, id: Int, source: String, points: List<Point>): CategoryInstall {
    val path = tomtomCategoryFile(mapDir, id)
    val old = fs.read(path)
    val c = old?.let(::readOv2Contents) ?: Ov2Contents(emptyList(), emptyList(), true)
    require(c.complete) { "${nameOf(path)} on the TomTom doesn't read as a POI file, so it was left alone" }
    val added = readAddedList(fs.read(listFile(path)))

    val kept = c.entries.filter { added[key(it.lat, it.lon)] != source }
    val yours = kept.filter { key(it.lat, it.lon) !in added }
    val others = kept.filter { key(it.lat, it.lon) in added }
    val nearYours = NearIndex(yours); val nearOther = NearIndex(others)
    var skippedYours = 0; var skippedOther = 0
    val fresh = mutableListOf<Ov2Entry>()
    val seen = HashSet<Long>()
    for (p in points) {
        when {
            nearYours.any(p.lat, p.lon) -> skippedYours++
            nearOther.any(p.lat, p.lon) -> skippedOther++
            seen.add(key(p.lat, p.lon)) -> fresh += Ov2Entry(safeName(p.name), p.lat, p.lon)
        }
    }

    if (old != null && !fs.exists("$path.mapstotom-backup") && added.isEmpty()) fs.write("$path.mapstotom-backup", old)
    val all = kept + fresh
    if (all.isEmpty()) { fs.delete(path); fs.delete(listFile(path)) }
    else {
        fs.write(path, buildOv2(all, c.other, c.heads))
        val list = others.map { added.getValue(key(it.lat, it.lon)) to it } + fresh.map { source to it }
        fs.write(listFile(path), writeAddedList(list))
    }
    return CategoryInstall(path, fresh.size, skippedYours, skippedOther, yours.size)
}

/** ", 2 next to your own POIs, 5 already added from another source" (empty when nothing was left out). */
fun categoryNearNote(r: CategoryInstall): String =
    (if (r.nearYours > 0) ", %,d next to your own POIs".format(r.nearYours) else "") +
        (if (r.nearOther > 0) ", %,d already added from another source".format(r.nearOther) else "")

/** Takes [source]'s places out of category [id] again (the user's own stay). Returns how many were removed. */
fun removeFromTomTomCategory(fs: DeviceFs, mapDir: String, id: Int, source: String): Int {
    val path = tomtomCategoryFile(mapDir, id)
    val before = fs.read(path)?.let(::readOv2Contents)?.entries?.size ?: return 0
    installIntoTomTomCategory(fs, mapDir, id, source, emptyList())
    return before - (fs.read(path)?.let(::readOv2Contents)?.entries?.size ?: 0)
}

/**
 * Deletes the app's own named file for [c] (any language), with its icon and sound: used once its places live in
 * a TomTom category, so they aren't shown twice. Returns the names deleted.
 */
fun removeNamedCategory(fs: DeviceFs, mapDir: String, c: TranslatedCategory): List<String> {
    val known = c.knownNames()
    val names = fs.list(mapDir)
    val bases = names.filter { it.endsWith(".ov2", true) && it.dropLast(4).lowercase() in known }.map { it.dropLast(4) }
    for (b in bases) for (f in names.filter { it.substringBeforeLast('.').equals(b, true) && it.substringAfterLast('.').lowercase() in setOf("ov2", "bmp", "ogg") })
        fs.delete(join(mapDir, f))
    return bases
}

/**
 * Moves the places of the app's older named files ("Campsites (OSM)", "Established Campground (iOverlander)")
 * into the TomTom categories they belong to, and deletes those files. Returns "name → PA7360 (n)" per file moved.
 */
fun moveNamedIntoTomTomCategories(fs: DeviceFs, mapDir: String): List<String> {
    val moves = OsmPoiType.entries.mapNotNull { t -> t.tomtomCategory?.let { Triple(t.translated, it, t.source) } } +
        listOfNotNull(gpiTranslated("iOverlander", "Established Campground")?.let {
            Triple(it, TomTomCategory.CAMPGROUND, gpiSource("iOverlander", "Established Campground")) })
    val done = mutableListOf<String>()
    for ((named, id, source) in moves) {
        val known = named.knownNames()
        val files = fs.list(mapDir).filter { it.endsWith(".ov2", true) && it.dropLast(4).lowercase() in known }
        if (files.isEmpty()) continue
        val c = files.map { readOv2Contents(fs.read(join(mapDir, it)) ?: ByteArray(0)) }
        if (c.any { !it.complete }) continue
        val r = installIntoTomTomCategory(fs, mapDir, id, source, c.flatMap { it.entries.map(Ov2Entry::toPoint) })
        removeNamedCategory(fs, mapDir, named)
        done += "${files.joinToString { it.dropLast(4) }} → PA$id (${r.added})"
    }
    return done
}

/** Which sources added places to the category file [ov2], with how many each. */
fun addedSources(fs: DeviceFs, ov2: String): Map<String, Int> =
    readAddedList(fs.read(listFile(ov2))).values.groupingBy { it }.eachCount()

// The list: one line per added place, "lat×1e5<TAB>lon×1e5<TAB>source".
private fun key(lat: Double, lon: Double): Long = (Math.rint(lat * 1e5).toLong() shl 32) xor (Math.rint(lon * 1e5).toLong() and 0xffffffffL)

private fun readAddedList(data: ByteArray?): Map<Long, String> {
    val out = HashMap<Long, String>()
    data?.toString(Charsets.UTF_8)?.lineSequence()?.forEach { line ->
        val f = line.split('\t')
        if (f.size < 3) return@forEach
        val lat = f[0].toLongOrNull() ?: return@forEach; val lon = f[1].toLongOrNull() ?: return@forEach
        out[key(lat / 1e5, lon / 1e5)] = f[2]
    }
    return out
}

private fun writeAddedList(list: List<Pair<String, Ov2Entry>>): ByteArray = buildString {
    append("# MapsToTom: places it added to this TomTom category (lat, lon x100000, source). Others are yours.\n")
    for ((s, e) in list) append(Math.rint(e.lat * 1e5).toLong()).append('\t').append(Math.rint(e.lon * 1e5).toLong()).append('\t').append(s).append('\n')
}.toByteArray()

/** Finds places near a point quickly: a grid of about [SAME_PLACE_METERS] cells. */
private class NearIndex(entries: List<Ov2Entry>) {
    private val cell = SAME_PLACE_METERS / 111_320.0
    private val grid = HashMap<Long, MutableList<Ov2Entry>>()
    private fun k(a: Int, b: Int) = (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)
    init { for (e in entries) grid.getOrPut(k((e.lat / cell).toInt(), (e.lon / cell).toInt())) { mutableListOf() } += e }

    fun any(lat: Double, lon: Double): Boolean {
        if (grid.isEmpty()) return false
        val a = (lat / cell).toInt(); val b = (lon / cell).toInt()
        val reach = (1 / cos(Math.toRadians(lat)).coerceAtLeast(0.2)).toInt() + 1 // lon cells shrink away from the equator
        for (i in a - 1..a + 1) for (j in b - reach..b + reach)
            if (grid[k(i, j)]?.any { meters(lat, lon, it.lat, it.lon) <= SAME_PLACE_METERS } == true) return true
        return false
    }

    private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2)); val y = Math.toRadians(lat2 - lat1)
        return sqrt(x * x + y * y) * 6_371_000.0
    }
}

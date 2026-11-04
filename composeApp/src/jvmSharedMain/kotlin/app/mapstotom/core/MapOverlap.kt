package app.mapstotom.core

import java.io.File
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Skips OpenStreetMap places that the TomTom's own map already has, so an OSM category only adds what is missing.
 *
 * The map on the card can't be read (its POI file is encrypted), so TomTom's online Search API stands in for it,
 * with the user's own developer key. Spot checks in a Portuguese town (2026-11-03) found the same entries on the
 * Iberia 1175 map (searched in the emulator) and online; the online database is newer, so a place TomTom added
 * after the map was made counts as "already there".
 *
 * Requests are kept low: the map is cut into squares of about 44 km. A square with OSM places in it costs one
 * search (limit 100); a full one is split in four, again and again down to about 350 m, and only the parts that
 * hold OSM places are searched. Countryside costs one search per square, towns a few more. Each answer is cached
 * per square, so a second install is free.
 */
class MapOverlap(
    private val key: String,
    private val net: Net,
    private val cache: OverlapCache,
    private val budget: RequestBudget,
) {
    /** One OSM place left out, with the TomTom entry it matched, for the list the user can check. */
    data class Skipped(val category: String, val osmName: String, val tomtomName: String, val meters: Int)

    class Result(val kept: List<Point>, val skipped: List<Skipped>, val unchecked: Int, val requests: Int)

    private var requests = 0

    /**
     * [points] minus the ones within the type's match distance of a TomTom place of its categories.
     * When the day's request budget runs out, the remaining places are kept (a duplicate is better than a missing
     * place) and counted in [Result.unchecked]; installing again on another day removes them.
     */
    fun filter(type: OsmPoiType, points: List<Point>, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val cats = type.tomtom ?: return Result(points, emptyList(), 0, 0)
        requests = 0
        val cells = points.groupBy { cellOf(it.lat, it.lon) }
        val out = Out(type)
        var done = 0
        for ((cell, pts) in cells) {
            check(cats, cell.key, cell.lat0, cell.lat0 + CELL_LAT, cell.lon0, cell.lon0 + CELL_LON, pts, 0, type.matchMeters, out)
            progress(++done, cells.size)
        }
        cache.save()
        return Result(out.kept, out.skipped, out.unchecked, requests)
    }

    private class Out(val type: OsmPoiType) {
        val kept = mutableListOf<Point>(); val skipped = mutableListOf<Skipped>(); var unchecked = 0
        fun match(pts: List<Point>, near: List<Place>) = pts.forEach { p ->
            val hit = near.filter { sameKind(type, it, meters(p.lat, p.lon, it.lat, it.lon)) }.minByOrNull { meters(p.lat, p.lon, it.lat, it.lon) }
            val m = hit?.let { meters(p.lat, p.lon, it.lat, it.lon) }
            if (hit != null && m!! <= type.matchMeters) skipped += Skipped(type.label, p.name, hit.name, m.toInt()) else kept += p
        }
    }

    /**
     * Compare [pts] (all inside this square) with TomTom's places in it plus [margin] m around: one search, from the
     * cache when possible. A full answer means the square holds more than one search returns, so its quarters that
     * have OSM places are searched instead. With the day's budget spent, the places are kept unchecked.
     */
    private fun check(cats: String, key: String, lat0: Double, lat1: Double, lon0: Double, lon1: Double,
                      pts: List<Point>, depth: Int, margin: Double, out: Out) {
        if (pts.isEmpty()) return
        val mlat = (lat0 + lat1) / 2; val mlon = (lon0 + lon1) / 2
        var places = cache.get(cats, key)
        if (places == null && !cache.isFull(cats, key)) {
            val res = try { nearby(cats, mlat, mlon, meters(lat0, lon0, lat1, lon1) / 2 + margin) }
                catch (e: BudgetSpent) { out.kept += pts; out.unchecked += pts.size; return }
            if (res.size >= SEARCH_LIMIT && depth < MAX_DEPTH) cache.putFull(cats, key) else { cache.put(cats, key, res); places = res }
        }
        if (places != null) return out.match(pts, places)
        val quarters = pts.groupBy { (if (it.lat < mlat) 0 else 2) + (if (it.lon < mlon) 0 else 1) }
        for ((q, qp) in quarters) {
            val (a, b) = if (q < 2) lat0 to mlat else mlat to lat1
            val (c, d) = if (q % 2 == 0) lon0 to mlon else mlon to lon1
            check(cats, "$key/$q", a, b, c, d, qp, depth + 1, margin, out)
        }
    }

    private fun nearby(cats: String, lat: Double, lon: Double, radius: Double): List<Place> {
        var wait = 1000L
        repeat(4) {
            if (!budget.take()) throw BudgetSpent()
            requests++
            val q = urlEncode(listOf("key" to key, "lat" to "%.6f".format(java.util.Locale.ROOT, lat), "lon" to "%.6f".format(java.util.Locale.ROOT, lon),
                "radius" to max(1, radius.toInt()).toString(), "categorySet" to cats, "limit" to SEARCH_LIMIT.toString()))
            val r = net.get("https://api.tomtom.com/search/2/nearbySearch/.json?$q", mapOf("User-Agent" to USER_AGENT), timeoutMs = 30_000)
            when (r.code) {
                200 -> return parseNearby(r.text)
                // Out of credits is a 403 too ("InsufficientFunds"): that is the day's limit, not a bad key.
                403 -> if ("InsufficientFunds" in r.text || "Over" in r.text) throw BudgetSpent()
                    else error("TomTom refused the key (HTTP 403). Check it on developer.tomtom.com.")
                429 -> { Thread.sleep(wait); wait *= 3 } // too fast, or the day's free requests are used up
                else -> error("TomTom search failed (HTTP ${r.code})")
            }
        }
        throw BudgetSpent() // still 429 after retries: treat as the day's quota
    }

    /** [cats]: TomTom category ids of the place; empty when unknown (answers cached before they were kept). */
    data class Place(val name: String, val lat: Double, val lon: Double, val cats: List<Int> = emptyList())
    private class BudgetSpent : RuntimeException()

    data class Cell(val key: String, val lat0: Double, val lon0: Double)

    companion object {
        const val SEARCH_LIMIT = 100
        /** About 44 x 43 km in Portugal; half its diagonal stays well under the search's 50 km radius limit. */
        const val CELL_LAT = 0.4
        const val CELL_LON = 0.5
        /** Splits of a full square: 44 km down to about 350 m. */
        const val MAX_DEPTH = 7
        /** In every cache key, so answers for an older square size are never reused. */
        const val CELL_VERSION = "q44"

        fun cellOf(lat: Double, lon: Double): Cell {
            val y = Math.floorDiv((lat * 1e6).toLong(), (CELL_LAT * 1e6).toLong())
            val x = Math.floorDiv((lon * 1e6).toLong(), (CELL_LON * 1e6).toLong())
            return Cell("$CELL_VERSION:$y:$x", y * CELL_LAT, x * CELL_LON)
        }

        fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
            6371000.0 * hypot(Math.toRadians(lat2 - lat1), Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2)))

        /** A place only counts if it's very close, within [LOOSE_METERS]. */
        const val LOOSE_METERS = 25.0

        /**
         * Whether TomTom's [place], [m] metres away, can stand for an OSM place of [type]. A place from one of the
         * type's loose categories (a sports centre for a pool) is often something else nearby, such as a gym or a
         * club, so it counts only when its name says what the type is, or when it's within [LOOSE_METERS].
         * A place cached without categories is treated as loose for a type that has loose categories.
         */
        fun sameKind(type: OsmPoiType, place: Place, m: Double): Boolean {
            if (type.looseTomtom.isEmpty()) return true
            val loose = place.cats.isEmpty() || place.cats.all { baseCategory(it) in type.looseTomtom }
            if (!loose) return true
            return m <= LOOSE_METERS || type.nameHint?.containsMatchIn(place.name) == true
        }

        /** 7320002 (a kind of sports centre) → 7320. */
        fun baseCategory(id: Int): Int { var v = id; while (v >= 10000) v /= 10; return v }

        internal fun parseNearby(json: String): List<Place> {
            val root = Json.parse(json) as? Map<*, *> ?: error("Unexpected answer from TomTom")
            return (root["results"] as? List<*>).orEmpty().mapNotNull { e ->
                val m = e as? Map<*, *> ?: return@mapNotNull null
                val pos = m["position"] as? Map<*, *> ?: return@mapNotNull null
                val lat = (pos["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
                val lon = (pos["lon"] as? Number)?.toDouble() ?: return@mapNotNull null
                val poi = m["poi"] as? Map<*, *>
                val cats = (poi?.get("categorySet") as? List<*>).orEmpty().mapNotNull { ((it as? Map<*, *>)?.get("id") as? Number)?.toInt() }
                Place((poi?.get("name") as? String).orEmpty(), lat, lon, cats)
            }
        }
    }
}

/**
 * TomTom's answers per (categories, cell), kept for [maxAgeDays] in a small text file (no file: memory only).
 * Line: categories TAB cell TAB epoch day TAB places, places separated by 0x1E, fields by 0x1F.
 */
class OverlapCache(private val file: File?, private val maxAgeDays: Long = 180, private val today: () -> Long = { LocalDate.now().toEpochDay() }) {
    /** [places] null: the search came back full, so the answer is in the square's quarters. */
    private class Entry(val day: Long, val places: List<MapOverlap.Place>?)
    private val map = HashMap<String, Entry>()
    private var dirty = false

    init {
        file?.takeIf { it.isFile }?.useLines { lines ->
            for (line in lines) {
                val f = line.split('\t'); if (f.size != 4) continue
                val day = f[2].toLongOrNull() ?: continue
                val places = if (f[3] == FULL) null else if (f[3].isEmpty()) emptyList() else f[3].split('\u001e').mapNotNull { p ->
                    // lat, lon, name[, category ids separated by commas]
                    val g = p.split('\u001f'); if (g.size != 3 && g.size != 4) null
                    else MapOverlap.Place(g[2], g[0].toDoubleOrNull() ?: return@mapNotNull null, g[1].toDoubleOrNull() ?: return@mapNotNull null,
                        g.getOrNull(3)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty())
                }
                if (!f[1].startsWith(MapOverlap.CELL_VERSION + ":")) continue // an older cell size
                map["${f[0]}\t${f[1]}"] = Entry(day, places)
            }
        }
    }

    private fun fresh(cats: String, cell: String) = map["$cats\t$cell"]?.takeIf { today() - it.day <= maxAgeDays }
    fun get(cats: String, cell: String): List<MapOverlap.Place>? = fresh(cats, cell)?.places
    fun isFull(cats: String, cell: String): Boolean = fresh(cats, cell)?.let { it.places == null } == true
    fun putFull(cats: String, cell: String) { map["$cats\t$cell"] = Entry(today(), null); dirty = true }

    fun put(cats: String, cell: String, places: List<MapOverlap.Place>) { map["$cats\t$cell"] = Entry(today(), places); dirty = true }

    fun save() {
        val f = file ?: return
        if (!dirty) return
        f.parentFile?.mkdirs()
        val clean = { s: String -> s.replace(Regex("[\\t\\n\\r\\u001e\\u001f]"), " ") }
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((k, e) in map) {
                if (today() - e.day > maxAgeDays) continue
                w.write(k); w.write("\t${e.day}\t")
                w.write(e.places?.joinToString("\u001e") { "${it.lat}\u001f${it.lon}\u001f${clean(it.name)}" + if (it.cats.isEmpty()) "" else "\u001f" + it.cats.joinToString(",") } ?: FULL); w.write("\n")
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        dirty = false
    }

    private companion object { const val FULL = "!full" }
}

/**
 * Requests left today for the duplicate check. TomTom's free tier allows 2,500 a day per key, shared with the
 * car link's traffic, so the check stops at [perDay] and leaves the rest for traffic.
 */
class RequestBudget(private val load: (String) -> String?, private val save: (String, String?) -> Unit,
                    private val perDay: Int = 2000, private val today: () -> Long = { LocalDate.now().toEpochDay() }) {
    fun used(): Int = if (load(DAY)?.toLongOrNull() == today()) load(COUNT)?.toIntOrNull() ?: 0 else 0
    fun left(): Int = perDay - used()
    fun take(): Boolean {
        val u = used(); if (u >= perDay) return false
        save(DAY, today().toString()); save(COUNT, (u + 1).toString()); return true
    }
    private companion object { const val DAY = "poiCheck.day"; const val COUNT = "poiCheck.count" }
}

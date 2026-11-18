package app.mapstotom.core

/*
 * Places from Foursquare Open Source Places (Apache 2.0): about 100 million places worldwide, as Parquet files.
 *
 * Two copies:
 * - Free, no account: the one on Source.coop (release 2025-02-06), split by area, so a country needs only a few
 *   parts. files/fsq/index.json (tools/fsq/fsqcountryindex.py) says which files and row groups hold each country.
 * - Newer, monthly: Foursquare's own on Hugging Face. It is gated: the user accepts Foursquare's terms on the
 *   dataset page with their own Hugging Face account and gives the app a read token. The app finds the newest
 *   release and reads the parts whose latitude/longitude touch the country.
 *
 * Foursquare keeps places nobody has confirmed in years. Each place has the date it was last confirmed
 * (date_refreshed) and, when known, the date it closed; closed places are always left out, old ones by choice.
 */

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

/** The free copy's release date, as shown to the user. */
const val FSQ_FREE_RELEASE = "2025-02-06"

private val FSQ_COLUMNS = listOf("country", "fsq_category_labels.list.element", "date_closed", "date_refreshed", "name", "latitude", "longitude")

/** Where the parts of one download come from: a file and the row groups to read. */
private class FsqPart(val file: ParquetFile, val rowGroups: List<Int>)

/**
 * Downloads the places of [kinds] in [countries] from the free copy, leaving out closed places and those not
 * confirmed since [since] (a year). [progress] gets (done, of) row groups.
 */
fun fetchFoursquare(
    index: FsqIndex, countries: List<String>, kinds: Set<PlaceKind>, since: Int, net: Net,
    progress: (Int, Int) -> Unit = { _, _ -> },
): Map<PlaceKind, List<Point>> {
    val cc = countries.map { it.uppercase() }.toSet()
    require(cc.any { it in index.countries }) { "Foursquare has no places for ${cc.joinToString()}" }
    val want = HashMap<String, MutableSet<Int>>()
    for (c in cc) index.countries[c]?.forEach { (f, rgs) -> want.getOrPut(f) { sortedSetOf() } += rgs }
    val parts = want.toSortedMap().map { (f, rgs) -> FsqPart(ParquetFile(HttpRangeSource(net, index.base + f)), rgs.toList()) }
    return readFsqParts(parts, cc, kinds, since, progress)
}

// ------------------------------------------------------------------ Foursquare's own copy (Hugging Face)

const val HF_DATASET = "foursquare/fsq-os-places"
const val HF_DATASET_PAGE = "https://huggingface.co/datasets/$HF_DATASET"

/** A release on Hugging Face: its date and its places files (path, size). */
class FsqRelease(val date: String, val files: List<Pair<String, Long>>)

/** The newest release with places files. Listing the dataset needs no token; reading its files does. */
fun latestFsqRelease(net: Net): FsqRelease {
    val api = "https://huggingface.co/api/datasets/$HF_DATASET/tree/main"
    val dirs = (Json.parse(net.get("$api/release", timeoutMs = 30_000).also { check(it.code == 200) { "Hugging Face answered HTTP ${it.code}" } }.text) as List<*>)
        .mapNotNull { (it as? Map<*, *>)?.get("path") as? String }
        .filter { Regex("release/dt=\\d{4}-\\d{2}-\\d{2}").matches(it) }.sortedDescending()
    for (d in dirs) {
        val r = net.get("$api/$d/places/parquet", timeoutMs = 30_000)
        if (r.code != 200) continue
        val files = (Json.parse(r.text) as List<*>).mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val p = m["path"] as? String ?: return@mapNotNull null
            if (m["type"] == "file" && p.endsWith(".parquet")) p to ((m["size"] as? Long) ?: 0L) else null
        }.sortedBy { it.first }
        if (files.isNotEmpty()) return FsqRelease(d.substringAfter("dt="), files)
    }
    error("no Foursquare release found on Hugging Face")
}

/**
 * Downloads the places of [kinds] in [countries] from Foursquare's own copy on Hugging Face with the user's
 * [token]. Only row groups whose latitude/longitude touch the country are read; if the files aren't sorted by area
 * that could be most of the data set, so above [PLACES_MAX_MB] it stops with [TooBigDownload] instead.
 * [footerCache] keeps the files' indexes between runs. [progress] gets (done, of) steps.
 */
fun fetchFoursquareLatest(
    release: FsqRelease, token: String, countries: List<String>, boxes: CountryBoxes, kinds: Set<PlaceKind>, since: Int, net: Net,
    footerCache: java.io.File? = null, progress: (Int, Int) -> Unit = { _, _ -> },
): Map<PlaceKind, List<Point>> {
    val cc = countries.map { it.uppercase() }.toSet()
    val area = cc.flatMap { boxes.of(it) }
    require(area.isNotEmpty()) { "No area known for ${cc.joinToString()}" }
    val auth = mapOf("Authorization" to "Bearer ${token.trim()}")
    val parts = release.files.mapIndexed { i, (path, _) ->
        progress(i, release.files.size * 2)
        val url = "https://huggingface.co/datasets/$HF_DATASET/resolve/main/$path"
        val pf = ParquetFile(HttpRangeSource(net, url, auth), footerCache?.let { java.io.File(it, "fsq-${release.date}-${path.substringAfterLast('/')}.footer") })
        FsqPart(pf, rowGroupsTouching(pf, "longitude", "longitude", "latitude", "latitude", area))
    }
    val mb = parts.sumOf { plannedBytes(it.file, it.rowGroups, FSQ_COLUMNS) } / 1_000_000
    if (mb > PLACES_MAX_MB) throw TooBigDownload(mb, "Foursquare's ${release.date} release isn't sorted by area, so ${cc.joinToString()}")
    val n = release.files.size
    return readFsqParts(parts, cc, kinds, since) { d, of -> progress(n + d * n / maxOf(of, 1), n * 2) }
}

// ------------------------------------------------------------------ reading

private fun readFsqParts(parts: List<FsqPart>, cc: Set<String>, kinds: Set<PlaceKind>, since: Int, progress: (Int, Int) -> Unit): Map<PlaceKind, List<Point>> {
    val total = parts.sumOf { it.rowGroups.size }
    var done = 0
    val out = kinds.associateWith { LinkedHashMap<String, Point>() }
    for (part in parts) {
        val pf = part.file
        for (rg in part.rowGroups) {
            progress(done++, total)
            val g = pf.rowGroups.getOrNull(rg) ?: continue
            val country = g.columns["country"] ?: continue
            // Row groups sorted by place have an exact country range in their statistics.
            val lo = country.min?.toString(Charsets.UTF_8); val hi = country.max?.toString(Charsets.UTF_8)
            if (lo != null && hi != null && cc.none { it in lo..hi }) continue
            val ctry = pf.read(rg, "country")
            val wanted = ctry.indices.filter { ctry[it] in cc }
            if (wanted.isEmpty()) continue
            val labels = pf.read(rg, "fsq_category_labels.list.element")
            val found = arrayOfNulls<PlaceKind>(ctry.size)
            for (i in wanted) found[i] = PlaceKind.ofFsq(labels[i] as? List<*> ?: continue)?.takeIf { it in kinds }
            if (found.all { it == null }) continue
            val closed = pf.read(rg, "date_closed"); val refreshed = pf.read(rg, "date_refreshed")
            val name = pf.read(rg, "name"); val lat = pf.read(rg, "latitude"); val lon = pf.read(rg, "longitude")
            for (i in wanted) {
                val k = found[i] ?: continue
                if (closed[i] != null) continue
                if ((yearOf(refreshed[i]) ?: 0) < since) continue
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

/** The year of a date stored as text ("2024-05-01"), as days since 1970 (Parquet DATE) or as a timestamp. */
internal fun yearOf(v: Any?): Int? = when (v) {
    is String -> v.take(4).toIntOrNull()
    is Int -> java.time.LocalDate.ofEpochDay(v.toLong()).year
    is Long -> java.time.Instant.ofEpochMilli(if (v > 100_000_000_000_000L) v / 1000 else v).atZone(java.time.ZoneOffset.UTC).year
    else -> null
}

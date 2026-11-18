package app.mapstotom.core

/*
 * Places from Overture Maps (overturemaps.org): a monthly, open data set of about 70 million places, mostly from
 * Meta and Microsoft, under CDLA Permissive 2.0. No account is needed. The files are Parquet with Zstandard
 * compression, sorted by area, and each row group's bounding box statistics say where its places are, so a country
 * needs only the row groups that touch it: about 25 MB for Portugal.
 *
 * Overture gives each place a confidence (0 to 1) that it exists; low ones are often duplicates or closed places.
 * operating_status, when set, says a place has closed.
 */

const val OVERTURE_BUCKET = "https://overturemaps-us-west-2.s3.amazonaws.com/"

/** Leave out places below this confidence, unless the user picks another. */
const val OVERTURE_DEFAULT_CONFIDENCE = 0.6

/** An Overture release: its name ("2026-10-21.0") and its places files. */
class OvertureRelease(val name: String, val files: List<String>)

/** The newest release that has places. */
fun latestOvertureRelease(net: Net): OvertureRelease {
    val releases = s3List(net, "release/", delimiter = true).filter { Regex("release/\\d{4}-\\d{2}-\\d{2}\\.\\d+/").matches(it) }.sortedDescending()
    for (r in releases) {
        val files = s3List(net, "${r}theme=places/type=place/").filter { it.endsWith(".parquet") }
        if (files.isNotEmpty()) return OvertureRelease(r.removePrefix("release/").trimEnd('/'), files.sorted())
    }
    error("no Overture release with places found")
}

/** Keys (or with [delimiter], folders) under [prefix] in Overture's public bucket. */
private fun s3List(net: Net, prefix: String, delimiter: Boolean = false): List<String> {
    val out = mutableListOf<String>()
    var token: String? = null
    do {
        val url = OVERTURE_BUCKET + "?list-type=2&prefix=" + java.net.URLEncoder.encode(prefix, "UTF-8") +
            (if (delimiter) "&delimiter=%2F" else "") + (token?.let { "&continuation-token=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        val r = net.get(url, timeoutMs = 30_000)
        check(r.code == 200) { "Overture's server answered HTTP ${r.code}" }
        val x = r.text
        val tag = if (delimiter) "Prefix" else "Key"
        out += Regex("<$tag>([^<]*)</$tag>").findAll(x).map { it.groupValues[1].replace("&amp;", "&") }.filter { it != prefix }
        token = Regex("<NextContinuationToken>([^<]*)</NextContinuationToken>").find(x)?.groupValues?.get(1)
    } while (token != null)
    return out
}

private val OVERTURE_COLUMNS = listOf("addresses.list.element.country", "taxonomy.hierarchy.list.element", "operating_status", "confidence",
    "names.primary", "bbox.xmin", "bbox.ymin")

/**
 * Downloads the places of [kinds] in [countries], leaving out closed ones and those below [minConfidence].
 * [footerCache] keeps the files' indexes (over a megabyte each) between runs. [progress] gets (done, of) steps.
 */
fun fetchOverture(
    release: OvertureRelease, countries: List<String>, boxes: CountryBoxes, kinds: Set<PlaceKind>, minConfidence: Double, net: Net,
    footerCache: java.io.File? = null, progress: (Int, Int) -> Unit = { _, _ -> },
): Map<PlaceKind, List<Point>> {
    val cc = countries.map { it.uppercase() }.toSet()
    val area = cc.flatMap { boxes.of(it) }
    require(area.isNotEmpty()) { "No area known for ${cc.joinToString()}" }
    val n = release.files.size
    val parts = release.files.mapIndexed { i, key ->
        progress(i, n * 2)
        val pf = ParquetFile(HttpRangeSource(net, OVERTURE_BUCKET + key),
            footerCache?.let { java.io.File(it, "overture-${release.name}-${key.substringAfterLast('/').take(40)}.footer") })
        pf to rowGroupsTouching(pf, "bbox.xmin", "bbox.xmax", "bbox.ymin", "bbox.ymax", area)
    }
    val mb = parts.sumOf { (pf, rgs) -> plannedBytes(pf, rgs, OVERTURE_COLUMNS) } / 1_000_000
    if (mb > PLACES_MAX_MB) throw TooBigDownload(mb, cc.joinToString())
    val total = parts.sumOf { it.second.size }
    var done = 0
    val out = kinds.associateWith { LinkedHashMap<String, Point>() }
    for ((pf, rgs) in parts) for (rg in rgs) {
        progress(n + done++ * n / maxOf(total, 1), n * 2)
        val country = pf.read(rg, "addresses.list.element.country")
        val lon = pf.read(rg, "bbox.xmin"); val lat = pf.read(rg, "bbox.ymin")
        val wanted = country.indices.filter { i ->
            val c = (country[i] as? List<*>)?.firstOrNull { it != null } as? String
            if (c != null) c in cc else {
                // No address: inside the country's boxes then.
                val x = lon[i] as? Double; val y = lat[i] as? Double
                x != null && y != null && area.any { b -> x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3] }
            }
        }
        if (wanted.isEmpty()) continue
        val hierarchy = pf.read(rg, "taxonomy.hierarchy.list.element")
        val found = arrayOfNulls<PlaceKind>(country.size)
        for (i in wanted) found[i] = PlaceKind.ofOverture(hierarchy[i] as? List<*> ?: continue)?.takeIf { it in kinds }
        if (found.all { it == null }) continue
        val status = pf.read(rg, "operating_status"); val confidence = pf.read(rg, "confidence"); val name = pf.read(rg, "names.primary")
        for (i in wanted) {
            val k = found[i] ?: continue
            if (status[i] != null && status[i] != "open") continue
            if (((confidence[i] as? Double) ?: 0.0) < minConfidence) continue
            val la = lat[i] as? Double ?: continue; val lo = lon[i] as? Double ?: continue
            val nm = (name[i] as? String)?.trim().orEmpty()
            if (nm.isEmpty()) continue
            out.getValue(k).putIfAbsent("${Math.rint(la * 1e5)},${Math.rint(lo * 1e5)},$nm", Point(nm, la, lo))
        }
    }
    progress(n * 2, n * 2)
    return out.mapValues { it.value.values.toList() }
}

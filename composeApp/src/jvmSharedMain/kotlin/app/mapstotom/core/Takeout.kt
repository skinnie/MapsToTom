package app.mapstotom.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Google Maps saved places from a Google Takeout export (takeout.google.com, "Saved" and
 * "Maps (your places)"). Each saved list is a CSV (Title, Note, URL, ...); starred and labelled
 * places are GeoJSON. Folder and column names change with the account language, so files are
 * recognised by content, not by name.
 */
class SavedList(val name: String, val rows: List<SavedRow>)

/** One saved place: a title and either coordinates or a Google Maps link to look up. */
class SavedRow(val title: String, val url: String?, val lat: Double? = null, val lon: Double? = null)

/** Read a Takeout .zip, or a single .csv / .json from it. */
fun readTakeout(fileName: String, data: ByteArray): List<SavedList> {
    val lists = mutableListOf<SavedList>()
    fun add(name: String, bytes: ByteArray) {
        val base = nameOf(name).substringBeforeLast('.')
        when {
            name.endsWith(".csv", true) -> parseSavedCsv(bytes.toString(Charsets.UTF_8))?.let { lists += SavedList(base, it) }
            name.endsWith(".json", true) -> parseSavedGeoJson(bytes.toString(Charsets.UTF_8))?.let { lists += SavedList(base, it) }
        }
    }
    if (fileName.endsWith(".zip", true) || (data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte())) {
        ZipInputStream(ByteArrayInputStream(data)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) add(e.name, z.readBytes())
            }
        }
    } else add(fileName, data)
    return lists.filter { it.rows.isNotEmpty() }.sortedBy { it.name.lowercase() }
}

/** A saved-list CSV: the URL column is found by its content; the title is the first column. */
internal fun parseSavedCsv(text: String): List<SavedRow>? {
    val rows = parseCsv(text.removePrefix("﻿"))
    if (rows.size < 2) return null
    val body = rows.drop(1).filter { r -> r.any { it.isNotBlank() } }
    val urlCol = rows[0].indices.firstOrNull { c -> body.any { it.getOrNull(c)?.contains("google.", true) == true && it[c].contains("maps", true) } }
        ?: return null
    return body.mapNotNull { r ->
        val url = r.getOrNull(urlCol)?.trim().orEmpty()
        val title = r.getOrNull(0)?.trim().orEmpty()
        if (url.isEmpty() && title.isEmpty()) null else SavedRow(title, url.ifEmpty { null })
    }
}

/** Starred / labelled places (GeoJSON). Coordinates [0,0] mean "look up the link". */
internal fun parseSavedGeoJson(text: String): List<SavedRow>? {
    val root = runCatching { Json.parse(text) }.getOrNull() as? Map<*, *> ?: return null
    if (root["type"] != "FeatureCollection") return null
    return (root["features"] as? List<*>).orEmpty().mapNotNull { f ->
        val feat = f as? Map<*, *> ?: return@mapNotNull null
        val props = (feat["properties"] as? Map<*, *>).orEmpty()
        val loc = (props["location"] as? Map<*, *>).orEmpty()
        val title = listOf(loc["name"], props["Title"], props["name"], loc["address"], props["address"])
            .firstNotNullOfOrNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
        val url = (props["google_maps_url"] ?: props["Google Maps URL"]) as? String
        val c = ((feat["geometry"] as? Map<*, *>)?.get("coordinates") as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
        val has = c != null && c.size >= 2 && !(c[0] == 0.0 && c[1] == 0.0)
        SavedRow(title, url, if (has) c!![1] else null, if (has) c!![0] else null)
    }
}

/** RFC 4180 CSV: quoted fields, doubled quotes, newlines inside quotes. */
internal fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val f = StringBuilder()
    var q = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (q) {
            if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else q = false }
            else f.append(ch)
        } else when (ch) {
            '"' -> if (f.isEmpty()) q = true else f.append(ch)
            ',' -> { row += f.toString(); f.clear() }
            '\r' -> {}
            '\n' -> { row += f.toString(); f.clear(); rows += row; row = mutableListOf() }
            else -> f.append(ch)
        }
        i++
    }
    if (f.isNotEmpty() || row.isNotEmpty()) { row += f.toString(); rows += row }
    return rows
}

/**
 * Turn saved rows into points, looking up links that have no coordinates (Google first, then
 * OpenStreetMap; at most one request per second). Returns the points and the titles not found.
 */
fun resolveSaved(list: SavedList, net: Net, onProgress: (Int, Int) -> Unit = { _, _ -> }): Pair<List<Point>, List<String>> {
    val pts = mutableListOf<Point>()
    val missed = mutableListOf<String>()
    list.rows.forEachIndexed { i, r ->
        onProgress(i, list.rows.size)
        if (r.lat != null && r.lon != null) { pts += Point(r.title.ifEmpty { pretty(r.lat, r.lon) }, r.lat, r.lon); return@forEachIndexed }
        val p = r.url?.let { u -> runCatching { parseInput(u, net).points.firstOrNull() }.getOrNull() }
            ?: r.title.takeIf { it.isNotEmpty() }?.let { t -> runCatching { geocodeSmart(t, net) }.getOrNull()?.let { Point(t, it.first, it.second) } }
        if (p == null) missed += r.title.ifEmpty { r.url.orEmpty() }
        else pts += p.copy(name = r.title.ifEmpty { p.name })
        Thread.sleep(300) // be gentle with Google
    }
    onProgress(list.rows.size, list.rows.size)
    return pts to missed
}

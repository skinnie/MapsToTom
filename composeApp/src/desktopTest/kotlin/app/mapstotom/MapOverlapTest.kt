package app.mapstotom

import app.mapstotom.core.*
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.test.*

/** A fake TomTom search: answers nearbySearch from a list of places, like the real one (within the radius, nearest first). */
private class FakeTomTom(val places: List<MapOverlap.Place>, val code: Int = 200, val body: String = "") : Net {
    var calls = 0
    override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
        calls++
        if (code != 200) return HttpResponse(code, emptyMap(), body.toByteArray())
        val u = Url.parse(url)
        val lat = u.first("lat")!!.toDouble(); val lon = u.first("lon")!!.toDouble()
        val r = u.first("radius")!!.toDouble(); val limit = u.first("limit")!!.toInt()
        val hits = places.map { it to MapOverlap.meters(lat, lon, it.lat, it.lon) }.filter { it.second <= r }.sortedBy { it.second }.take(limit)
        val body = hits.joinToString(",", "{\"results\":[", "]}") { (p, d) ->
            "{\"dist\":$d,\"poi\":{\"name\":\"${p.name}\"},\"position\":{\"lat\":${p.lat},\"lon\":${p.lon}}}"
        }
        return HttpResponse(200, emptyMap(), body.toByteArray())
    }
}

private fun budget(perDay: Int = 2000): RequestBudget {
    val m = HashMap<String, String>()
    return RequestBudget({ m[it] }, { k, v -> if (v == null) m.remove(k) else m[k] = v }, perDay)
}

/** A point [north] m north and [east] m east of (40.0, -8.0). */
private fun at(north: Double, east: Double, name: String = "p") =
    Point(name, 40.0 + north / 111195.0, -8.0 + east / (111195.0 * cos(Math.toRadians(40.0))))

class MapOverlapTest {
    @Test fun skipsWhatTheMapHasAndKeepsTheRest() {
        val tt = FakeTomTom(listOf(at(0.0, 50.0, "Galp").let { MapOverlap.Place(it.name, it.lat, it.lon) }))
        val osm = listOf(at(0.0, 0.0, "Galp station"), at(0.0, 600.0, "BP"))
        val r = MapOverlap("k", tt, OverlapCache(null), budget()).filter(OsmPoiType.FUEL, osm)
        assertEquals(listOf("BP"), r.kept.map { it.name })
        assertEquals(1, r.skipped.size); assertEquals("Galp", r.skipped[0].tomtomName); assertTrue(r.skipped[0].meters in 49..50, "meters ${r.skipped[0].meters}")
        assertEquals(0, r.unchecked)
        assertEquals(1, r.requests, "both points share one cell, so one search")
    }

    @Test fun matchDistanceIsPerType() {
        val tt = FakeTomTom(listOf(at(200.0, 0.0, "Praia").let { MapOverlap.Place(it.name, it.lat, it.lon) }))
        val p = listOf(at(0.0, 0.0, "Praia X"))
        assertEquals(0, MapOverlap("k", tt, OverlapCache(null), budget()).filter(OsmPoiType.BEACH, p).kept.size, "300 m for beaches")
        assertEquals(1, MapOverlap("k", tt, OverlapCache(null), budget()).filter(OsmPoiType.PHARMACY, p).kept.size, "120 m for pharmacies")
    }

    @Test fun typesWithoutATomTomCategoryAreNotChecked() {
        val tt = FakeTomTom(emptyList())
        val r = MapOverlap("k", tt, OverlapCache(null), budget()).filter(OsmPoiType.LPG, listOf(at(0.0, 0.0)))
        assertEquals(1, r.kept.size); assertEquals(0, tt.calls)
    }

    @Test fun aFullCellIsSplitSoNothingIsMissed() {
        // 150 TomTom places in a tight cluster plus one far corner of the cell: the first search is full (100).
        val cluster = (0 until 150).map { i -> at(5.0 * (i / 15), 5.0 * (i % 15), "c$i").let { MapOverlap.Place(it.name, it.lat, it.lon) } }
        val cell = MapOverlap.cellOf(40.0, -8.0)
        val corner = Point("corner", cell.lat0 + MapOverlap.CELL_LAT - 0.0005, cell.lon0 + MapOverlap.CELL_LON - 0.0005)
        val tt = FakeTomTom(cluster + MapOverlap.Place("ttcorner", corner.lat + 0.0002, corner.lon))
        val r = MapOverlap("k", tt, OverlapCache(null), budget()).filter(OsmPoiType.FUEL, listOf(corner))
        assertEquals(0, r.kept.size, "the corner's TomTom twin is found by a smaller search")
        assertTrue(r.requests in 2..21, "requests ${r.requests}")
    }

    @Test fun cacheMakesASecondRunFreeAndSurvivesAFile() {
        val dir = Files.createTempDirectory("overlap").toFile()
        val f = File(dir, "c.txt")
        val tt = FakeTomTom(listOf(at(10.0, 0.0, "Odd\tname").let { MapOverlap.Place(it.name, it.lat, it.lon) }))
        val osm = listOf(at(0.0, 0.0, "A"), at(100000.0, 100000.0, "B"))
        val first = MapOverlap("k", tt, OverlapCache(f), budget()).filter(OsmPoiType.FUEL, osm)
        assertEquals(2, first.requests)
        val second = MapOverlap("k", tt, OverlapCache(f), budget()).filter(OsmPoiType.FUEL, osm)
        assertEquals(0, second.requests)
        assertEquals(first.kept.map { it.name }, second.kept.map { it.name })
        assertEquals("Odd name", second.skipped.single().tomtomName)
        // Old entries are asked again.
        val later = MapOverlap("k", tt, OverlapCache(f, maxAgeDays = 180, today = { java.time.LocalDate.now().toEpochDay() + 200 }), budget())
            .filter(OsmPoiType.FUEL, osm)
        assertEquals(2, later.requests)
        dir.deleteRecursively()
    }

    @Test fun whenTheDayIsUsedUpTheRestIsKeptAndCounted() {
        val tt = FakeTomTom(listOf(at(0.0, 10.0).let { MapOverlap.Place("x", it.lat, it.lon) }))
        val osm = listOf(at(0.0, 0.0, "A"), at(100000.0, 0.0, "B"), at(200000.0, 0.0, "C"))
        val r = MapOverlap("k", tt, OverlapCache(null), budget(perDay = 1)).filter(OsmPoiType.FUEL, osm)
        assertEquals(1, r.requests)
        assertEquals(2, r.unchecked)
        assertEquals(setOf("B", "C"), r.kept.map { it.name }.toSet())
    }

    @Test fun aWholeCountryTakesFewSearches() {
        // 3,000 stations spread over 300 x 500 km (about Portugal), half of them on TomTom's map.
        val rnd = java.util.Random(1)
        val osm = (0 until 3000).map { at(rnd.nextDouble() * 500_000, rnd.nextDouble() * 300_000, "s$it") }
        val tt = FakeTomTom(osm.filterIndexed { i, _ -> i % 2 == 0 }.map { MapOverlap.Place(it.name, it.lat, it.lon + 0.0003) })
        val r = MapOverlap("k", tt, OverlapCache(null), budget()).filter(OsmPoiType.FUEL, osm)
        assertTrue(r.skipped.size in 1500..1510, "skipped ${r.skipped.size}"); assertEquals(0, r.unchecked); println("country: ${r.requests} requests")
        assertTrue(r.requests < 250, "requests ${r.requests}")
    }

    @Test fun badKeyStops() {
        val e = assertFailsWith<IllegalStateException> {
            MapOverlap("k", FakeTomTom(emptyList(), code = 403), OverlapCache(null), budget()).filter(OsmPoiType.FUEL, listOf(at(0.0, 0.0)))
        }
        assertTrue("key" in e.message!!)
    }

    @Test fun outOfCreditsCountsAsTheDaysLimit() {
        val tt = FakeTomTom(emptyList(), code = 403, body = """{"detailedError":{"code":"InsufficientFunds","message":"You do not have enough credits"}}""")
        val r = MapOverlap("k", tt, OverlapCache(null), budget()).filter(OsmPoiType.FUEL, listOf(at(0.0, 0.0, "A")))
        assertEquals(1, r.unchecked); assertEquals(listOf("A"), r.kept.map { it.name })
    }

    @Test fun budgetResetsEachDay() {
        val m = HashMap<String, String>(); var day = 100L
        val b = RequestBudget({ m[it] }, { k, v -> if (v == null) m.remove(k) else m[k] = v }, perDay = 2, today = { day })
        assertTrue(b.take()); assertTrue(b.take()); assertFalse(b.take()); assertEquals(0, b.left())
        day++
        assertEquals(2, b.left()); assertTrue(b.take())
    }

    @Test fun everyCheckedTypeHasACategory() {
        for (t in OsmPoiType.entries) t.tomtom?.let { assertTrue(it.matches(Regex("\\d+(,\\d+)*")), "$t: $it") }
        assertNull(OsmPoiType.SPEED_CAMERA.tomtom); assertNull(OsmPoiType.LPG.tomtom)
    }
}

/**
 * Install OSM categories into a map folder like the app does, with the duplicate check:
 * -Pm2t.osmInstall=<map dir> -Pm2t.osmCountries=PT -Pm2t.osmKeyFile=<file with the TomTom key> [-Pm2t.osmBudget=1500].
 * Every type except speed cameras; TomTom's answers are cached in the app's own cache file.
 */
class OsmInstallRun {
    @Test fun run() {
        val dir = System.getProperty("m2t.osmInstall") ?: return
        val key = File(System.getProperty("m2t.osmKeyFile")).readText().trim()
        val cc = System.getProperty("m2t.osmCountries", "PT").split(',')
        val net = RealNet()
        val cacheFile = File(DesktopPlatform().dataDir, "tomtom-poi-check.txt")
        val overlap = MapOverlap(key, net, OverlapCache(cacheFile), budget(System.getProperty("m2t.osmBudget", "1500").toInt()))
        val skippedOut = StringBuilder()
        for (t in OsmPoiType.entries.filter { !it.warns }) {
            var all: List<Point>? = null
            for (attempt in 1..3) {
                all = runCatching { fetchOsmPois(t, cc, net) }.onFailure { println("${t.label}: ${it.message} (attempt $attempt)") }.getOrNull()
                if (all != null) break
                Thread.sleep(60_000)
            }
            if (all == null) { println("${t.label}: SKIPPED, OpenStreetMap didn't answer"); continue }
            val r = if (t.tomtom != null) overlap.filter(t, all) else null
            val pts = r?.kept ?: all
            writePoiCategory(LocalFs(File(dir)), "", t.category, pts, categoryIconBmp(t.rgb, t.glyph))
            r?.skipped?.forEach { skippedOut.append("${it.category}\t${it.osmName}\t${it.tomtomName}\t${it.meters}\n") }
            println("${t.label}: OSM ${all.size}, written ${pts.size}, skipped ${r?.skipped?.size ?: 0}, unchecked ${r?.unchecked ?: 0}, requests ${r?.requests ?: 0}")
        }
        File(dir, "skipped.tsv").writeText(skippedOut.toString())
    }
}

/**
 * Real data check, not run by default: -Pm2t.overlap=<dir> with osm.json (Overpass "out center tags") and
 * tt.json ({category: [{name, lat, lon}]}) from a manual download of one area; prints kept/skipped per type.
 */
class MapOverlapDataCheck {
    @Test fun run() {
        val dir = System.getProperty("m2t.overlap") ?: return
        @Suppress("UNCHECKED_CAST")
        val tt = (Json.parse(File(dir, "tt.json").readText()) as Map<String, List<Map<String, Any?>>>)
        val osmJson = File(dir, "osm.json").readText()
        val pairs = mapOf(OsmPoiType.FUEL to listOf("fuel"), OsmPoiType.PHARMACY to listOf("pharmacy"), OsmPoiType.SUPERMARKET to listOf("supermarket"),
            OsmPoiType.EV to listOf("ev"), OsmPoiType.POOL to listOf("pool", "sports"), OsmPoiType.CAMPSITE to listOf("campsite"))
        for ((type, keys) in pairs) {
            val places = keys.flatMap { tt[it].orEmpty() }.map { MapOverlap.Place(it["name"] as String, (it["lat"] as Number).toDouble(), (it["lon"] as Number).toDouble()) }
            val osm = parseOverpass(osmJson, type).filter { p -> overpassMatches(osmJson, type, p) }
            val r = MapOverlap("k", FakeTomTom(places), OverlapCache(null), budget()).filter(type, osm)
            println("${type.label}: OSM ${osm.size}, kept ${r.kept.size}, skipped ${r.skipped.size}, requests ${r.requests}; kept e.g. ${r.kept.take(6).map { it.name }}")
        }
    }

    /** The data file holds several types; keep the points whose tags fit [type]'s first selector. */
    private fun overpassMatches(json: String, type: OsmPoiType, p: Point): Boolean {
        @Suppress("UNCHECKED_CAST")
        val els = (Json.parse(json) as Map<String, Any?>)["elements"] as List<Map<String, Any?>>
        val el = els.firstOrNull { e ->
            val c = (e["center"] as? Map<*, *>) ?: e
            (c["lat"] as? Number)?.toDouble() == p.lat && (c["lon"] as? Number)?.toDouble() == p.lon
        } ?: return false
        val t = el["tags"] as? Map<*, *> ?: return false
        return when (type) {
            OsmPoiType.FUEL -> t["amenity"] == "fuel"
            OsmPoiType.PHARMACY -> t["amenity"] == "pharmacy"
            OsmPoiType.SUPERMARKET -> t["shop"] == "supermarket"
            OsmPoiType.EV -> t["amenity"] == "charging_station"
            OsmPoiType.CAMPSITE -> t["tourism"] == "camp_site"
            OsmPoiType.POOL -> t["leisure"] == "swimming_pool" || (t["leisure"] == "sports_centre" && "swimming" in (t["sport"] as? String).orEmpty())
            else -> false
        }
    }
}

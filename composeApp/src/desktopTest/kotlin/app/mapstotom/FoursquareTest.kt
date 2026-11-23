package app.mapstotom

import app.mapstotom.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Parquet reader against files written by pyarrow (tools/fsq/make_test_parquet.py), and the Foursquare source. */
class FoursquareTest {
    init { ParquetCodecs.zstd = { b, n -> com.github.luben.zstd.Zstd.decompress(b, n) } }
    private fun res(name: String) = javaClass.getResourceAsStream("/parquet/$name")!!.readBytes()
    private val expected = Json.parse(res("expected.json").decodeToString()) as List<*>

    private class Bytes(val b: ByteArray) : RangeSource {
        var requests = 0
        override val size get() = b.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray { requests++; return b.copyOfRange(offset.toInt(), offset.toInt() + length) }
    }

    /**
     * Serves [files] like a web server that answers range requests. URLs in [redirects] answer with a redirect
     * to their target, and only when they carry [token]; the target must then be asked without it.
     */
    private class RangeNet(val files: Map<String, ByteArray>, val redirects: Map<String, String> = emptyMap(), val token: String? = null) : Net {
        val asked = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
            redirects[url]?.let { to ->
                return if (headers["Authorization"] == "Bearer $token") HttpResponse(302, mapOf("Location" to to), ByteArray(0))
                else HttpResponse(401, emptyMap(), ByteArray(0))
            }
            check("Authorization" !in headers) { "token sent to $url" }
            val b = files[url] ?: return HttpResponse(404, emptyMap(), ByteArray(0))
            asked += url
            val r = headers["Range"]!!.removePrefix("bytes=")
            val (s, e) = if (r.startsWith("-")) (b.size - r.drop(1).toInt()) to b.size - 1 else r.substringBefore('-').toInt() to r.substringAfter('-').toInt()
            return HttpResponse(206, mapOf("Content-Range" to "bytes $s-$e/${b.size}"), b.copyOfRange(s, e + 1))
        }
    }

    @Test fun readsEveryLayoutLikePyarrowWroteIt() {
        for (f in listOf("v1-snappy-dict", "v2-none-plain", "v2-snappy-dict")) {
            val src = Bytes(res("$f.parquet"))
            val pf = ParquetFile(src)
            assertEquals(3, pf.rowGroups.size, f)
            var row = 0
            pf.rowGroups.indices.forEach { rg ->
                val cols = listOf("name", "latitude", "longitude", "country", "date_refreshed", "date_closed")
                    .associateWith { pf.read(rg, it) }
                val labels = pf.read(rg, "fsq_category_labels.list.element")
                assertEquals(pf.rowGroups[rg].numRows.toInt(), labels.size, "$f rows in $rg")
                for (i in labels.indices) {
                    val e = expected[row++] as Map<*, *>
                    for ((k, v) in cols) assertEquals(e[k], v[i], "$f row ${row - 1} $k")
                    // A null list reads as an empty one: both mean "no categories".
                    assertEquals((e["fsq_category_labels"] as? List<*>).orEmpty(), labels[i], "$f row ${row - 1} labels")
                }
            }
            assertEquals(expected.size, row)
            val c = pf.rowGroups[0].columns.getValue("country")
            assertEquals("PT", c.min!!.decodeToString(), f)
        }
    }

    @Test fun snappyCopiesThatOverlap() {
        // "abcabcabcab": literal "abc", then copy 8 bytes from 3 back (1-byte offset form).
        val data = byteArrayOf(11, 8, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), ((8 - 4) shl 2 or 1).toByte(), 3)
        assertEquals("abcabcabcab", ParquetFile.snappy(data, 11).decodeToString())
    }

    @Test fun fetchesOnlyTheChosenCountryAndKinds() {
        val url = "https://example.org/places/"
        val net = RangeNet(mapOf(url + "0.parquet" to res("v1-snappy-dict.parquet")))
        // Row group 2 is all ES in the fixture; the index only lists the PT ones.
        val index = FsqIndex("2025-02-06", url, mapOf("PT" to mapOf("0.parquet" to listOf(0, 1)), "ES" to mapOf("0.parquet" to listOf(1, 2))))
        val got = fetchFoursquare(index, listOf("PT"), setOf(PlaceKind.RESTAURANT, PlaceKind.PHARMACY), 2020, net)
        val want = expected.map { it as Map<*, *> }.withIndex().filter { (_, e) ->
            e["country"] == "PT" && e["date_closed"] == null && (e["date_refreshed"] as String) >= "2020" && e["name"] != null
        }
        fun kind(e: Map<*, *>) = PlaceKind.ofFsq((e["fsq_category_labels"] as? List<*>).orEmpty())
        assertEquals(want.count { kind(it.value) == PlaceKind.RESTAURANT }, got.getValue(PlaceKind.RESTAURANT).size)
        assertEquals(want.count { kind(it.value) == PlaceKind.PHARMACY }, got.getValue(PlaceKind.PHARMACY).size)
        assertTrue(got.getValue(PlaceKind.RESTAURANT).all { it.name.endsWith("ção") && it.lat in 38.0..38.6 })
        assertEquals(setOf(PlaceKind.RESTAURANT, PlaceKind.PHARMACY), got.keys)
    }

    @Test fun kindsMatchWholePathSteps() {
        assertEquals(PlaceKind.RESTAURANT, PlaceKind.ofFsq(listOf("Dining and Drinking > Restaurant > Pizzeria")))
        assertEquals(PlaceKind.CAMPGROUND, PlaceKind.ofFsq(listOf("Travel and Transportation > Lodging > Campground")))
        assertEquals(PlaceKind.LODGING, PlaceKind.ofFsq(listOf("Travel and Transportation > Lodging > Hostel")))
        assertEquals(null, PlaceKind.ofFsq(listOf("Dining and Drinking > Restaurants Guide")))
        assertEquals(null, PlaceKind.ofFsq(listOf("Retail")))
    }

    @Test fun bigFilesGetSkippersThatStillReadBack() {
        val pts = (0 until 1000).map { Ov2Entry("P$it", 37.0 + (it % 40) * 0.1, -9.0 + (it / 40) * 0.1) }
        val data = buildOv2(pts)
        val back = readOv2Contents(data)
        assertTrue(back.complete)
        assertEquals(pts.map { it.name }.toSet(), back.entries.map { it.name }.toSet())
        assertEquals(1000, back.entries.size)
        // Every skipper's area holds the places inside it, and its size covers exactly them.
        val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val starts = HashSet<Int>().also { s -> var j = 0; while (j < data.size) { s += j; j += if (data[j].toInt() == 1) 21 else bb.getInt(j + 1) }; s += data.size }
        var i = 0; var skippers = 0
        while (i < data.size) {
            if (data[i].toInt() == 1) {
                skippers++
                val end = i + bb.getInt(i + 1); val e = bb.getInt(i + 5); val n = bb.getInt(i + 9); val w = bb.getInt(i + 13); val s = bb.getInt(i + 17)
                // Passing over a block must land on the start of a record (or the end of the file).
                assertTrue(end in starts, "skipper at $i ends inside a record")
                val inside = readOv2Contents(data.copyOfRange(i + 21, end)).entries
                assertTrue(inside.isNotEmpty() && inside.all { Math.rint(it.lon * 1e5).toInt() in w..e && Math.rint(it.lat * 1e5).toInt() in s..n })
                i += 21
            } else i += bb.getInt(i + 1)
        }
        assertTrue(skippers > 10)
        assertEquals(buildOv2(pts.take(SKIPPER_ABOVE)).size, pts.take(SKIPPER_ABOVE).sumOf { 14 + it.name.length })
    }

    /** [good] as MapsToTom 1.0.0 wrote it: each skipper's size counts itself and its places, not the skippers inside. */
    private fun withOldSkipperSizes(good: ByteArray): ByteArray {
        val out = good.copyOf(); val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val src = java.nio.ByteBuffer.wrap(good).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < good.size) {
            if (good[i].toInt() != 1) { i += src.getInt(i + 1); continue }
            val end = i + src.getInt(i + 1); var j = i + 21; var places = 0
            while (j < end) { if (good[j].toInt() == 1) j += 21 else { val n = src.getInt(j + 1); places += n; j += n } }
            bb.putInt(i + 1, 21 + places); i += 21
        }
        return out
    }

    private val bigPoi = buildOv2((0 until 1000).map { Ov2Entry("P$it", 37.0 + (it % 40) * 0.1, -9.0 + (it / 40) * 0.1) })

    @Test fun repairsOldSkipperSizesExactly() {
        val old = withOldSkipperSizes(bigPoi)
        assertTrue(!old.contentEquals(bigPoi))
        assertTrue(repairSkipperSizes(old).contentEquals(bigPoi))
        assertEquals(null, repairSkipperSizes(bigPoi), "a good file is left alone")
        // Off a record boundary, but not the old fault: not ours to touch.
        val other = bigPoi.copyOf(); java.nio.ByteBuffer.wrap(other).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1, 22)
        assertEquals(null, repairSkipperSizes(other))
        assertEquals(null, repairSkipperSizes(old.copyOf(old.size - 3)), "a cut-off file is left alone")
    }

    @Test fun repairsPoiFilesOnTheCardAndRecoversFromACutShortRepair() {
        val dir = java.nio.file.Files.createTempDirectory("repair").toFile()
        try {
            val fs = LocalFs(dir); val old = withOldSkipperSizes(bigPoi)
            for (n in listOf("PA7369.ov2", "Bares.ov2", "PA7311.ov2.mapstotom-parked", "Cams.ov2")) fs.write("m/$n", old)
            fs.write("m/Cams.ov2.dct", ByteArray(4))
            fs.write("m/PA7369.ov2.mapstotom", old) // the list of added places: not a POI file
            fs.write("m/Good.ov2", bigPoi)
            fs.write("m/Lost.ov2.mapstotom-repair-old", old); fs.write("m/Lost.ov2.mapstotom-repair-new", ByteArray(9))
            assertEquals(setOf("PA7369.ov2", "Bares.ov2", "PA7311.ov2.mapstotom-parked", "Lost.ov2"), repairPoiFiles(fs, "m").toSet())
            for (n in listOf("PA7369.ov2", "Bares.ov2", "PA7311.ov2.mapstotom-parked", "Lost.ov2", "Good.ov2"))
                assertTrue(fs.read("m/$n").contentEquals(bigPoi), n)
            assertTrue(fs.read("m/Cams.ov2").contentEquals(old)); assertTrue(fs.read("m/PA7369.ov2.mapstotom").contentEquals(old))
            assertTrue(fs.list("m").none { "repair" in it })
            assertEquals(emptyList(), repairPoiFiles(fs, "m"))
        } finally { dir.deleteRecursively() }
    }

    // ------------------------------------------------------------------ Overture, Hugging Face, sharing a file

    private val overtureExpected = Json.parse(res("overture-expected.json").decodeToString()) as List<*>
    private val ptBoxes = CountryBoxes(mapOf("PT" to listOf(doubleArrayOf(-10.0, 36.0, -6.0, 43.0))))

    @Test fun readsNestedOvertureColumns() {
        for (f in listOf("overture-v1-zstd", "overture-v2-zstd")) {
            val pf = ParquetFile(Bytes(res("$f.parquet")))
            var row = 0
            for (rg in pf.rowGroups.indices) {
                val names = pf.read(rg, "names.primary"); val h = pf.read(rg, "taxonomy.hierarchy.list.element")
                val country = pf.read(rg, "addresses.list.element.country"); val x = pf.read(rg, "bbox.xmin")
                for (i in names.indices) {
                    val e = overtureExpected[row++] as Map<*, *>
                    assertEquals((e["names"] as? Map<*, *>)?.get("primary"), names[i], "$f $row name")
                    assertEquals(((e["taxonomy"] as? Map<*, *>)?.get("hierarchy") as? List<*>).orEmpty(), h[i], "$f $row hierarchy")
                    assertEquals((e["addresses"] as List<*>).map { (it as Map<*, *>)["country"] }, country[i], "$f $row country")
                    assertEquals((e["bbox"] as Map<*, *>)["xmin"], x[i], "$f $row lon")
                }
            }
            assertEquals(overtureExpected.size, row)
        }
    }

    @Test fun overtureReadsOnlyTheCountrysAreaAndFilters() {
        val base = OVERTURE_BUCKET + "release/x/theme=places/type=place/"
        val net = RangeNet(mapOf(base + "a.parquet" to res("overture-v2-zstd.parquet")))
        val rel = OvertureRelease("x", listOf("release/x/theme=places/type=place/a.parquet"))
        // Row group 2 (lon 2.5…) is outside the box and isn't downloaded.
        val got = fetchOverture(rel, listOf("PT"), ptBoxes, setOf(PlaceKind.RESTAURANT, PlaceKind.CAFE, PlaceKind.CAMPGROUND), 0.6, net)
        val want = overtureExpected.map { it as Map<*, *> }.filter { e ->
            val c = (e["addresses"] as List<*>).firstOrNull()?.let { (it as Map<*, *>)["country"] }
            val lon = (e["bbox"] as Map<*, *>)["xmin"] as Double
            (c == "PT" || (c == null && lon in -10.0..-6.0)) && e["names"] != null && (e["confidence"] as Double) >= 0.6 &&
                (e["operating_status"] == null || e["operating_status"] == "open")
        }
        fun kind(e: Map<*, *>) = PlaceKind.ofOverture(((e["taxonomy"] as? Map<*, *>)?.get("hierarchy") as? List<*>).orEmpty())
        for (k in listOf(PlaceKind.RESTAURANT, PlaceKind.CAFE, PlaceKind.CAMPGROUND))
            assertEquals(want.count { kind(it) == k }, got.getValue(k).size, k.name)
        assertTrue(got.values.flatten().all { it.lon < -6.0 && it.name.startsWith("Sítio") })
    }

    @Test fun huggingFaceTokenGoesOnlyToHuggingFace() {
        val hf = "https://huggingface.co/datasets/$HF_DATASET/resolve/main/release/dt=2026-11-17/places/parquet/p0.parquet"
        val cdn = "https://cdn.example.org/signed/p0"
        val net = RangeNet(mapOf(cdn to res("v1-snappy-dict.parquet")), mapOf(hf to cdn), token = "hf_good")
        val rel = FsqRelease("2026-11-17", listOf("release/dt=2026-11-17/places/parquet/p0.parquet" to 0L))
        val got = fetchFoursquareLatest(rel, "hf_good", listOf("PT"), ptBoxes, setOf(PlaceKind.RESTAURANT), 2020, net)
        assertTrue(got.getValue(PlaceKind.RESTAURANT).isNotEmpty())
        assertTrue(runCatching { fetchFoursquareLatest(rel, "hf_bad", listOf("PT"), ptBoxes, setOf(PlaceKind.RESTAURANT), 2020, net) }
            .exceptionOrNull() is AccessDenied)
    }

    @Test fun datesAsTextOrNumbers() {
        assertEquals(2024, yearOf("2024-05-01")); assertEquals(2021, yearOf(java.time.LocalDate.of(2021, 3, 1).toEpochDay().toInt()))
        assertEquals(2023, yearOf(java.time.Instant.parse("2023-06-01T00:00:00Z").toEpochMilli()))
    }

    private fun card(): Pair<LocalFs, String> {
        // Under build/, not the system temp folder.
        val dir = java.io.File("build/tmp/m2t-places/${System.nanoTime()}").apply { mkdirs() }
        java.io.File(dir, "Map").mkdirs()
        return LocalFs(dir) to "Map"
    }

    @Test fun aPlaceFromBothSourcesIsAddedOnce() {
        val (fs, map) = card()
        val a = installPlaceKind(fs, map, PlaceKind.CAFE, PlaceKind.CAFE.fsqSource, listOf(Point("Café A", 38.7, -9.1), Point("Café B", 38.8, -9.2)), "pt")
        val b = installPlaceKind(fs, map, PlaceKind.CAFE, PlaceKind.CAFE.overtureSource, listOf(Point("Cafe A", 38.7003, -9.1), Point("Café C", 39.0, -9.0)), "pt")
        assertEquals(2, a.added); assertEquals(1, b.added); assertEquals(1, b.nearOther)
        val file = "Map/Cafes e pastelarias.ov2"
        assertEquals(setOf("Café A", "Café B", "Café C"), readOv2Contents(fs.read(file)!!).entries.map { it.name }.toSet())
        assertTrue(fs.exists("Map/Cafes e pastelarias.bmp"))
        // Installing Foursquare again replaces only Foursquare's places.
        installPlaceKind(fs, map, PlaceKind.CAFE, PlaceKind.CAFE.fsqSource, listOf(Point("Café D", 37.0, -8.0)), "pt")
        assertEquals(setOf("Café C", "Café D"), readOv2Contents(fs.read(file)!!).entries.map { it.name }.toSet())
    }

    @Test fun oldFoursquareFilesMoveIntoTheSharedOne() {
        val (fs, map) = card()
        fs.write("Map/Bares (Foursquare).ov2", buildOv2(listOf(Ov2Entry("Bar 1", 38.0, -9.0), Ov2Entry("Bar 2", 38.1, -9.1))))
        fs.write("Map/Bares (Foursquare).bmp", categoryIconBmp(0, 'B'))
        val moved = moveNamedIntoTomTomCategories(fs, map)
        assertTrue(moved.any { "Bares (Foursquare)" in it }, moved.toString())
        assertTrue(!fs.exists("Map/Bares (Foursquare).ov2") && !fs.exists("Map/Bares (Foursquare).bmp"))
        assertEquals(mapOf(PlaceKind.BAR.fsqSource to 2), addedSources(fs, "Map/Bares.ov2"))
        // They count as Foursquare's, so a new Foursquare install replaces them.
        installPlaceKind(fs, map, PlaceKind.BAR, PlaceKind.BAR.fsqSource, listOf(Point("Bar 3", 37.0, -8.0)), "pt")
        assertEquals(listOf("Bar 3"), readOv2Contents(fs.read("Map/Bares.ov2")!!).entries.map { it.name })
    }

    @Test fun countryBoxesShipWithTheApp() {
        val boxes = CountryBoxes.parse(java.io.File("src/commonMain/composeResources/files/country-boxes.json").readText())
        val pt = boxes.of("PT")
        assertTrue(pt.any { b -> -9.14 in b[0]..b[2] && 38.72 in b[1]..b[3] }) // Lisbon
        assertTrue(pt.any { b -> -16.9 in b[0]..b[2] && 32.65 in b[1]..b[3] }) // Funchal
        assertTrue(pt.any { b -> -25.67 in b[0]..b[2] && 37.74 in b[1]..b[3] }) // Ponta Delgada
        assertTrue(boxes.has("es") && !boxes.has("XX"))
    }
}

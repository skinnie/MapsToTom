package app.mapstotom

import app.mapstotom.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Parquet reader against files written by pyarrow (tools/fsq/make_test_parquet.py), and the Foursquare source. */
class FoursquareTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/parquet/$name")!!.readBytes()
    private val expected = Json.parse(res("expected.json").decodeToString()) as List<*>

    private class Bytes(val b: ByteArray) : RangeSource {
        var requests = 0
        override val size get() = b.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray { requests++; return b.copyOfRange(offset.toInt(), offset.toInt() + length) }
    }

    /** Serves [files] like a web server that answers range requests. */
    private class RangeNet(val files: Map<String, ByteArray>) : Net {
        val asked = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
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
        val got = fetchFoursquare(index, listOf("PT"), setOf(FsqPoiType.RESTAURANT, FsqPoiType.PHARMACY), 2020, net)
        val want = expected.map { it as Map<*, *> }.withIndex().filter { (_, e) ->
            e["country"] == "PT" && e["date_closed"] == null && (e["date_refreshed"] as String) >= "2020" && e["name"] != null
        }
        fun kind(e: Map<*, *>) = FsqPoiType.of((e["fsq_category_labels"] as? List<*>).orEmpty())
        assertEquals(want.count { kind(it.value) == FsqPoiType.RESTAURANT }, got.getValue(FsqPoiType.RESTAURANT).size)
        assertEquals(want.count { kind(it.value) == FsqPoiType.PHARMACY }, got.getValue(FsqPoiType.PHARMACY).size)
        assertTrue(got.getValue(FsqPoiType.RESTAURANT).all { it.name.endsWith("ção") && it.lat in 38.0..38.6 })
        assertEquals(setOf(FsqPoiType.RESTAURANT, FsqPoiType.PHARMACY), got.keys)
    }

    @Test fun kindsMatchWholePathSteps() {
        assertEquals(FsqPoiType.RESTAURANT, FsqPoiType.of(listOf("Dining and Drinking > Restaurant > Pizzeria")))
        assertEquals(FsqPoiType.CAMPGROUND, FsqPoiType.of(listOf("Travel and Transportation > Lodging > Campground")))
        assertEquals(FsqPoiType.LODGING, FsqPoiType.of(listOf("Travel and Transportation > Lodging > Hostel")))
        assertEquals(null, FsqPoiType.of(listOf("Dining and Drinking > Restaurants Guide")))
        assertEquals(null, FsqPoiType.of(listOf("Retail")))
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
        var i = 0; var skippers = 0
        while (i < data.size) {
            if (data[i].toInt() == 1) {
                skippers++
                val end = i + bb.getInt(i + 1); val e = bb.getInt(i + 5); val n = bb.getInt(i + 9); val w = bb.getInt(i + 13); val s = bb.getInt(i + 17)
                val inside = readOv2Contents(data.copyOfRange(i + 21, end)).entries
                assertTrue(inside.isNotEmpty() && inside.all { Math.rint(it.lon * 1e5).toInt() in w..e && Math.rint(it.lat * 1e5).toInt() in s..n })
                i += 21
            } else i += bb.getInt(i + 1)
        }
        assertTrue(skippers > 10)
        assertEquals(buildOv2(pts.take(SKIPPER_ABOVE)).size, pts.take(SKIPPER_ABOVE).sumOf { 14 + it.name.length })
    }
}

package app.mapstotom

import app.mapstotom.core.*
import app.mapstotom.core.Point.Kind
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class Ov2Test {
    private fun ov2(vararg p: Point) = ByteArrayOutputStream().apply { p.forEach { write(ov2Record(it)) } }.toByteArray()

    @Test fun parsesAndRebuilds() {
        val data = ov2(Point("Palácio da Pena", 38.787585, -9.390609), Point("castro beef", 39.73058, -8.92995))
        val e = parseOv2(data)!!
        assertEquals(listOf("Palácio da Pena", "castro beef"), e.map { it.name })
        assertContentEquals(data, buildOv2(e))
    }

    @Test fun skipsSkipperAndDeletedRecords() {
        val skipper = ByteArray(21).also { it[0] = 1 }
        val deleted = byteArrayOf(0, 6, 0, 0, 0, 0)
        val e = parseOv2(skipper + deleted + ov2(Point("A", 1.0, 2.0)))!!
        assertEquals(listOf("A"), e.map { it.name })
    }

    @Test fun encryptedIsProtected() {
        val junk = ByteArray(200) { (it * 37 + 11).toByte() }.also { it[0] = 0x2d }
        assertNull(parseOv2(junk))
    }

    @Test fun editKeepsBackup() {
        val dir = Files.createTempDirectory("ov2").toFile()
        val fs = LocalFs(dir)
        fs.write("Map/Google Maps.ov2", ov2(Point("40.09835, -8.50711", 40.09835, -8.50711), Point("castro beef", 39.73058, -8.92995)))
        val target = parseOv2(fs.read("Map/Google Maps.ov2")!!)!![0]
        assertEquals(1, editPoiFile(fs, "Map/Google Maps.ov2") { l -> l.filterNot { sameEntry(it, target) } })
        assertEquals(listOf("castro beef"), parseOv2(fs.read("Map/Google Maps.ov2")!!)!!.map { it.name })
        assertEquals(2, parseOv2(fs.read("Map/Google Maps.ov2.mapstotom-backup")!!)!!.size)
        dir.deleteRecursively()
    }

    @Test fun iconIs22x22Bmp() {
        val b = categoryIconBmp(0xE07000, 'F')
        assertEquals('B'.code.toByte(), b[0]); assertEquals(22, b[18].toInt()); assertEquals(54 + 22 * 68, b.size)
    }

    @Test fun builtInOverlap() {
        assertEquals("Petrol station", builtInEquivalent("Gasolina_Galp"))
        assertEquals("Hotel/motel", builtInEquivalent("Pousadas_de_Portugal_-_2009"))
        assertNull(builtInEquivalent("Abastecimento_Gpl"))
        assertNull(builtInEquivalent("Areas_De_Autocaravanas"))
        assertNull(builtInEquivalent("Safety_Cam_PT_Premium"))
    }

    /** Every POI file on the user's TomTom backup: plain ones parse fully, TomTom's camera service is protected. */
    @Test fun realTomTomBackup() {
        val dir = File("/media/skinnie/05a64eef-3fdf-450f-aaf7-09748f80297c/tomtom-backup-2026-10-26/files/Iberia_1175_13260")
        if (!dir.isDirectory) return
        val cats = scanPoiFolder(LocalFs(dir))
        assertTrue(cats.size > 50)
        assertTrue(cats.filter { it.name.startsWith("Safety_Cam") }.all { it.isProtected })
        assertTrue(cats.filterNot { it.name.startsWith("Safety_Cam") }.none { it.isProtected }, cats.filter { it.isProtected }.joinToString { it.name })
        val unreadable = cats.filter { !it.editable && !it.isProtected }.map { it.name }
        assertTrue(unreadable.size <= 1, unreadable.toString()) // 01_Hoteis uses an unusual record type
        assertTrue(cats.first { it.name == "Praias.pt" }.editable) // header record (type 100) is fine
        assertTrue(cats.first { it.name == "Autogas" }.count > 1000)
    }
}

class OsmTest {
    @Test fun names() {
        assertEquals("Galp, Leiria", osmName(mapOf("brand" to "Galp", "name" to "Posto Galp X", "addr:city" to "Leiria"), OsmPoiType.FUEL))
        assertEquals("Speed camera 50 km/h", osmName(mapOf("maxspeed" to "50"), OsmPoiType.SPEED_CAMERA))
        assertEquals("Motorhome area", osmName(emptyMap<String, String>(), OsmPoiType.MOTORHOME))
    }

    @Test fun parsesNodesAndCentres() {
        val json = """{"elements":[{"type":"node","lat":38.7,"lon":-9.1,"tags":{"brand":"Prio"}},
            {"type":"way","center":{"lat":39.1,"lon":-8.2},"tags":{"name":"Area X"}},{"type":"way","tags":{}}]}"""
        val p = parseOverpass(json, OsmPoiType.FUEL)
        assertEquals(2, p.size); assertEquals("Prio", p[0].name); assertEquals(39.1, p[1].lat)
    }

    @Test fun refusesCamerasInFrance() {
        val net = object : Net { override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int) = error("no network") }
        assertFailsWith<IllegalArgumentException> { fetchOsmPois(OsmPoiType.SPEED_CAMERA, listOf("ES", "FR"), net) }
    }

    @Test fun countriesFromMapName() {
        assertEquals(listOf("PT", "ES", "AD", "GI"), countriesForMap("Iberia_1175_13260"))
    }
}

class TakeoutTest {
    private val csv = "Title,Note,URL,Tags,Comment\n" +
        "\"Tasca do Chico, Lisboa\",,https://www.google.com/maps/place/Tasca/data=!4m2!3m1!1s0xd19:0x2,,\n" +
        "\"Café \"\"A\"\"\",note,https://www.google.com/maps/search/38.71,-9.14,,\n\n"
    private val geo = """{"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"Point","coordinates":[-9.2159,38.6916]},"properties":{"location":{"name":"Torre de Belém"},"google_maps_url":"http://maps.google.com/?cid=1"}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"location":{"name":"Somewhere"},"google_maps_url":"http://maps.google.com/?cid=2"}}]}"""

    @Test fun csvQuotes() {
        val r = parseSavedCsv(csv)!!
        assertEquals(listOf("Tasca do Chico, Lisboa", "Café \"A\""), r.map { it.title })
        assertTrue(r[1].url!!.contains("38.71"))
    }

    @Test fun geoJson() {
        val r = parseSavedGeoJson(geo)!!
        assertEquals("Torre de Belém", r[0].title); assertEquals(38.6916, r[0].lat)
        assertNull(r[1].lat) // [0,0] = look it up
    }

    @Test fun zipFindsListsByContent() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("Takeout/Guardados/Quero ir.csv")); z.write(csv.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("Takeout/Maps (os seus locais)/Locais guardados.json")); z.write(geo.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("Takeout/archive_browser.html")); z.write("<html/>".toByteArray()); z.closeEntry()
        }
        val lists = readTakeout("takeout.zip", bos.toByteArray())
        assertEquals(listOf("Locais guardados", "Quero ir"), lists.map { it.name })
    }

    @Test fun resolvesCoordinatesWithoutNetworkWhenPresent() {
        val net = object : Net { override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int) = HttpResponse(404, emptyMap(), ByteArray(0)) }
        val (pts, missed) = resolveSaved(SavedList("x", parseSavedGeoJson(geo)!!.take(1)), net)
        assertEquals("Torre de Belém", pts.single().name); assertTrue(missed.isEmpty())
    }
}

class GpxTest {
    private fun gpx(n: Int) = buildString {
        append("""<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>Serra loop</name></metadata>""")
        append("<wpt lat=\"38.8\" lon=\"-9.4\"><name>Café</name></wpt><trk><trkseg>")
        // a zig-zag so every point bends the line a little
        for (i in 0 until n) append("<trkpt lat=\"${38.7 + i * 0.001}\" lon=\"${-9.4 + (if (i % 2 == 0) 0.0 else 0.0005 * (i % 7))}\"/>")
        append("</trkseg></trk></gpx>")
    }

    @Test fun readsTrackAndWaypoints() {
        val g = readGpx(gpx(500).toByteArray())
        assertEquals("Serra loop", g.name); assertEquals(500, g.line.size); assertEquals("Café", g.waypoints.single().name)
    }

    @Test fun thinsToTheTomTomLimit() {
        val pts = gpxToPoints(readGpx(gpx(500).toByteArray()))
        assertTrue(pts.size in 40..ITN_MAX, "${pts.size}")
        assertEquals(Kind.STOP, pts.first().kind); assertEquals(Kind.STOP, pts.last().kind)
        assertTrue(pts.drop(1).dropLast(1).all { it.kind == Kind.VIA })
        assertEquals(38.7, pts.first().lat, 1e-9); assertEquals(38.7 + 499 * 0.001, pts.last().lat, 1e-9)
        assertEquals("Serra loop", pts.last().name)
    }

    @Test fun waypointsOnlyBecomeStops() {
        val g = readGpx("""<gpx><wpt lat="1" lon="2"><name>A</name></wpt><wpt lat="3" lon="4"/></gpx>""".toByteArray())
        assertEquals(listOf("A", "Waypoint 2"), gpxToPoints(g).map { it.name })
    }

    @Test fun straightLineKeepsEnds() {
        val line = (0..100).map { Point("p$it", it * 0.01, 0.0) }
        assertEquals(2, thinLine(line, 10).size)
    }
}

class OsmLiveTest {
    /** Real download from OpenStreetMap, written like the app does; -Pm2t.online=1. */
    @Test fun livePortugal() {
        if (System.getProperty("m2t.online") != "1") return
        val dir = Files.createTempDirectory("osm").toFile()
        val fs = LocalFs(dir)
        for (t in listOf(OsmPoiType.SPEED_CAMERA, OsmPoiType.MOTORHOME)) {
            val pts = fetchOsmPois(t, listOf("PT"), RealNet())
            println("${t.label}: ${pts.size}; e.g. ${pts.take(3).map { it.name }}")
            assertTrue(pts.size > 100, "${t.label}: ${pts.size}")
            assertTrue(pts.all { it.lat in 32.0..42.5 && it.lon in -31.5..-6.0 })
            writePoiCategory(fs, "Map", t.category, pts, categoryIconBmp(t.rgb, t.glyph), if (t.warns) ByteArray(10) else null)
            assertEquals(pts.size, parseOv2(fs.read("Map/${t.category}.ov2")!!)!!.size)
        }
        dir.deleteRecursively()
    }
}

class GpxRealTest {
    /** Real GPX files: -Pm2t.gpx=/path/a.gpx,/path/b.gpx. Prints how far the thinned route strays from the original. */
    @Test fun realFiles() {
        val files = System.getProperty("m2t.gpx")?.split(',')?.map(::File)?.filter { it.isFile } ?: return
        for (f in files) {
            val g = readGpx(f.readBytes())
            val pts = gpxToPoints(g)
            val thin = pts.map { it.lat to it.lon }
            fun distM(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
                val k = Math.cos(Math.toRadians(p.first)) * 111_320.0
                val px = p.second * k; val py = p.first * 110_540.0
                val ax = a.second * k; val ay = a.first * 110_540.0; val bx = b.second * k; val by = b.first * 110_540.0
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
                return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            }
            val worst = g.line.maxOf { p -> thin.zipWithNext().minOf { (a, b) -> distM(p.lat to p.lon, a, b) } }
            println("GPX ${f.name}: '${g.name}' ${g.line.size} points, ${g.waypoints.size} waypoints -> ${pts.size}; worst gap ${worst.toInt()} m")
            assertTrue(pts.size <= ITN_MAX)
        }
    }
}

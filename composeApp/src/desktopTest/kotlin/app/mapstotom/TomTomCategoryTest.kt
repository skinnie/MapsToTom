package app.mapstotom

import app.mapstotom.core.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class TomTomCategoryTest {
    /** The header NavCore 9.541 wrote at the start of PA7338.ov2 on a real ONE v4. */
    private val head = byteArrayOf(0x64, 0x15, 0, 0, 0, 0, 0xee.toByte(), 0x6d, 0x33, 0x3d, 0, 0, 0, 0, 0, 0x80.toByte(), 0, 0xc4.toByte(), 0, 0, 0)
    private val batalha = Point("Piscinas Municipais Batalha", 39.65976, -8.82586)

    private fun card(): LocalFs {
        val fs = LocalFs(Files.createTempDirectory("pa").toFile())
        fs.write("Map/PA7338.ov2", ByteArrayOutputStream().apply { write(head); write(ov2Record(batalha)) }.toByteArray())
        return fs
    }
    private fun names(fs: DeviceFs, f: String = "Map/PA7338.ov2") = readOv2Contents(fs.read(f)!!).entries.map { it.name }

    @Test fun keepsYoursAndTheHeaderAndSkipsDuplicates() {
        val fs = card()
        val r = installIntoTomTomCategory(fs, "Map", 7338, OsmPoiType.POOL.source, listOf(
            Point("Piscinas da Batalha", 39.65990, -8.82570), // ~20 m from the user's own
            Point("Piscinas de Leiria", 39.74, -8.80),
            Point("Piscinas de Leiria", 39.74, -8.80),         // twice in the download
        ))
        assertEquals(1, r.added); assertEquals(1, r.nearYours); assertEquals(1, r.yours)
        val data = fs.read("Map/PA7338.ov2")!!
        assertContentEquals(head, data.copyOfRange(0, 21))
        assertEquals(listOf("Piscinas Municipais Batalha", "Piscinas de Leiria"), names(fs))
        assertTrue(fs.exists("Map/PA7338.ov2.mapstotom-backup"))
    }

    @Test fun replacesOnlyItsOwnSource() {
        val fs = card()
        installIntoTomTomCategory(fs, "Map", 7338, OsmPoiType.POOL.source, listOf(Point("Old pool", 39.0, -8.0)))
        installIntoTomTomCategory(fs, "Map", 7338, OsmPoiType.POOL.source, listOf(Point("New pool", 38.0, -8.0)))
        assertEquals(listOf("Piscinas Municipais Batalha", "New pool"), names(fs))
        assertEquals(1, removeFromTomTomCategory(fs, "Map", 7338, OsmPoiType.POOL.source))
        assertEquals(listOf("Piscinas Municipais Batalha"), names(fs))
    }

    @Test fun twoSourcesShareACategory() {
        val fs = LocalFs(Files.createTempDirectory("pa").toFile())
        installIntoTomTomCategory(fs, "Map", 7360, OsmPoiType.CAMPSITE.source, listOf(Point("Parque de Campismo", 40.0, -8.0)))
        val r = installIntoTomTomCategory(fs, "Map", 7360, gpiSource("iOverlander Portugal", "Established Campground"), listOf(
            Point("Camping X", 40.0005, -8.0005), // ~70 m: the same campsite
            Point("Camping Y", 41.0, -8.0),
        ))
        assertEquals(1, r.added); assertEquals(1, r.nearOther)
        // Installing OpenStreetMap again keeps iOverlander's campsite.
        installIntoTomTomCategory(fs, "Map", 7360, OsmPoiType.CAMPSITE.source, listOf(Point("Parque de Campismo", 40.0, -8.0)))
        assertEquals(listOf("Camping Y", "Parque de Campismo"), names(fs, "Map/PA7360.ov2"))
        assertEquals(mapOf("OSM CAMPSITE" to 1, "iOverlander Established Campground" to 1), addedSources(fs, "Map/PA7360.ov2"))
    }

    @Test fun oldNamedFileIsRemoved() {
        val fs = card()
        fs.write("Map/Piscinas (OSM).ov2", ov2Record(Point("A", 1.0, 2.0))); fs.write("Map/Piscinas (OSM).bmp", byteArrayOf(1))
        fs.write("Map/Praias (OSM).ov2", ov2Record(Point("B", 1.0, 2.0)))
        assertEquals(listOf("Piscinas (OSM)"), removeNamedCategory(fs, "Map", OsmPoiType.POOL.translated))
        assertEquals(listOf("PA7338.ov2", "Praias (OSM).ov2"), fs.list("Map"))
    }

    @Test fun oldFilesMoveIn() {
        val fs = LocalFs(Files.createTempDirectory("pa").toFile())
        fs.write("Map/Campsites (OSM).ov2", ov2Record(Point("Parque", 40.0, -8.0))); fs.write("Map/Campsites (OSM).bmp", byteArrayOf(1))
        fs.write("Map/Established Campground (iOverlander).ov2", ov2Record(Point("Same", 40.0003, -8.0)) + ov2Record(Point("Other", 41.0, -8.0)))
        fs.write("Map/Wild Camping (iOverlander).ov2", ov2Record(Point("Wild", 40.0, -8.0)))
        assertEquals(2, moveNamedIntoTomTomCategories(fs, "Map").size)
        assertEquals(listOf("Parque", "Other"), names(fs, "Map/PA7360.ov2"))
        assertEquals(listOf("PA7360.ov2", "PA7360.ov2.mapstotom", "Wild Camping (iOverlander).ov2"), fs.list("Map"))
        assertTrue(moveNamedIntoTomTomCategories(fs, "Map").isEmpty())
    }

    @Test fun mapping() {
        assertEquals(7338, OsmPoiType.POOL.tomtomCategory)
        assertNull(OsmPoiType.LPG.tomtomCategory)
        assertEquals(7360, gpiTomtomCategory("iOverlander Portugal", "Established Campground"))
        assertNull(gpiTomtomCategory("iOverlander Portugal", "Wild Camping"))
        assertEquals("Swimming pools (TomTom category)", tomtomCategoryLabel("PA7338"))
        assertNull(tomtomCategoryLabel("Piscinas (OSM)"))
    }
}

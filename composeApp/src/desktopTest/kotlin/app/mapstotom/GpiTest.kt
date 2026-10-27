package app.mapstotom

import app.mapstotom.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** Builds small GPI files the way POI Loader lays them out. */
private object GpiWriter {
    fun le(n: Int, vararg v: Number) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (x in v) when (x) { is Short -> putShort(x); is Byte -> put(x); else -> putInt(x.toInt()) }
    }.array()
    fun lstr(s: String): ByteArray { val b = s.toByteArray(); return le(8, 4 + b.size + 0, 0).copyOf(4) + "EN".toByteArray() + le(2, b.size.toShort()) + b }
    fun rec(type: Int, main: ByteArray, kids: ByteArray = ByteArray(0)): ByteArray =
        if (kids.isEmpty()) le(8, type.toShort(), 0.toShort(), main.size) + main
        else le(12, type.toShort(), 8.toShort(), main.size + kids.size, main.size) + main + kids
    fun semi(d: Double) = Math.round(d / (180.0 / (1L shl 31))).toInt()
    fun place(name: String, lat: Double, lon: Double, cat: Int) =
        rec(2, le(11, semi(lat), semi(lon), 1.toShort(), 0x42.toByte()) + lstr(name), rec(6, le(2, cat.toShort())))
    fun icon(id: Int): ByteArray {
        val w = 2; val h = 2; val line = w * 4
        val pix = le(16, 0x00FF00FF, 0x00FF0000, 0x000000FF, 0x00FFFFFF) // magenta (transparent), red, blue, white as BGRx
        return rec(5, le(36, id.toShort(), h.toShort(), w.toShort(), line.toShort(), 32.toShort(), 0.toShort(), pix.size, 0x2c, 0, 0x00FF00FF, 1, 0) + pix)
    }
    fun file(): ByteArray {
        val header = rec(0, "GRMREC01".toByteArray() + le(4, 0) + byteArrayOf(1, 3) + le(2, 4.toShort()) + "test".toByteArray())
        val info = rec(1, "POI".toByteArray() + byteArrayOf(0, 0, 0) + "01".toByteArray() + le(4, 65001.toShort(), 0.toShort()),
            rec(17, ByteArray(8) + lstr("Test POIs") + lstr("(c) nobody")))
        val area = rec(8, ByteArray(23), place("Lisbon spot", 38.7, -9.1, 1) + rec(8, ByteArray(23), place("Paris spot", 48.85, 2.35, 1) + place("Água", 41.1, -8.6, 2)))
        val cats = rec(7, le(2, 1.toShort()) + lstr("Campsite"), rec(4, le(2, 7.toShort()))) + rec(7, le(2, 2.toShort()) + lstr("Water"))
        val group = rec(9, lstr("Camping") + area, icon(7) + cats)
        val out = ByteArrayOutputStream(); out.write(header); out.write(info); out.write(group); out.write(rec(0xffff, ByteArray(0)))
        return out.toByteArray()
    }
}

class GpiTest {
    @Test fun readsGroupsCategoriesAndIcons() {
        val g = readGpi(GpiWriter.file())
        assertEquals("Test POIs", g.title)
        assertEquals(listOf("Campsite", "Water"), g.categories.map { it.name })
        val camp = g.categories[0]
        assertEquals("Camping", camp.group)
        assertEquals(listOf("Lisbon spot", "Paris spot"), camp.points.map { it.name })
        assertEquals(38.7, camp.points[0].lat, 1e-6); assertEquals(-9.1, camp.points[0].lon, 1e-6)
        assertEquals("Água", g.categories[1].points.single().name)
        val icon = camp.icon!!
        assertEquals(0, icon.argb[0]); assertEquals(0xFFFF0000.toInt(), icon.argb[1])
        assertNull(g.categories[1].icon)
        val bmp = gpiIconBmp(icon)!!
        assertEquals(54 + 68 * 22, bmp.size)
    }

    @Test fun areaKeepsMapCountries() {
        val f = areaFilter(countriesForMap("Iberia_1175"))!!
        assertTrue(f(Point("a", 38.7, -9.1))); assertTrue(f(Point("madeira", 32.65, -16.9)))
        assertFalse(f(Point("paris", 48.85, 2.35)))
        assertTrue(f(Point("bilbao", 43.26, -2.93))); assertTrue(f(Point("menorca", 39.95, 4.1)))
        assertFalse(f(Point("camargue", 43.45, 4.42))); assertFalse(f(Point("perpignan", 42.7, 2.9))); assertFalse(f(Point("banyuls", 42.48, 3.12))); assertFalse(f(Point("tangier", 35.77, -5.81)))
        assertNull(areaFilter(listOf("ZZ")))
    }

    @Test fun rejectsOtherFiles() {
        assertFailsWith<IllegalArgumentException> { readGpi(ByteArray(100)) }
    }
}

class GpiRealTest {
    /** A real .gpi: -Pm2t.gpi=/path/file.gpi. Prints categories and writes their icons to build/gpi-icons. */
    @Test fun realFile() {
        val f = System.getProperty("m2t.gpi")?.let(::File)?.takeIf { it.isFile } ?: return
        val t0 = System.currentTimeMillis()
        val g = readGpi(f.readBytes())
        val keep = areaFilter(countriesForMap("Iberia"))!!
        println("${g.title}: ${g.categories.size} categories, ${g.categories.sumOf { it.points.size }} places, ${System.currentTimeMillis() - t0} ms")
        val dir = File("build/gpi-icons").apply { mkdirs() }
        for (c in g.categories) {
            println("  ${c.group} / ${c.name}: ${c.points.size}, Iberia ${c.points.count(keep)}, icon ${c.icon?.let { "${it.width}x${it.height}" }}; e.g. ${c.points.filter(keep).take(2).map { it.name }}")
            gpiIconBmp(c.icon)?.let { File(dir, safeFilename(c.name) + ".bmp").writeBytes(it) }
        }
        assertTrue(g.categories.sumOf { it.points.size } > 1000)
    }
}

class GpiInstall {
    /** Install categories of a .gpi straight into a map folder, like the app: -Pm2t.gpi=file -Pm2t.gpiInstall=<map dir>:Cat A,Cat B */
    @Test fun install() {
        val arg = System.getProperty("m2t.gpiInstall") ?: return
        val f = File(System.getProperty("m2t.gpi")); val map = File(arg.substringBeforeLast(':'))
        val want = arg.substringAfterLast(':').split(',').map { it.trim() }
        val g = readGpi(f.readBytes())
        val keep = areaFilter(countriesForMap(map.name))!!
        val prefix = g.title.substringBefore(' ')
        for (c in g.categories.filter { it.name in want }) {
            val pts = c.points.filter(keep)
            writePoiCategory(LocalFs(map), "", "$prefix ${c.name}", pts, gpiIconBmp(c.icon) ?: categoryIconBmp(0x6B4E2E, 'P'))
            println("$prefix ${c.name}: ${pts.size}")
        }
    }
}

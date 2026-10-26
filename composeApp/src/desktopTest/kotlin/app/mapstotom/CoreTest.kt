package app.mapstotom

import app.mapstotom.core.*
import app.mapstotom.core.Point.Kind
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import kotlin.test.*

private const val DIR_URL = "https://www.google.com/maps/dir/Lisbon/Sintra/Cascais/@38.7601316,-9.4559645,11z/data=!4m19!4m18" +
    "!1m5!1m1!1s0xd19331a61e4f33b:0x400ebbde49036d0!2m2!1d-9.1393366!2d38.7222524" +
    "!1m5!1m1!1s0xd1ed09be5d23323:0x400ebbde4903720!2m2!1d-9.3229213!2d38.8233416" +
    "!1m5!1m1!1s0xd1ec47379b79a97:0x870d3b7e1807bbc2!2m2!1d-9.4303646!2d38.6960614?entry=ttu"
private const val DIR_VIA_URL = "https://www.google.com/maps/dir/Lisbon/Sintra/Cascais/@38.7601316,-9.4559645,11z/data=!4m25!4m24" +
    "!1m10!1m1!1s0xd19331a61e4f33b:0x400ebbde49036d0!2m2!1d-9.1393366!2d38.7222524" +
    "!3m4!1m2!1d-9.2362374!2d38.6706256!3s0xd1ecba9a2b6d26f:0x5ad67c75ad299d60" +
    "!1m5!1m1!1s0xd1ed09be5d23323:0x400ebbde4903720!2m2!1d-9.3229213!2d38.8233416" +
    "!1m5!1m1!1s0xd1ec47379b79a97:0x870d3b7e1807bbc2!2m2!1d-9.4303646!2d38.6960614!3e0?entry=ttu"
private const val PLACE_URL = "https://www.google.com/maps/place/Torre+de+Bel%C3%A9m/@38.6916,-9.2185,17z/data=!3m1!4b1!4m6!3m5" +
    "!1s0xd1ecb5d6c7e5a4f:0x2b2d2c2a!8m2!3d38.6915837!4d-9.2159767!16zL20vMDN4eDk?entry=ttu"

private fun near(expected: Double, actual: Double, eps: Double = 1e-7) = assertTrue(kotlin.math.abs(expected - actual) < eps, "$expected != $actual")

class ParseTest {
    @Test fun dir() {
        val r = parseInput(DIR_URL, null)
        assertEquals(listOf("Lisbon", "Sintra", "Cascais"), r.points.map { it.name })
        near(38.8233416, r.points[1].lat); near(-9.3229213, r.points[1].lon)
        assertTrue(r.points.all { it.kind == Kind.STOP })
    }

    @Test fun dirWithDraggedVia() {
        val r = parseInput(DIR_VIA_URL, null)
        assertEquals(listOf(Kind.STOP, Kind.VIA, Kind.STOP, Kind.STOP), r.points.map { it.kind })
        near(38.6706256, r.points[1].lat); near(-9.2362374, r.points[1].lon)
    }

    @Test fun place() {
        val r = parseInput(PLACE_URL, null)
        assertEquals(1, r.points.size)
        assertEquals("Torre de Belém", r.points[0].name)
        near(38.6915837, r.points[0].lat)
    }

    @Test fun coordsAndDms() {
        val r = parseInput("38.7, -9.1\n38°42'05.0\"N 9°08'21.6\"W", null)
        assertEquals(2, r.points.size)
        near(38.70139, r.points[1].lat, 1e-4); near(-9.13933, r.points[1].lon, 1e-4)
    }

    @Test fun apiAndQ() {
        val r = parseInput("https://www.google.com/maps/dir/?api=1&origin=38.7,-9.1&destination=38.8,-9.3" +
            "&waypoints=38.75,-9.2\nhttps://maps.google.com/maps?q=41.15,-8.61", null)
        assertEquals(listOf(38.7 to -9.1, 38.75 to -9.2, 38.8 to -9.3, 41.15 to -8.61), r.points.map { it.lat to it.lon })
    }

    @Test fun placeCoordsAndMapView() {
        val r = parseInput("https://www.google.com/maps/place/38.70,-9.21\nhttps://www.google.com/maps/@40.1,-8.2,15z", null)
        assertEquals(listOf(38.70 to -9.21, 40.1 to -8.2), r.points.map { it.lat to it.lon })
    }

    @Test fun yourLocationOrigin() {
        val url = "https://www.google.com/maps/dir//Sintra/@38.8,-9.3,11z/data=!4m9!4m8!1m0" +
            "!1m5!1m1!1s0xd1ed09be5d23323:0x400ebbde4903720!2m2!1d-9.3229213!2d38.8233416"
        val r = parseInput(url, null)
        assertEquals(listOf("Sintra"), r.points.map { it.name })
        assertTrue(r.warnings.any { "your location" in it })
    }
}

/** Phone share links (q=<name, address>&ftid=<id>) carry no coordinates. */
class GooglePlaceLookupTest {
    @Test fun readsPinFromPlaceDetails() {
        val page = """<a href="/maps/preview/place?q=x&amp;pb=%211m1">x</a>"""
        val details = ")]}'\n" + "[null,null,null,null,null,null,[null,null,null,null,null,null,null,null,null," +
            "[null,null,39.7305163,-8.9299386],null,\"Restaurante Castros Reef & Beef\"]]"
        val calls = mutableListOf<String>()
        val fake = object : Net {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
                calls += url
                return HttpResponse(200, emptyMap(), (if (calls.size == 1) page else details).toByteArray())
            }
        }
        val url = "https://maps.google.com/maps?q=Restaurante+Castros+Reef+%26+Beef,+R.+dos+Carreirinhos+79," +
            "+2430-177+Marinha+Grande,+Portugal&ftid=0xd220fc1e7dc5b47:0xc7335bacf4e93f77&entry=gps"
        val r = parseInput(url, fake)
        assertEquals(1, r.points.size)
        assertEquals("Restaurante Castros Reef & Beef", r.points[0].name)
        near(39.7305163, r.points[0].lat); near(-8.9299386, r.points[0].lon)
        assertTrue("ftid=" in calls[0])
        assertTrue(calls[1].startsWith("https://www.google.com/maps/preview/place?q=x&pb="))
    }
}

class FilesTest {
    private fun tmp(): File = Files.createTempDirectory("m2t").toFile().also { it.deleteOnExit() }

    @Test fun itn() {
        val pts = parseInput(DIR_VIA_URL, null).points
        val data = buildItn(pts)
        val lines = data.toString(CP1252).lines().filter { it.isNotEmpty() }
        assertEquals("-913934|3872225|Lisbon|4|", lines[0])
        assertTrue(lines[1].endsWith("|0|"))
        assertTrue(lines.last().endsWith("|Cascais|2|"))
        assertEquals(4, readItn(data).size)
    }

    @Test fun ov2RoundtripAndAppend() {
        val fs = LocalFs(tmp())
        val pts = parseInput(PLACE_URL, null).points
        writeOv2(fs, "", "Google Maps", pts)
        val (_, added) = writeOv2(fs, "", "Google Maps", pts + Point("Ação", 38.5, -9.0))
        assertEquals(1, added) // Torre de Belém already there
        val back = readOv2(fs.read("Google Maps.ov2")!!)
        assertEquals(listOf("Torre de Belém", "Ação"), back.map { it.name })
        near(38.69158, back[0].lat, 1e-5)
        assertTrue(fs.exists("Google Maps.bmp"))
    }

    @Test fun writeItnIntoDeviceLayout() {
        val fs = LocalFs(tmp())
        fs.write("ttgo.bif", ByteArray(0))
        val path = writeItn(fs, "My trip: day/1", listOf(Point("A", 1.0, 2.0), Point("B", 3.0, 4.0)))
        assertEquals("itn/My trip_ day_1.itn", path)
        assertTrue(fs.exists("ITN/my trip_ DAY_1.itn")) // lookups ignore case, like FAT
    }
}

class TlsTest {
    @Test fun embeddedDigiCertRootIsTheRightOne() {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(TomTomTls.DIGICERT_HA_EV_ROOT.toByteArray()))
        val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        assertEquals("74:31:E5:F4:C3:C1:CE:46:90:77:4F:0B:61:E0:54:40:88:3B:A9:A0:1E:D0:0B:A6:AB:D7:80:6E:D3:B1:18:CF", fp)
    }
}

class NamingTest {
    @Test fun coordNames() {
        assertTrue(isCoordName("40.09835, -8.50711"))
        assertTrue(isCoordName("38.6916,-9.2160"))
        assertFalse(isCoordName("Palácio Nacional da Pena, Sintra"))
    }

    @Test fun reverseNames() {
        val place = Json.parse("""{"name":"Torre de Belém","address":{"road":"Avenida Brasília","city":"Lisboa"}}""") as Map<*, *>
        assertEquals("Torre de Belém, Lisboa", reverseName(place))
        val street = Json.parse("""{"name":"","address":{"road":"Rua da Paz","house_number":"12","village":"Ançã"}}""") as Map<*, *>
        assertEquals("Rua da Paz 12, Ançã", reverseName(street))
    }

    @Test fun coordinateInputGetsAName() {
        val fake = object : Net {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
                assertTrue(url.startsWith("https://nominatim.openstreetmap.org/reverse?"))
                return HttpResponse(200, emptyMap(), """{"name":"Torre de Belém","address":{"city":"Lisboa"}}""".toByteArray())
            }
        }
        assertEquals("Torre de Belém, Lisboa", parseInput("38.6916, -9.2160", fake).points.single().name)
        // offline: keeps the coordinates
        assertEquals("38.69160, -9.21600", parseInput("38.6916, -9.2160", null).points.single().name)
    }
}

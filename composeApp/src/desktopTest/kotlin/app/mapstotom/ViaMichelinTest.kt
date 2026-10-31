package app.mapstotom

import app.mapstotom.core.*
import app.mapstotom.core.Point.Kind
import kotlin.test.*

// The address bar of a ViaMichelin route (Lisbon -> a step -> Évora, no tolls), shortened to the parts we read.
private val VM_URL = "https://www.viamichelin.pt/routes/results?bounds=-9.2~38.5~-7.8~38.8&car=1~x~false~false~DIESEL~X" +
    "&itinerary=" + percentEncode("""{"l":"Praça do Comércio, 1100-148 Lisboa","c":{"lng":-9.1365,"lat":38.7076},"isDeparture":true}""") +
    "~" + percentEncode("""{"l":"Montemor-o-Novo {centro} ~ praça","c":{"lng":-8.2167,"lat":38.6486}}""") +
    "~" + percentEncode("""{"l":"Praça do Giraldo, 7000 Évora","c":{"lng":-7.9086,"lat":38.5711},"isArrival":true}""") +
    "&selectedRoute=0&traffic=CLOSINGS&travelMode=CAR&tripConstraint=NO_TOLL"

private fun percentEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

class ViaMichelinTest {
    @Test fun readsStopsAndRouteOption() {
        assertTrue(isViaMichelinUrl(VM_URL))
        assertTrue(isViaMichelinUrl("https://www.viamichelin.com/routes/results?itinerary=x"))
        assertFalse(isViaMichelinUrl("https://www.google.com/maps/dir/a/b"))
        val r = parseInput(VM_URL, null)
        assertEquals(listOf("Praça do Comércio, 1100-148 Lisboa", "Montemor-o-Novo {centro} ~ praça", "Praça do Giraldo, 7000 Évora"),
            r.points.map { it.name })
        assertTrue(r.points.all { it.kind == Kind.STOP })
        assertEquals(38.7076, r.points[0].lat); assertEquals(-9.1365, r.points[0].lon)
        assertEquals(-7.9086, r.points[2].lon)
        assertEquals(Avoid(tolls = true), r.avoid)
        assertEquals(emptyList(), r.warnings)
    }

    @Test fun routeOptions() {
        assertEquals(Avoid(), viaMichelinAvoid("NONE").first)
        assertEquals(Avoid(motorways = true, borders = true), viaMichelinAvoid("NO_MOTORWAY_AND_NO_BORDER_CROSSING").first)
        assertEquals(Avoid(ferries = true), viaMichelinAvoid("NO_FERRY").first)
        assertNotNull(viaMichelinAvoid("NO_LOW_EMISSION_ZONE").second)
        assertFalse(viaMichelinAvoid("NO_LOW_EMISSION_ZONE").first.any)
        assertEquals("tolls, motorways", Avoid(tolls = true, motorways = true).label())
    }

    @Test fun linkWithoutRoute() {
        val r = parseInput("https://www.viamichelin.com/routes", null)
        assertTrue(r.points.isEmpty())
        assertTrue(r.warnings.single().startsWith("No route in this ViaMichelin link"))
        assertNull(r.avoid)
    }

    @Test fun polyline6() {
        // Google's documented example, read with 6 decimals instead of 5
        val p = decodePolyline6("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(listOf(3.85 to -12.02, 4.07 to -12.095, 4.3252 to -12.6453), p)
    }

    @Test fun followRoadsWithValhallaWhenAvoiding() {
        val calls = mutableListOf<String>()
        val fake = object : Net {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
                calls += url
                val body = """{"trip":{"legs":[{"shape":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","summary":{"length":120.5}}]}}"""
                return HttpResponse(200, emptyMap(), body.toByteArray())
            }
        }
        val stops = listOf(Point("A", 3.85, -12.02), Point("B", 4.3252, -12.6453))
        val out = addShapingPoints(stops, 3, fake, Avoid(tolls = true))
        assertEquals(listOf("A", "Shape 1.1", "B"), out.map { it.name })
        assertEquals(Kind.SHAPE, out[1].kind)
        val q = percentDecode(calls.single().substringAfter("json="), plus = true)
        assertTrue(calls.single().startsWith("https://valhalla1.openstreetmap.de/route?"))
        assertTrue("\"exclude_tolls\":true" in q, q)
        assertTrue("\"type\":\"break\"" in q)
        assertEquals("""{"lat":3.850000,"lon":-12.020000,"type":"break"}""", Regex("""\{"lat[^}]*\}""").find(q)!!.value)
    }

    @Test fun viaPointsPassThrough() {
        val q = valhallaRequest(listOf(Point("A", 1.0, 2.0), Point("v", 1.5, 2.5, Kind.VIA), Point("B", 2.0, 3.0)), Avoid(motorways = true))
        assertTrue("\"type\":\"through\"" in q)
        assertTrue("\"use_highways\":0" in q)
    }

    /** Real route from the FOSSGIS Valhalla server, no tolls; -Pm2t.online=1. */
    @Test fun onlineNoTolls() {
        if (System.getProperty("m2t.online") != "1") return
        val pts = parseInput(VM_URL, null).points
        val out = addShapingPoints(pts, 20, RealNet(), Avoid(tolls = true))
        assertEquals(20, out.size)
        assertEquals(17, out.count { it.kind == Kind.SHAPE })
    }
}

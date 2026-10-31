package app.mapstotom

import app.mapstotom.core.Json
import app.mapstotom.link.live.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

/** Replays the golden request/reply pairs made by go950-navcore-9541/server/make_goldens.py from real ONE v4 requests. */
class LiveServerTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/goldens/$name")!!.readBytes()
    private fun json(name: String) = Json.parse(res("inputs/$name").toString(Charsets.UTF_8)) as Map<*, *>

    private fun server(store: LiveStore = MemoryLiveStore()): LiveServer {
        val tile = TomTomTraffic.parse(json("tomtom_lisboa_tile.json"))
        // same filter as make_goldens.py: unrounded lon/lat * 1e5 inside the box
        val src = IncidentSource { bb ->
            tile.filter { i -> bb == null || (i.lon * 1e5 in minOf(bb[0], bb[2]).toDouble()..maxOf(bb[0], bb[2]).toDouble() &&
                i.lat * 1e5 in minOf(bb[1], bb[3]).toDouble()..maxOf(bb[1], bb[3]).toDouble()) }
        }
        val meteo = json("openmeteo_sample.json")
        val f = json("fuel.prices.json")
        val set = FuelPriceSet((f["setid"] as Number).toLong(), f["name"] as String,
            (f["types"] as Map<*, *>).entries.associate { (k, v) -> (k as String).toInt() to (v as List<*>).map { (it as Number).toInt() } })
        return LiveServer(LiveConfig(), src, { _, _ -> meteo }, { id -> set.takeIf { it.setId.toString() == id } }, store)
    }

    private fun goldens(): List<String> {
        val index = Json.parse(res("index.json").toString(Charsets.UTF_8)) as Map<*, *>
        return (index["pairs"] as List<*>).map { (it as Map<*, *>)["file"] as String }
    }

    private fun request(name: String): Triple<String, String, ByteArray> {
        val r = res("$name.req"); val nl = r.indexOf('\n'.code.toByte())
        val (method, path) = String(r, 0, nl, Charsets.ISO_8859_1).split(' ', limit = 2)
        return Triple(method, path, r.copyOfRange(nl + 1, r.size))
    }

    @Test fun allGoldensByteIdentical() {
        val s = server()
        val bad = ArrayList<String>()
        val names = goldens()
        for (name in names) {
            val (method, path, body) = request(name)
            val got = s.respond(method, path, body).body
            if (!got.contentEquals(res("$name.resp"))) bad += name
        }
        println("goldens: ${names.size - bad.size}/${names.size} byte-identical")
        assertEquals(22, names.size)
        assertEquals(emptyList(), bad)
    }

    @Test fun overHttp() {
        val s = server()
        val (method, path, _) = request("08_services_directory")
        val out = ByteArrayOutputStream()
        s.serve(ByteArrayInputStream("$method $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()), out)
        val text = out.toString(Charsets.ISO_8859_1.name())
        assertTrue(text.startsWith("HTTP/1.0 200 OK\r\n"))
        assertTrue("Connection: close" in text)
        assertTrue(text.endsWith(res("08_services_directory.resp").toString(Charsets.ISO_8859_1)))

        // a POST with a body
        val (_, p2, b2) = request("15_h204_kind3")
        val out2 = ByteArrayOutputStream()
        s.serve(ByteArrayInputStream("POST $p2 HTTP/1.1\r\nContent-Length: ${b2.size}\r\n\r\n".toByteArray() + b2), out2)
        assertTrue(out2.toByteArray().toList().takeLast(18) == res("15_h204_kind3.resp").toList())
    }

    @Test fun sessionSurvivesRestart() {
        val store = MemoryLiveStore()
        val s1 = server(store)
        for (n in listOf("15_h204_kind3")) { val (m, p, b) = request(n); s1.respond(m, p, b) }
        assertEquals(setOf("Z00000T00001"), store.lastLogins().keys)
        val s2 = server(store)   // "app restart": no new login, the later calls must still decrypt
        for (n in listOf("17_h204_kind16", "18_h204_kind14")) {
            val (m, p, b) = request(n)
            assertContentEquals(res("$n.resp"), s2.respond(m, p, b).body, n)
        }
    }

    @Test fun xmsg() {
        assertContentEquals("00000016020400000".toByteArray().copyOf(16), XMsg.reply(emptyList(), 204))
        val r = XMsg.reply(listOf(XMsg.int(-5), XMsg.str("ab"), XMsg.buf(byteArrayOf(0, 1)), XMsg.table(listOf(listOf("a".toByteArray(), "b".toByteArray())))))
        val (err, args) = XMsg.parse(r)
        assertEquals(0, err)
        assertEquals(-5L, args[0].second)
        assertEquals("1-00000000005", String(XMsg.int(-5)))
        assertContentEquals("ab".toByteArray(), args[1].second as ByteArray)
        assertEquals(1, (args[3].second as List<*>).size)
    }

    @Test fun hermesRoundTrip() {
        val v = HStruct(1 to 3L, 2 to "Z1", 3 to byteArrayOf(1, 2), 4 to listOf(true, null, -70000L, 5_000_000_000L))
        assertEquals(v, Hermes.decode(Hermes.encode(v)))
        assertEquals(v, Hermes.unpackBody(Hermes.packBody(v)))
        val big = List(50) { HStruct(10 to it.toLong(), 13 to ">-930560x3879135") }
        val packed = Hermes.packBody(big)
        assertEquals(0x10, packed[0].toInt())          // compressed when smaller
        assertEquals(big, Hermes.unpackBody(packed))
    }

    @Test fun sessionKeyDerivation() {
        val k = Hercules.sessionKey(Hercules.LOGIN_KEY, "Z00000T00001".toByteArray(), "123456789012345".toByteArray(), "SIM".toByteArray())
        assertEquals(0, k.size % 8)
        assertTrue(k.size <= 56)
        val plain = Hercules.blowfish(Hercules.LOGIN_CIPHER_KEY, Hercules.LOGIN_KEY, true)
        assertContentEquals(plain, k.copyOf(8))
        assertEquals(9, k[8].toInt())
        assertContentEquals(Hercules.LOGIN_KEY, Hercules.blowfish(Hercules.LOGIN_CIPHER_KEY, plain, false))
        val long = Hercules.sessionKey(Hercules.LOGIN_KEY, ByteArray(40) { 'a'.code.toByte() }, ByteArray(20), ByteArray(0))
        assertEquals(56, long.size)
    }

    @Test fun majorRoads() {
        assertTrue(TomTomTraffic.isMajor(listOf("A1")))
        assertTrue(TomTomTraffic.isMajor(listOf("N-II")))
        assertTrue(TomTomTraffic.isMajor(listOf("EN125")))
        assertFalse(TomTomTraffic.isMajor(listOf("EM588")))
        assertFalse(TomTomTraffic.isMajor(listOf("CM1185")))
        assertFalse(TomTomTraffic.isMajor(listOf("EN125"), motorwayOnly = true))
        assertTrue(TomTomTraffic.isMajor(listOf("IC19"), motorwayOnly = true))
    }

    @Test fun tomtomSourceAsksForLengthAndRoadNumbers() {
        val urls = ArrayList<String>()
        val net = object : app.mapstotom.core.Net {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): app.mapstotom.core.HttpResponse {
                synchronized(urls) { urls += url }
                return app.mapstotom.core.HttpResponse(200, emptyMap(), res("inputs/tomtom_lisboa_tile.json"))
            }
        }
        val src = TomTomSource({ "KEY" }, net)
        val got = src.incidents(longArrayOf(-950000, 3850000, -910000, 3890000))
        assertTrue(got.isNotEmpty())
        assertEquals(1, urls.size)
        val u = java.net.URLDecoder.decode(urls[0], "UTF-8")
        assertTrue("length,roadNumbers" in u, u)
        assertTrue("bbox=-10.000000,38.000000,-9.000000,39.000000" in u, u)
        src.incidents(longArrayOf(-950000, 3850000, -910000, 3890000))
        assertEquals(1, urls.size)                     // cached
        assertTrue(TomTomSource({ null }, net).incidents(longArrayOf(0, 0, 1, 1)).isEmpty())   // no key: no traffic
    }
}

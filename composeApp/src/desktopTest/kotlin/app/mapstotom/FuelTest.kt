package app.mapstotom

import app.mapstotom.core.*
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

class FuelTest {
    private val alves = FuelStation("PT:86760", 39.46772, -8.98081, "ALVES BANDEIRA Turquel, R. José Diogo Ribeiro, EN 8/6, Turquel", mapOf(1 to 226))

    /** Header and first record as in FuelPT.ov2, which a ONE v4 (NavCore 9.541) reads with names and prices. */
    @Test fun ov2MatchesTheFileTheTomTomReads() {
        val out = buildFuelOv2(listOf(alves, FuelStation("x", 38.7, -9.1, "Galp €", emptyMap())), setId = 1)
        assertEquals(
            "6415000000000000000100000008000082000000001d7fdf4bf2ff14393c000600008041214c215621452153212021422141214e2144214521492152" +
                "21412120215421752172217121752165216c212c21202152212e2120214a216f217321e9212021442169216f2167216f2120215221692162216521" +
                "692172216f212c21202145214e21202138212f2136212c2120215421752172217121752165216c211d0f501df2ff300d3b0006010080472161216c" +
                "21702120215e4b",
            hex(out))
        assertTrue(isFuelOv2(out))
        assertEquals(2, fuelOv2Count(out))
    }

    @Test fun textUnits() {
        assertEquals(0x2147, tomtomTextUnit('G'.code))
        assertEquals(0x21e9, tomtomTextUnit('é'.code))
        assertEquals(0x4b5e, tomtomTextUnit('€'.code))
        assertEquals(0x4aa0, tomtomTextUnit('’'.code))
        assertEquals(0x82f1, tomtomTextUnit('中'.code))
        assertEquals(0x2491, tomtomTextUnit('Ł'.code))
        assertNull(tomtomTextUnit(0x1F600)) // emoji: no unit, written as '?'
        assertEquals("3f21", hex(tomtomText("😀")))
    }

    @Test fun fpfLayout() {
        val area = intArrayOf(-950000, 3850000, -800000, 4025000)
        val out = buildFpf(FuelType.DIESEL, listOf(226, 0, 70000), area, Instant.ofEpochSecond(1761811200))
        assertEquals("304650460000000001000000001b03691081f1ff10bf3a0000cbf3ffa86a3d00001b036900000000e2000000ffff", hex(out))
        assertEquals(1 to Instant.ofEpochSecond(1761811200), readFpfHeader(out))
    }

    @Test fun portugalRows() {
        val ids = mapOf("Gasóleo simples" to 2101, "GPL Auto" to 1120, "Gasolina 98" to 3400)
        fun row(fuel: String, price: String) = mapOf("Id" to 94701L, "Nome" to "PAGPL Marto Energias - Pedreiras", "Marca" to "Genérico",
            "Municipio" to "Porto de Mós", "Distrito" to "Leiria", "Morada" to "Estrada  ladeira Pires nº49 Azoio", "Localidade" to "Pedreiras",
            "Combustivel" to fuel, "Preco" to price, "Latitude" to 39.58782, "Longitude" to -8.875652, "Quantidade" to 3L)
        val st = dgegStations(listOf(row("GPL Auto", "0,967 €"), row("Gasóleo simples", "2,132 €"), row("Hidrogénio", "9,000 €")), ids)
        assertEquals(1, st.size)
        assertEquals(mapOf(8 to 97, 1 to 213), st[0].prices)
        assertEquals("Genérico PAGPL Marto Energias - Pedreiras, Estrada ladeira Pires nº49 Azoio, Pedreiras", st[0].name)
        assertEquals(39.58782, st[0].lat)
    }

    @Test fun spainRows() {
        val json = "﻿{\"ListaEESSPrecio\":[" +
            "{\"IDEESS\":\"4375\",\"Rótulo\":\"Nº 10.935\",\"Dirección\":\"AVENIDA CASTILLA LA MANCHA, 26\",\"Localidad\":\"ABENGIBRE\"," +
            "\"Latitud\":\"39,211417\",\"Longitud (WGS84)\":\"-1,539167\",\"Tipo Venta\":\"P\",\"Provincia\":\"ALBACETE\",\"Municipio\":\"Abengibre\"," +
            "\"Precio Gasoleo A\":\"1,839\",\"Precio Gasolina 95 E5\":\"1,699\",\"Precio Gasolina 95 E10\":\"\",\"Precio Gasoleo B\":\"1,589\"}," +
            "{\"IDEESS\":\"1\",\"Rótulo\":\"COOP\",\"Latitud\":\"40,0\",\"Longitud (WGS84)\":\"-3,0\",\"Tipo Venta\":\"R\",\"Precio Gasoleo A\":\"1,5\"}]}"
        val st = mitecoStations(json)
        assertEquals(1, st.size)
        assertEquals(mapOf(1 to 184, 6 to 170, 10 to 159), st[0].prices)
        assertEquals("Nº 10.935, AVENIDA CASTILLA LA MANCHA, 26, ABENGIBRE", st[0].name)
        assertEquals(-1.539167, st[0].lon)
    }

    @Test fun portugalDownload() {
        val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun row(id: Int, name: String, brand: String, place: String) = """{"Id":$id,"Nome":"$name","Marca":"$brand","Combustivel":"Gasóleo simples",""" +
            """"Preco":"2,132 €","Latitude":39.62395,"Longitude":-8.85685,"Localidade":"$place","Quantidade":1}"""
        val net = object : Net {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
                urls += url
                val body = when {
                    "GetTiposCombustiveis" in url -> """{"status":true,"resultado":[{"Id":2101,"Descritivo":"Gasóleo simples"}]}"""
                    "GetDistritos" in url -> """{"status":true,"resultado":[{"Id":10,"Descritivo":"Leiria"},{"Id":11,"Descritivo":"Lisboa"}]}"""
                    "GetMunicipios" in url -> """{"status":true,"resultado":[{"Id":145,"Descritivo":"Porto de Mós"},{"Id":146,"Descritivo":"Nazaré"}]}"""
                    // The whole of Leiria is too much for the server: fall back to its municipalities.
                    "idDistrito=10&idsMunicipios=&" in url -> return HttpResponse(500, emptyMap(), "{}".toByteArray())
                    "idsMunicipios=146" in url -> """{"status":false,"mensagem":"Nenhum posto encontrado"}"""
                    "idsMunicipios=145" in url -> """{"status":true,"resultado":[${row(74915, "PD PORTO DE MÓS", "PINGO DOCE", "Calvária")}]}"""
                    "idDistrito=11&idsMunicipios=&" in url -> """{"status":true,"resultado":[${row(1, "GALP LISBOA", "GALP", "Lisboa")}]}"""
                    else -> error("unexpected $url")
                }
                return HttpResponse(200, emptyMap(), body.toByteArray())
            }
        }
        val st = fetchPortugal(net, FuelType.DIESEL).sortedBy { it.name }
        assertEquals(listOf("GALP LISBOA, Lisboa", "PINGO DOCE PD PORTO DE MÓS, Calvária"), st.map { it.name })
        assertEquals(mapOf(1 to 213), st[0].prices)
        val search = urls.filter { "PesquisarPostos" in it }
        assertTrue(search.all { "idsTiposComb=2101&" in it }, search.toString())            // only the chosen fuel
        assertTrue(search.any { "idDistrito=11&idsMunicipios=&qtdPorPagina=1000&pagina=1" in it }, search.toString())
        assertTrue(search.any { "idDistrito=10&idsMunicipios=145&qtdPorPagina=200&pagina=1" in it }, search.toString())
    }

    /** Real downloads from DGEG and MITECO, installed like the app does; -Pm2t.online=1 (Portugal takes minutes). */
    @Test fun liveIberia() {
        if (System.getProperty("m2t.online") != "1") return
        val net = RealNet()
        var t0 = System.currentTimeMillis()
        val es = fetchSpain(net)
        println("Spain: ${es.size} stations in ${(System.currentTimeMillis() - t0) / 1000} s")
        t0 = System.currentTimeMillis()
        val pt = fetchPortugal(net, FuelType.DIESEL) { i, n -> println("  Portugal district $i of $n") }
        println("Portugal: ${pt.size} stations in ${(System.currentTimeMillis() - t0) / 1000} s")
        assertTrue(es.size > 8000, "Spain ${es.size}")
        assertTrue(pt.size > 2000, "Portugal ${pt.size}")
        for (s in listOf(es, pt)) {
            val diesel = s.mapNotNull { it.prices[1] }
            assertTrue(diesel.size > s.size / 2)
            assertTrue(diesel.sorted()[diesel.size / 2] in 100..300, "median diesel ${diesel.sorted()[diesel.size / 2]}")
        }
        val dir = Files.createTempDirectory("fuel-live").toFile()
        val fs = LocalFs(dir)
        installFuelPrices(fs, "Iberia_1175_13260", es + pt, FuelType.DIESEL)
        val set = findFuelSets(fs, "Iberia_1175_13260").single()
        assertEquals(es.size + pt.size, set.stations)
        println("ov2 ${fs.size(set.ov2)} bytes, fpf ${fs.size(set.fpf!!)} bytes; first names: " + (es + pt).sortedBy { it.sortKey }.take(3).map { it.name })
        System.getProperty("m2t.fuelOut")?.let { out -> dir.copyRecursively(java.io.File(out), overwrite = true) }
        dir.deleteRecursively()
    }

    @Test fun franceFeed() {
        val xml = """<?xml version="1.0" encoding="ISO-8859-1" standalone="yes"?>
            <pdv_liste>
              <pdv id="5100009" latitude="4493400" longitude="673300" cp="05100" pop="R">
                <adresse>RN 94</adresse><ville>Montgenèvre</ville>
                <prix nom="Gazole" id="1" maj="2026-10-30 17:00:13" valeur="2.398"/>
                <prix nom="SP95" id="2" maj="2026-10-30 17:00:13" valeur="2.301"/>
                <prix nom="E10" id="5" maj="2026-10-30 17:00:14" valeur="2.226"/>
                <prix nom="E85" id="3" maj="2026-10-30 17:00:13" valeur="0.945"/>
              </pdv>
              <pdv id="97400001" latitude="-2088000" longitude="5545000" cp="97400" pop="R">
                <adresse>Rue de Paris</adresse><ville>Saint-Denis</ville><prix nom="Gazole" id="1" valeur="1789"/>
              </pdv>
            </pdv_liste>""".toByteArray(Charsets.ISO_8859_1)
        val st = franceStations(xml)
        assertEquals(2, st.size)
        assertEquals("RN 94, 05100 Montgenèvre", st[0].name)
        assertEquals(mapOf(1 to 240, 6 to 223), st[0].prices)    // E10 is the unleaded 95 most stations sell
        assertEquals(44.934, st[0].lat, 1e-9)
        assertEquals(179, st[1].prices[1])                         // old feeds: thousandths of a euro
        // fetchFuel keeps mainland France and Corsica only (TomTom's Europe map has no overseas departments).
        val zip = java.io.ByteArrayOutputStream().also { b ->
            java.util.zip.ZipOutputStream(b).use { z -> z.putNextEntry(java.util.zip.ZipEntry("PrixCarburants_instantane.xml")); z.write(xml) }
        }.toByteArray()
        val net = object : Net {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int) = HttpResponse(200, emptyMap(), zip)
        }
        assertEquals(listOf("FR:5100009"), fetchFuel(FuelSource.FRA, net, FuelType.DIESEL).map { it.id })
    }

    @Test fun italyFiles() {
        val anag = "Estrazione del 2026-10-15\nidImpianto|Gestore|Bandiera|Tipo Impianto|Nome Impianto|Indirizzo|Comune|Provincia|Latitudine|Longitudine\n" +
            "59183|ENIMOOV S.P.A.|Agip Eni|Stradale|19829 AGRIGENTO|SS.189 KM. 64+649 - C.DA SAN MICHELE  S.N.C  |AGRIGENTO|AG|37.333935|13.595533\n" +
            "60001|ROSSI SRL|Pompe Bianche|Stradale|ROSSI|VIA ROMA 1|PISA|PI|43.7|10.4\n" +
            "60002|BAD|Q8|Stradale|NOCOORD|VIA X|PISA|PI||\n"
        val prezzi = "Estrazione del 2026-10-15\nidImpianto|descCarburante|prezzo|isSelf|dtComu\n" +
            "59183|Benzina|2.349|0|13/10/2026 20:00:07\n59183|Benzina|1.999|1|13/10/2026 20:00:06\n59183|Gasolio|1.889|1|x\n" +
            "59183|Blue Diesel|2.019|1|x\n59183|Metano|1.399|1|x\n60001|GPL|0.719|1|x\n60002|Gasolio|1.8|1|x\n"
        val st = italyStations(anag, prezzi)
        assertEquals(listOf("IT:59183", "IT:60001"), st.map { it.id })
        assertEquals(mapOf(6 to 200, 1 to 189, 11 to 202), st[0].prices)
        assertEquals("Agip Eni, SS.189 KM. 64+649 - C.DA SAN MICHELE S.N.C, AGRIGENTO", st[0].name)
        assertEquals("VIA ROMA 1, PISA", st[1].name)                // "Pompe Bianche" = unbranded, left out
        // Before February 2026 the files used ';'.
        assertEquals(189, italyStations(anag.replace('|', ';'), prezzi.replace('|', ';')).first().prices[1])
        assertEquals(9, italyFuelCode("HVOlution")); assertEquals(7, italyFuelCode("Blue Super")); assertNull(italyFuelCode("L-GNC"))
    }

    @Test fun croatiaFeed() {
        val json = """{"postajas":[
            {"id":1425,"naziv":"SLANO","adresa":"Banići 40","mjesto":"Banići","obveznik_id":5,"long":"42.798694","lat":"17.850944",
             "cjenici":[{"gorivo_id":829,"cijena":1.64},{"gorivo_id":830,"cijena":1.59},{"gorivo_id":780,"cijena":2.1},{"gorivo_id":999,"cijena":0.86}]},
            {"id":7,"naziv":"ZAGREB","adresa":"Ilica 1","mjesto":"Zagreb","obveznik_id":228,"long":"15° 58.2' E","lat":"45° 48.6' N",
             "cjenici":[{"gorivo_id":831,"cijena":0.9}]},
            {"id":8,"naziv":"EMPTY","adresa":"","mjesto":"","obveznik_id":5,"long":"","lat":"","cjenici":[{"gorivo_id":829,"cijena":1.6}]}],
          "gorivos":[{"id":829,"vrsta_goriva_id":7},{"id":830,"vrsta_goriva_id":8},{"id":780,"vrsta_goriva_id":5},{"id":831,"vrsta_goriva_id":9},{"id":999,"vrsta_goriva_id":10}],
          "obvezniks":[{"id":5,"naziv":"Tifon d.o.o."},{"id":228,"naziv":"-"}]}"""
        val st = croatiaStations(json)
        assertEquals(2, st.size)
        assertEquals(42.798694, st[0].lat); assertEquals(17.850944, st[0].lon)  // the feed swaps lat and long
        assertEquals(mapOf(1 to 159, 7 to 210), st[0].prices)                   // cheapest diesel; heating oil left out
        assertEquals("Tifon SLANO, Banići 40, Banići", st[0].name)
        assertEquals(45.81, st[1].lat, 1e-9); assertEquals(15.97, st[1].lon, 1e-9)
        assertEquals("ZAGREB, Ilica 1, Zagreb", st[1].name)
    }

    @Test fun sloveniaAndAustria() {
        val si = sloveniaStations(listOf(mapOf("pk" to 2258L, "name" to "SHELL KOPER", "address" to "SERMIN 7C", "lat" to 45.552486, "lng" to 13.762092,
            "prices" to mapOf("95" to 0.001, "dizel" to 1.936, "98" to null, "100" to 1.999, "KOEL" to 1.1))))
        assertEquals(mapOf(1 to 194, 7 to 200), si.single().prices)               // 0.001 is a placeholder, not a price
        assertEquals("SHELL KOPER, SERMIN 7C", si.single().name)
        fun at(ft: String, amount: Double) = mapOf("id" to 1214419L, "name" to "CP - so günstig wie möglich",
            "location" to mapOf("address" to "Wiener Straße 36-38", "postalCode" to "7000", "city" to "Eisenstadt", "latitude" to 47.8449265, "longitude" to 16.50778),
            "prices" to listOf(mapOf("fuelType" to ft, "amount" to amount)))
        val au = austriaStations(listOf(at("SUP", 1.763), at("DIE", 1.689)))
        assertEquals(mapOf(6 to 176, 1 to 169), au.single().prices)
        assertEquals("CP - so günstig wie möglich, Wiener Straße 36-38, Eisenstadt", au.single().name)
    }

    /** Real downloads of the other countries; -Pm2t.online=1. Italy's server may not answer outside Italy. */
    @Test fun liveEurope() {
        if (System.getProperty("m2t.online") != "1") return
        val net = RealNet()
        for (s in listOf(FuelSource.FRA, FuelSource.HRV, FuelSource.SVN, FuelSource.AUT, FuelSource.ITA)) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { fetchFuel(s, net, FuelType.DIESEL) }
            r.onFailure { println("${s.country}: FAILED ${it.message}") }.onSuccess { st ->
                val diesel = st.mapNotNull { it.prices[1] }.sorted()
                println("${s.country}: ${st.size} stations in ${(System.currentTimeMillis() - t0) / 1000} s, diesel ${diesel.size} " +
                    "(median ${diesel.getOrNull(diesel.size / 2)}), e.g. ${st.take(2).map { it.name }}")
                assertTrue(st.isNotEmpty() && diesel.isNotEmpty() && diesel[diesel.size / 2] in 100..300)
            }
            if (s != FuelSource.ITA) r.getOrThrow()
        }
    }

    @Test fun installReplacesOtherFuelSetsOnly() {
        val dir = Files.createTempDirectory("fuel").toFile()
        val fs = LocalFs(dir)
        val map = "Iberia_1175_13260"
        fs.write("$map/cspeech_PRT.dat", ByteArray(1)); fs.write("$map/cspeech_ESP.dat", ByteArray(1)); fs.write("$map/cspeech_GIB.dat", ByteArray(1))
        fs.write("$map/FuelPT.ov2", buildFuelOv2(listOf(alves), 1))
        fs.write("$map/FuelPT.fpf", buildFpf(FuelType.LPG, listOf(97), intArrayOf(0, 0, 0, 0), Instant.ofEpochSecond(1)))
        fs.write("$map/Cafes.ov2", ov2Record(Point("Café", 39.0, -9.0)))
        assertEquals(listOf("ESP", "GIB", "PRT"), mapCountries(fs, map))
        assertEquals(FuelType.LPG, findFuelSets(fs, map).single().type)

        val es = FuelStation("ES:1", 39.2, -1.5, "Repsol", mapOf(1 to 184, 6 to 170), "ES")
        installFuelPrices(fs, map, listOf(es, alves.let { FuelStation(it.id, it.lat, it.lon, it.name, it.prices, "PT") }), FuelType.DIESEL,
            Instant.ofEpochSecond(1761811200))

        val sets = findFuelSets(fs, map)
        assertEquals(1, sets.size)
        assertEquals("$map/$FUEL_SET_NAME.ov2", sets[0].ov2)
        assertEquals(2, sets[0].stations)
        assertEquals(FuelType.DIESEL, sets[0].type)
        assertTrue(fs.exists("$map/FuelPT.ov2.mapstotom-old") && fs.exists("$map/FuelPT.fpf.mapstotom-old"))
        assertFalse(fs.exists("$map/FuelPT.ov2"))
        assertTrue(fs.exists("$map/Cafes.ov2"))
        // Station order is the sort key (ES before PT), and the prices follow it.
        val fpf = fs.read("$map/$FUEL_SET_NAME.fpf")!!
        assertEquals("b800e200", hex(fpf.copyOfRange(0x28, 0x2c)))
        val cat = scanPoiFolder(fs, map).first { it.name == FUEL_SET_NAME }
        assertEquals(2, cat.count)
        assertNotNull(cat.fpf)
        assertFalse(cat.editable)
        dir.deleteRecursively()
    }

    @Test fun priceTyposDropped() {
        val st = (1..9).map { FuelStation("$it", 39.0, -8.0, "S$it", mapOf(8 to 99, 1 to 160)) } +
            FuelStation("x", 39.0, -8.0, "Typo", mapOf(8 to 494, 1 to 161))
        val out = dropPriceTypos(st)
        assertEquals(mapOf(1 to 161), out.last().prices)
        assertEquals(mapOf(8 to 99, 1 to 160), out.first().prices)
    }
}

package app.mapstotom.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.zip.CRC32

/*
 * Fuel prices over the cable, like TomTom HOME used to do (NavCore 9.x): a fuel-flagged POI set <name>.ov2 with one
 * station per record, plus <name>.fpf with one price per station, both in the active map folder. The TomTom then offers
 * "find fuel by price", cheapest nearby and cheapest on the route. Verified on a ONE v4 with NavCore 9.541.
 * Prices come from official open data: Portugal (DGEG) and Spain (MITECO).
 */

/** Fuel types the TomTom's chooser offers (outside North America), with its code in the .fpf file. */
enum class FuelType(val code: Int, val label: String) {
    DIESEL(1, "Diesel"),
    UNLEADED(6, "Unleaded 95"),
    SUPER(7, "Super unleaded 98"),
    LPG(8, "LPG"),
    PREMIUM_95(13, "Premium unleaded 95"),
    BIODIESEL(9, "Biodiesel"),
    RED_DIESEL(10, "Red diesel"),
    PREMIUM_DIESEL(11, "Premium diesel"),
    HIGH_POWER_DIESEL(12, "High-power diesel");

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** One station. [prices] maps a TomTom fuel code to the price in cents (1.979 €/l = 198). */
class FuelStation(val id: String, val lat: Double, val lon: Double, val name: String, val prices: Map<Int, Int>, val sortKey: String = name)

/**
 * Countries with an open price source, by the ISO3 code TomTom maps use (cspeech_<ISO3>.dat).
 * [area] (min lon, min lat, max lon, max lat) drops stations with broken coordinates or overseas ones.
 * [note] says when a source doesn't list every station.
 */
enum class FuelSource(val iso3: String, val country: String, val publisher: String, val area: DoubleArray, val note: String? = null) {
    PRT("PRT", "Portugal", "DGEG", doubleArrayOf(-31.3, 32.6, -6.1, 42.2)),
    ESP("ESP", "Spain", "MITECO", doubleArrayOf(-18.2, 27.6, 4.4, 43.8)),
    FRA("FRA", "France", "prix-carburants.gouv.fr", doubleArrayOf(-5.5, 41.2, 9.8, 51.2)),
    ITA("ITA", "Italy", "MIMIT", doubleArrayOf(6.5, 35.4, 18.6, 47.1)),
    HRV("HRV", "Croatia", "MINGO", doubleArrayOf(13.3, 42.3, 19.5, 46.6)),
    SVN("SVN", "Slovenia", "goriva.si", doubleArrayOf(13.3, 45.4, 16.7, 46.9)),
    AUT("AUT", "Austria", "E-Control", doubleArrayOf(9.5, 46.3, 17.2, 49.1),
        "Austria publishes only the cheapest stations of each district, for diesel and unleaded 95");

    /** The download depends on the fuel type (Portugal), so changing the type means downloading again. */
    val byType get() = this == PRT

    fun inArea(s: FuelStation) = s.lon >= area[0] && s.lat >= area[1] && s.lon <= area[2] && s.lat <= area[3]

    companion object {
        fun of(iso3: String) = entries.firstOrNull { it.iso3.equals(iso3, ignoreCase = true) }
    }
}

/** A price in euros -> cents, or null when it is missing or clearly wrong (some feeds have 0.001 placeholders). */
internal fun euroCents(v: Double?): Int? = v?.takeIf { it in 0.3..5.0 }?.let { Math.round(it * 100).toInt() }

/** Whitespace collapsed, empty parts left out, at most 120 characters. */
internal fun stationName(vararg parts: String?): String =
    parts.map { it.orEmpty().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ").trim(' ', ',') }
        .filter { it.isNotEmpty() }.joinToString(", ").take(120)

/** The countries a map folder covers, from its cspeech_<ISO3>.dat files (Iberia: AND, ESP, GIB, PRT). */
fun mapCountries(fs: DeviceFs, mapDir: String): List<String> = fs.list(mapDir).mapNotNull { n ->
    Regex("""cspeech_([A-Za-z]{3})\.dat""", RegexOption.IGNORE_CASE).matchEntire(n)?.groupValues?.get(1)?.uppercase()
}.distinct().sorted()

// ------------------------------------------------------------------ Portugal (DGEG)

private const val DGEG = "https://precoscombustiveis.dgeg.gov.pt/api/PrecoComb/"

/** TomTom code -> DGEG fuel ids; the first one with a price wins. */
private val DGEG_FUELS = mapOf(
    1 to listOf(2101), 6 to listOf(3201), 7 to listOf(3400, 3405), 8 to listOf(1120), 13 to listOf(3205),
    9 to listOf(2115), 10 to listOf(2150), 11 to listOf(2105), 12 to listOf(2105),
)

private fun dgeg(net: Net, path: String, params: List<Pair<String, String>> = emptyList(), timeoutMs: Int = 90_000, tries: Int = 3): List<Map<*, *>> {
    // DGEG wants the fuel id list with plain commas.
    val url = DGEG + path + if (params.isEmpty()) "" else "?" + urlEncode(params).replace("%2C", ",")
    var last: Throwable? = null
    repeat(tries) { k ->
        try {
            val r = net.get(url, mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"), timeoutMs = timeoutMs)
            if (r.code != 200) error("HTTP ${r.code}")
            val d = Json.parse(r.text) as? Map<*, *> ?: error("unexpected answer")
            if (d["status"] == true && d["resultado"] is List<*>) return (d["resultado"] as List<*>).filterIsInstance<Map<*, *>>()
            if ("Nenhum posto" in d["mensagem"].toString()) return emptyList() // no station sells these fuels there
            error(d["mensagem"]?.toString() ?: "unexpected answer")
        } catch (e: Exception) {
            last = e
            if (k < tries - 1) Thread.sleep(3000L * (k + 1))
        }
    }
    throw IllegalStateException("Portugal (DGEG): ${last?.message}", last)
}

/** "1,973 €" or "1,839" -> 197 / 184. */
internal fun priceCents(s: String): Int? =
    s.replace("€", "").replace(',', '.').trim().toDoubleOrNull()?.let { Math.round(it * 100).toInt() }

private fun num(v: Any?): Double? = when (v) {
    is Number -> v.toDouble()
    is String -> v.replace(',', '.').trim().toDoubleOrNull()
    else -> null
}

private fun str(m: Map<*, *>, k: String) = (m[k] as? String)?.trim().orEmpty()

/** "Galp Leiria, Av. ..., Leiria": brand (unless the name has it), street, place. At most 120 characters. */
internal fun dgegName(r: Map<*, *>): String {
    val brand = str(r, "Marca"); val name = str(r, "Nome"); val street = str(r, "Morada")
    val place = str(r, "Localidade").ifEmpty { str(r, "Municipio") }
    val head = if (brand.isNotEmpty() && name.contains(brand, ignoreCase = true)) name else "$brand $name".trim()
    val txt = if (street.isNotEmpty()) "$head, $street, $place" else "$head, $place"
    return txt.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(120)
}

/** DGEG rows (one per station and fuel) -> stations. [fuelIds] maps DGEG fuel names to their ids. */
internal fun dgegStations(rows: List<Map<*, *>>, fuelIds: Map<String, Int>): List<FuelStation> {
    class Acc(val row: Map<*, *>, val byFuel: MutableMap<Int, Int> = mutableMapOf())
    val acc = LinkedHashMap<String, Acc>()
    for (r in rows) {
        val fid = fuelIds[str(r, "Combustivel")] ?: continue
        if (num(r["Latitude"]) == null || num(r["Longitude"]) == null) continue
        val p = priceCents(str(r, "Preco")) ?: continue
        acc.getOrPut(r["Id"].toString()) { Acc(r) }.byFuel[fid] = p
    }
    return acc.map { (id, a) ->
        val prices = DGEG_FUELS.mapNotNull { (code, ids) -> ids.firstNotNullOfOrNull { a.byFuel[it] }?.let { code to it } }.toMap()
        FuelStation("PT:$id", num(a.row["Latitude"])!!, num(a.row["Longitude"])!!, dgegName(a.row), prices,
            listOf("PT", str(a.row, "Distrito"), str(a.row, "Municipio"), str(a.row, "Nome")).joinToString("\u0000"))
    }.filter { it.prices.isNotEmpty() }
}

/**
 * Portuguese stations with their price for [type]. DGEG's server needs about 0.1 s per row it returns (one row per
 * station and fuel), so this asks for the one fuel type only, a whole district per request, three districts at a time.
 * A district the server can't answer in one go is fetched one municipality at a time instead.
 * [progress] gets (districts done, districts).
 */
fun fetchPortugal(net: Net, type: FuelType, progress: (Int, Int) -> Unit = { _, _ -> }): List<FuelStation> {
    val ids = DGEG_FUELS[type.code]?.joinToString(",") ?: return emptyList()
    val fuelIds = dgeg(net, "GetTiposCombustiveis").associate { str(it, "Descritivo") to (num(it["Id"])?.toInt() ?: -1) }
    val districts = dgeg(net, "GetDistritos").map { it["Id"].toString() }
    fun search(district: String, municipality: String, perPage: Int, timeoutMs: Int, tries: Int): List<Map<*, *>> {
        val rows = mutableListOf<Map<*, *>>()
        var page = 1
        while (true) {
            val got = dgeg(net, "PesquisarPostos", listOf(
                "idsTiposComb" to ids, "idMarca" to "", "idTipoPosto" to "", "idDistrito" to district,
                "idsMunicipios" to municipality, "qtdPorPagina" to "$perPage", "pagina" to "$page"), timeoutMs, tries)
            rows += got
            val total = num(got.firstOrNull()?.get("Quantidade"))?.toInt() ?: 0
            if (got.isEmpty() || page * perPage >= total) return rows
            page++
        }
    }
    fun district(id: String): List<Map<*, *>> = runCatching { search(id, "", 1000, 150_000, 1) }.getOrElse {
        dgeg(net, "GetMunicipios", listOf("idDistrito" to id)).flatMap { m ->
            search(id, m["Id"].toString(), 200, 90_000, 3).also { Thread.sleep(150) } // be gentle with the public server
        }
    }
    progress(0, districts.size)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
    try {
        val done = java.util.concurrent.atomic.AtomicInteger()
        val jobs = districts.map { id -> pool.submit(java.util.concurrent.Callable { district(id).also { progress(done.incrementAndGet(), districts.size) } }) }
        val rows = jobs.flatMap { job ->
            try { job.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
        }
        return dgegStations(rows, fuelIds)
    } finally {
        pool.shutdownNow()
    }
}

// ------------------------------------------------------------------ Spain (MITECO)

private const val MITECO = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/"

/** TomTom code -> MITECO "Precio <fuel>" fields; the first one with a price wins. */
private val MITECO_FUELS = mapOf(
    1 to listOf("Gasoleo A"), 6 to listOf("Gasolina 95 E5", "Gasolina 95 E10"), 7 to listOf("Gasolina 98 E5", "Gasolina 98 E10"),
    8 to listOf("Gases licuados del petróleo"), 13 to listOf("Gasolina 95 E5 Premium"), 9 to listOf("Biodiesel"),
    10 to listOf("Gasoleo B"), 11 to listOf("Gasoleo Premium"), 12 to listOf("Gasoleo Premium"),
)

/** MITECO's station list -> stations. Members-only stations (Tipo Venta R) are left out. */
internal fun mitecoStations(json: String): List<FuelStation> {
    val root = Json.parse(json.removePrefix("﻿")) as? Map<*, *> ?: error("Spain (MITECO): unexpected answer")
    val list = root["ListaEESSPrecio"] as? List<*> ?: error("Spain (MITECO): no station list in the answer")
    return list.mapNotNull { e ->
        val r = e as? Map<*, *> ?: return@mapNotNull null
        if (str(r, "Tipo Venta") == "R") return@mapNotNull null
        val lat = num(r["Latitud"]) ?: return@mapNotNull null
        val lon = num(r["Longitud (WGS84)"]) ?: return@mapNotNull null
        val prices = MITECO_FUELS.mapNotNull { (code, names) ->
            names.firstNotNullOfOrNull { n -> priceCents(str(r, "Precio $n")) }?.let { code to it }
        }.toMap()
        if (prices.isEmpty()) return@mapNotNull null
        val brand = str(r, "Rótulo")
        val name = "$brand, ${str(r, "Dirección")}, ${str(r, "Localidad")}".split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(120)
        FuelStation("ES:" + str(r, "IDEESS"), lat, lon, name, prices,
            listOf("ES", str(r, "Provincia"), str(r, "Municipio"), brand).joinToString("\u0000"))
    }
}

/** Every Spanish station with its prices: one download of about 12 MB. */
fun fetchSpain(net: Net): List<FuelStation> {
    val r = net.get(MITECO, mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"), timeoutMs = 180_000)
    if (r.code != 200) error("Spain (MITECO): HTTP ${r.code}")
    return mitecoStations(r.text)
}

// ------------------------------------------------------------------ France (prix-carburants, Ministère de l'Économie)

private const val FR_URL = "https://donnees.roulez-eco.fr/opendata/instantane"

/** The national feed: a zip with one XML file of every station (about 10,000) and its current prices. */
fun fetchFrance(net: Net): List<FuelStation> {
    val r = net.get(FR_URL, mapOf("User-Agent" to USER_AGENT), timeoutMs = 120_000)
    if (r.code != 200) error("France: HTTP ${r.code}")
    val zip = java.util.zip.ZipInputStream(r.body.inputStream())
    while (true) {
        val e = zip.nextEntry ?: error("France: no XML file in the download")
        if (e.name.endsWith(".xml", ignoreCase = true)) return franceStations(zip.readBytes())
    }
}

/** Station elements `<pdv latitude="4493400" ...>` (degrees x100000) with `<adresse>`, `<ville>` and `<prix nom= valeur=>`. */
internal fun franceStations(xml: ByteArray): List<FuelStation> {
    val out = mutableListOf<FuelStation>()
    val factory = javax.xml.parsers.SAXParserFactory.newInstance()
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    var id = ""; var lat = 0.0; var lon = 0.0; var cp = ""
    var addr = ""; var town = ""
    val text = StringBuilder()
    val byName = HashMap<String, Int>()
    factory.newSAXParser().parse(xml.inputStream(), object : org.xml.sax.helpers.DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String, a: org.xml.sax.Attributes) {
            text.setLength(0)
            when (qName) {
                "pdv" -> {
                    id = a.getValue("id").orEmpty(); cp = a.getValue("cp").orEmpty(); addr = ""; town = ""; byName.clear()
                    lat = (num(a.getValue("latitude")) ?: 0.0) / 1e5; lon = (num(a.getValue("longitude")) ?: 0.0) / 1e5
                }
                "prix" -> {
                    val v = num(a.getValue("valeur")) ?: return
                    euroCents(if (v > 10) v / 1000 else v)?.let { byName[a.getValue("nom").orEmpty()] = it } // old feeds gave thousandths
                }
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "adresse" -> addr = text.toString()
                "ville" -> town = text.toString()
                "pdv" -> {
                    val prices = listOfNotNull(
                        byName["Gazole"]?.let { 1 to it }, (byName["E10"] ?: byName["SP95"])?.let { 6 to it },
                        byName["SP98"]?.let { 7 to it }, byName["GPLc"]?.let { 8 to it },
                    ).toMap()
                    if (prices.isNotEmpty()) out += FuelStation("FR:$id", lat, lon, stationName(addr, "$cp $town".trim()), prices,
                        listOf("FR", cp, town, addr).joinToString("\u0000"))
                }
            }
        }
    })
    return out
}

// ------------------------------------------------------------------ Italy (MIMIT, Osservaprezzi carburanti)

private const val IT_BASE = "https://www.mimit.gov.it/images/exportCSV/"

/** Two daily files: the stations and the prices at 8 a.m. (about 24,000 stations). */
fun fetchItaly(net: Net): List<FuelStation> {
    fun file(name: String): String {
        val r = runCatching { net.get(IT_BASE + name, mapOf("User-Agent" to USER_AGENT), timeoutMs = 120_000) }
            .getOrElse { throw IllegalStateException("Italy: the ministry's server (mimit.gov.it) didn't answer: ${it.message}", it) }
        if (r.code != 200) error("Italy: HTTP ${r.code} for $name")
        return r.text
    }
    return italyStations(file("anagrafica_impianti_attivi.csv"), file("prezzo_alle_8.csv"))
}

/** Rows of a MIMIT file: a line "Estrazione del ...", a header, then rows separated by '|' (';' before February 2026). */
private fun mimitRows(text: String): List<Map<String, String>> {
    val lines = text.removePrefix("﻿").lineSequence().filter { it.isNotBlank() }.toList()
    val start = if (lines.firstOrNull()?.startsWith("Estrazione") == true) 1 else 0
    val header = lines.getOrNull(start) ?: return emptyList()
    val sep = if ('|' in header) '|' else ';'
    val cols = header.split(sep).map { it.trim() }
    return lines.drop(start + 1).mapNotNull { l -> l.split(sep).takeIf { it.size == cols.size }?.let { f -> cols.zip(f.map(String::trim)).toMap() } }
}

/** MIMIT fuel name -> TomTom code: plain diesel, petrol and LPG, branded premium fuels, HVO. Methane is left out. */
internal fun italyFuelCode(desc: String): Int? {
    val d = desc.trim().lowercase()
    return when {
        d == "gasolio" -> 1
        d == "benzina" -> 6
        d == "gpl" -> 8
        "hvo" in d -> 9
        "metano" in d || "gnl" in d || "gnc" in d -> null
        "diesel" in d || d.startsWith("gasolio") -> 11
        "benzina" in d || "super" in d || "perform" in d -> 7
        else -> null
    }
}

internal fun italyStations(anagrafica: String, prezzi: String): List<FuelStation> {
    val prices = HashMap<String, MutableMap<Int, Int>>()
    for (r in mimitRows(prezzi)) {
        val code = italyFuelCode(r["descCarburante"].orEmpty()) ?: continue
        val p = euroCents(num(r["prezzo"])) ?: continue
        val m = prices.getOrPut(r["idImpianto"].orEmpty()) { HashMap() }
        m[code] = minOf(m[code] ?: p, p) // self-service and served: the lower one
    }
    return mimitRows(anagrafica).mapNotNull { r ->
        val id = r["idImpianto"].orEmpty()
        val p = prices[id] ?: return@mapNotNull null
        val lat = num(r["Latitudine"]) ?: return@mapNotNull null
        val lon = num(r["Longitudine"]) ?: return@mapNotNull null
        val brand = r["Bandiera"].orEmpty().takeUnless { it.equals("Pompe Bianche", true) }
        FuelStation("IT:$id", lat, lon, stationName(brand, r["Indirizzo"], r["Comune"]), p,
            listOf("IT", r["Provincia"].orEmpty(), r["Comune"].orEmpty(), brand.orEmpty()).joinToString("\u0000"))
    }
}

// ------------------------------------------------------------------ Croatia (Ministry of Economy, mzoe-gor.hr)

private const val HR_URL = "https://mzoe-gor.hr/data.json"

fun fetchCroatia(net: Net): List<FuelStation> {
    val r = net.get(HR_URL, mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"), timeoutMs = 120_000)
    if (r.code != 200) error("Croatia: HTTP ${r.code}")
    return croatiaStations(r.text)
}

/** "45.3325", "45,3325" or "45° 39.4112' N" -> degrees. */
internal fun degrees(v: Any?): Double? {
    num(v)?.let { return it }
    val m = Regex("""(\d+)\s*°\s*([\d.,]+)\s*'?\s*([NSEWnsew])?""").find(v?.toString() ?: return null) ?: return null
    val d = m.groupValues[1].toDouble() + m.groupValues[2].replace(',', '.').toDouble() / 60
    return if (m.groupValues[3].uppercase() in setOf("S", "W")) -d else d
}

/** Croatian fuel kinds (vrsta_goriva) -> TomTom code; the cheapest of each group wins. */
private val HR_FUELS = mapOf(1 to listOf(7, 8), 6 to listOf(1, 2), 7 to listOf(5, 6), 8 to listOf(9), 10 to listOf(11), 9 to listOf(13))

internal fun croatiaStations(json: String): List<FuelStation> {
    val root = Json.parse(json) as? Map<*, *> ?: error("Croatia: unexpected answer")
    fun list(k: String) = (root[k] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
    val kindOf = list("gorivos").associate { it["id"].toString() to (num(it["vrsta_goriva_id"])?.toInt() ?: 0) }
    val company = list("obvezniks").associate { it["id"].toString() to str(it, "naziv") }
    return list("postajas").mapNotNull { s ->
        // The feed swaps "lat" and "long" for most stations: tell them apart by value (Croatia: lat 42-47, lon 13-20).
        val a = degrees(s["lat"]) ?: return@mapNotNull null
        val b = degrees(s["long"]) ?: return@mapNotNull null
        val (lat, lon) = if (b in 42.0..47.0 && a !in 42.0..47.0) b to a else a to b
        val byKind = HashMap<Int, Int>()
        for (c in (s["cjenici"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
            val kind = kindOf[c["gorivo_id"].toString()] ?: continue
            val p = euroCents(num(c["cijena"])) ?: continue
            byKind[kind] = minOf(byKind[kind] ?: p, p)
        }
        val prices = HR_FUELS.mapNotNull { (code, kinds) -> kinds.mapNotNull { byKind[it] }.minOrNull()?.let { code to it } }.toMap()
        if (prices.isEmpty()) return@mapNotNull null
        val firm = company[s["obveznik_id"].toString()].orEmpty()
            .replace(Regex("""\s*\b(j\.\s?d\.\s?o\.\s?o\.|d\.\s?o\.\s?o\.|d\.\s?d\.)\s*$""", RegexOption.IGNORE_CASE), "").takeUnless { it == "-" }
        FuelStation("HR:" + s["id"], lat, lon, stationName(listOfNotNull(firm, str(s, "naziv")).joinToString(" "), str(s, "adresa"), str(s, "mjesto")),
            prices, listOf("HR", str(s, "mjesto"), str(s, "naziv")).joinToString("\u0000"))
    }
}

// ------------------------------------------------------------------ Slovenia (goriva.si)

private const val SI_URL = "https://goriva.si/api/v1/search/?format=json&page="

/** goriva.si price keys -> TomTom code (KOEL = heating oil is left out). */
private val SI_FUELS = mapOf(1 to listOf("dizel"), 6 to listOf("95"), 7 to listOf("98", "100"), 8 to listOf("avtoplin-lpg"),
    11 to listOf("dizel-premium"), 9 to listOf("hvo"))

internal fun sloveniaStations(results: List<Map<*, *>>): List<FuelStation> = results.mapNotNull { r ->
    val pr = r["prices"] as? Map<*, *> ?: return@mapNotNull null
    val prices = SI_FUELS.mapNotNull { (code, keys) -> keys.firstNotNullOfOrNull { euroCents(num(pr[it])) }?.let { code to it } }.toMap()
    val lat = num(r["lat"]) ?: return@mapNotNull null
    val lon = num(r["lng"]) ?: return@mapNotNull null
    if (prices.isEmpty()) null
    else FuelStation("SI:" + r["pk"], lat, lon, stationName(str(r, "name"), str(r, "address")), prices, "SI\u0000" + str(r, "name"))
}

/** About 550 stations, 25 per page. */
fun fetchSlovenia(net: Net, progress: (Int, Int) -> Unit = { _, _ -> }): List<FuelStation> {
    val rows = mutableListOf<Map<*, *>>()
    var page = 1
    while (page < 200) {
        val r = net.get(SI_URL + page, mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"), timeoutMs = 60_000)
        if (r.code != 200) error("Slovenia: HTTP ${r.code}")
        val d = Json.parse(r.text) as? Map<*, *> ?: error("Slovenia: unexpected answer")
        rows += (d["results"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        val total = num(d["count"])?.toInt() ?: rows.size
        progress(rows.size, maxOf(total, 1))
        if (d["next"] == null || rows.size >= total) break
        page++
        Thread.sleep(100)
    }
    return sloveniaStations(rows)
}

// ------------------------------------------------------------------ Austria (E-Control Spritpreisrechner)

private const val AT_API = "https://api.e-control.at/sprit/1.0/"
private val AT_FUELS = mapOf("DIE" to 1, "SUP" to 6)

internal fun austriaStations(found: List<Map<*, *>>): List<FuelStation> {
    val byId = LinkedHashMap<String, Pair<Map<*, *>, MutableMap<Int, Int>>>()
    for (s in found) {
        val e = byId.getOrPut(s["id"].toString()) { s to HashMap() }
        for (p in (s["prices"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()) {
            val code = AT_FUELS[p["fuelType"]?.toString()] ?: continue
            euroCents(num(p["amount"]))?.let { e.second[code] = it }
        }
    }
    return byId.mapNotNull { (id, e) ->
        val loc = e.first["location"] as? Map<*, *> ?: return@mapNotNull null
        val lat = num(loc["latitude"]) ?: return@mapNotNull null
        val lon = num(loc["longitude"]) ?: return@mapNotNull null
        if (e.second.isEmpty()) null
        else FuelStation("AT:$id", lat, lon, stationName(str(e.first, "name"), str(loc, "address"), str(loc, "city")), e.second,
            listOf("AT", str(loc, "postalCode"), str(e.first, "name")).joinToString("\u0000"))
    }
}

/** The cheapest stations of each of Austria's ~120 districts, for diesel and unleaded 95: all that E-Control publishes. */
fun fetchAustria(net: Net, progress: (Int, Int) -> Unit = { _, _ -> }): List<FuelStation> {
    fun get(path: String): Any? {
        val r = net.get(AT_API + path, mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"), timeoutMs = 60_000)
        if (r.code != 200) error("Austria: HTTP ${r.code}")
        return Json.parse(r.text)
    }
    val districts = (get("regions?includeCities=false") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        .flatMap { (it["subRegions"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>() }
        .filter { it["type"] == "PB" }.map { it["code"].toString() }
    require(districts.isNotEmpty()) { "Austria: no districts in the answer" }
    val found = mutableListOf<Map<*, *>>()
    districts.forEachIndexed { i, code ->
        progress(i, districts.size)
        for (ft in AT_FUELS.keys) {
            found += (get("search/gas-stations/by-region?code=$code&type=PB&fuelType=$ft&includeClosed=true") as? List<*>)
                .orEmpty().filterIsInstance<Map<*, *>>()
        }
        Thread.sleep(100)
    }
    progress(districts.size, districts.size)
    return austriaStations(found)
}

/**
 * Stations of one country. Every source returns all its fuel types, except Portugal, which is too slow for that and
 * returns only [type]; see [FuelSource.byType].
 */
fun fetchFuel(source: FuelSource, net: Net, type: FuelType, progress: (Int, Int) -> Unit = { _, _ -> }): List<FuelStation> = when (source) {
    FuelSource.PRT -> fetchPortugal(net, type, progress)
    FuelSource.ESP -> fetchSpain(net)
    FuelSource.FRA -> fetchFrance(net)
    FuelSource.ITA -> fetchItaly(net)
    FuelSource.HRV -> fetchCroatia(net)
    FuelSource.SVN -> fetchSlovenia(net, progress)
    FuelSource.AUT -> fetchAustria(net, progress)
}.filter(source::inArea).let(::dropPriceTypos)

/**
 * Feeds have typos (seen: LPG at 4.94 EUR, red diesel at 4.52 EUR). A price under half or over twice
 * the median for that fuel is treated as "not sold" at that station.
 */
internal fun dropPriceTypos(stations: List<FuelStation>): List<FuelStation> {
    val medians = stations.flatMap { it.prices.entries }.groupBy({ it.key }, { it.value })
        .mapValues { (_, v) -> v.sorted()[v.size / 2] }
    return stations.map { st ->
        val ok = st.prices.filter { (code, cents) -> val m = medians.getValue(code); cents * 2 >= m && cents <= m * 2 }
        if (ok.size == st.prices.size) st else FuelStation(st.id, st.lat, st.lon, st.name, ok, st.sortKey)
    }
}

// ------------------------------------------------------------------ file formats

/**
 * NavCore's 2-byte text unit for one character (low byte first), or null if it has none.
 * Latin-1 is the byte followed by '!' ("Galp" -> G!a!l!p!).
 */
internal fun tomtomTextUnit(cp: Int): Int? {
    if (cp in 1..0xff) return cp + 0x2100
    if (cp <= 0 || cp > 0xffff) return null
    val c = if (cp >= 0xf000) cp - 0x5000 else cp
    val q = c / 213; val r = c % 213
    fun adj(v: Int) = v + 0x23 + when { v + 0x23 > 0x5a -> 2; v + 0x23 > 0x24 -> 1; else -> 0 }
    val lo = adj(r); val hi = adj(q)
    return if (hi > 0xff) null else lo or (hi shl 8)
}

/** A station name as NavCore's 2-byte units; characters it can't show become '?'. */
internal fun tomtomText(text: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    text.codePoints().forEach { cp ->
        if (cp == 0) return@forEach
        val u = tomtomTextUnit(cp) ?: tomtomTextUnit('?'.code)!!
        out.write(u and 0xff); out.write(u shr 8)
    }
    return out.toByteArray()
}

private const val FUEL_FLAG = 0x08

/**
 * The fuel POI set: a 'd' header with the fuel flag and [setId], then one compact record (type 0x1d) per station.
 * Record: type, payload length, lon, lat (x100000), flags 0x06 (id follows + plain name), id 0x800000|index (24 bit),
 * name. The index picks the station's price in the .fpf file.
 */
fun buildFuelOv2(stations: List<FuelStation>, setId: Int): ByteArray {
    require(stations.size < 0x800000) { "Too many stations" }
    val h = ByteBuffer.allocate(0x15).order(ByteOrder.LITTLE_ENDIAN)
    h.put(0x64).putInt(0x15).putInt(0).putInt(setId).put(FUEL_FLAG.toByte()).put(0).put(0).put(0).putInt(0)
    val head = h.array()
    head[0x10] = (head.sumOf { it.toInt() and 0xff } and 0xff).toByte()
    val out = java.io.ByteArrayOutputStream()
    out.write(head)
    stations.forEachIndexed { i, s ->
        var name = tomtomText(s.name)
        if (name.size > 252) name = name.copyOf(252)
        val payload = byteArrayOf((i and 0xff).toByte(), (i shr 8 and 0xff).toByte(), (i shr 16 or 0x80).toByte()) + name
        out.write(ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN).put(0x1d).put(payload.size.toByte())
            .putInt(Math.rint(s.lon * 1e5).toInt()).putInt(Math.rint(s.lat * 1e5).toInt()).put(0x06).array())
        out.write(payload)
    }
    return out.toByteArray()
}

/**
 * The price file (FPF0): fuel type, update time, the area of the update (min lon, min lat, max lon, max lat, x100000),
 * then one u16 price in cents per station index (0 = not sold). The TomTom shows the age from the time.
 */
fun buildFpf(type: FuelType, prices: List<Int>, area: IntArray, time: Instant): ByteArray {
    require(area.size == 4)
    val b = ByteBuffer.allocate(0x28 + 2 * prices.size).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(0x46504630).putInt(0).put(type.code.toByte()).put(0).put(0).put(0).putInt(time.epochSecond.toInt())
    area.forEach { b.putInt(it) }
    b.putInt(time.epochSecond.toInt()).putInt(0)
    prices.forEach { b.putShort(it.coerceIn(0, 0xffff).toShort()) }
    return b.array()
}

/** The stations' extent, x100000: min lon, min lat, max lon, max lat. */
fun fuelArea(stations: List<FuelStation>): IntArray {
    require(stations.isNotEmpty())
    fun r(v: Double) = Math.rint(v * 1e5).toInt()
    return intArrayOf(r(stations.minOf { it.lon }), r(stations.minOf { it.lat }), r(stations.maxOf { it.lon }), r(stations.maxOf { it.lat }))
}

// ------------------------------------------------------------------ on the TomTom

/** A fuel set in the map folder. */
class FuelSet(val ov2: String, val fpf: String?, val stations: Int, val type: FuelType?, val updated: Instant?, val setId: Int)

fun isFuelOv2(data: ByteArray) = data.size >= 0x15 && data[0] == 0x64.toByte() && (data[0x0d].toInt() and FUEL_FLAG) != 0

/** Number of station records in a fuel .ov2. */
internal fun fuelOv2Count(data: ByteArray): Int {
    var i = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(1).coerceAtLeast(5)
    var n = 0
    while (i + 11 <= data.size) {
        val type = data[i].toInt() and 0xff
        if (type != 0x1d) break
        i += 11 + (data[i + 1].toInt() and 0xff); n++
    }
    return n
}

/** (fuel type code, update time) of a .fpf file, or null if it isn't one. */
internal fun readFpfHeader(data: ByteArray): Pair<Int, Instant>? {
    if (data.size < 0x28) return null
    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    if (b.getInt(0) != 0x46504630) return null
    return (data[8].toInt() and 0xff) to Instant.ofEpochSecond(b.getInt(0x0c).toLong() and 0xffffffffL)
}

/** Fuel sets in the map folder. The TomTom uses only the first one it finds, so there should be one. */
fun findFuelSets(fs: DeviceFs, mapDir: String): List<FuelSet> {
    val names = fs.list(mapDir)
    return names.filter { it.endsWith(".ov2", ignoreCase = true) }.mapNotNull { f ->
        val data = fs.read(join(mapDir, f)) ?: return@mapNotNull null
        if (!isFuelOv2(data)) return@mapNotNull null
        val fpf = names.firstOrNull { it.equals(f.dropLast(4) + ".fpf", ignoreCase = true) }?.let { join(mapDir, it) }
        val hdr = fpf?.let { fs.read(it) }?.let(::readFpfHeader)
        FuelSet(join(mapDir, f), fpf, fuelOv2Count(data), hdr?.first?.let(FuelType::of), hdr?.second,
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(9))
    }
}

/** No space: every fuel set name checked on a real device had none (FuelIberia). */
const val FUEL_SET_NAME = "FuelPrices"

/**
 * Install (or replace) the fuel set in the map folder. Other fuel sets there are renamed to *.mapstotom-old,
 * because the TomTom only uses the first one. The TomTom finds a new set at its next start.
 */
/** The set id MapsToTom writes in the .ov2 header for this map folder (the TomTom sends it back when asking for prices). */
fun fuelSetId(mapDir: String) = CRC32().run { update("MapsToTom/$mapDir".toByteArray()); (value % 1_000_000_000L).toInt() }

/** Station order in the .ov2 (the .fpf and the phone's price replies follow it). */
fun fuelSetOrder(stations: List<FuelStation>) = stations.sortedBy { it.sortKey }

fun installFuelPrices(fs: DeviceFs, mapDir: String, stations: List<FuelStation>, type: FuelType, time: Instant = Instant.now()): String {
    require(stations.isNotEmpty()) { "No stations to install" }
    val rows = fuelSetOrder(stations)
    val ov2 = join(mapDir, "$FUEL_SET_NAME.ov2")
    for (old in findFuelSets(fs, mapDir)) {
        if (old.ov2.equals(ov2, ignoreCase = true)) continue
        for (p in listOfNotNull(old.ov2, old.fpf)) {
            val target = nameOf(p) + ".mapstotom-old"
            if (fs.exists(join(mapDir, target))) fs.delete(join(mapDir, target))
            fs.rename(p, target)
        }
    }
    val setId = fuelSetId(mapDir)
    fs.write(ov2, buildFuelOv2(rows, setId))
    fs.write(join(mapDir, "$FUEL_SET_NAME.fpf"), buildFpf(type, rows.map { it.prices[type.code] ?: 0 }, fuelArea(rows), time))
    return ov2
}

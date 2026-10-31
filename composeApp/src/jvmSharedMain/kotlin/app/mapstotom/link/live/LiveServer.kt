package app.mapstotom.link.live

import app.mapstotom.core.Net
import app.mapstotom.link.LinkAddr
import app.mapstotom.link.LocalService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/*
 * The TomTom's Live services, answered inside the app (port of go950-navcore-9541/server/mockserver.py).
 * Plugged in as the LocalService for 192.168.77.1:8080 on the PPP link. Spec: docs/inapp_services.md.
 */

/** Fuel prices of one installed set, in the station order of its .ov2 (index i = station i). */
class FuelPriceSet(val setId: Long, val name: String, val prices: Map<Int, List<Int>>)

/** The fuel sets the app installed by cable, by set id (the 'd'-header id the TomTom sends as p3). */
fun interface FuelPriceSets {
    fun find(setId: String): FuelPriceSet?
}

/** Small persistent storage: the last Hercules login body per device, so an app restart can still decrypt its calls. */
interface LiveStore {
    fun lastLogins(): Map<String, ByteArray>
    fun saveLogin(serial: String, body: ByteArray)
}

class MemoryLiveStore : LiveStore {
    private val m = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    override fun lastLogins(): Map<String, ByteArray> = HashMap(m)
    override fun saveLogin(serial: String, body: ByteArray) { m[serial] = body }
}

class LiveConfig(
    /** What the card's discovery files point at. */
    val base: String = "http://%d.%d.%d.%d:%d".format(*(LinkAddr.PHONE.map { it.toInt() and 0xff } + LinkAddr.LIVE_PORT).toTypedArray()),
    /** Push router (unused: gpid answers 204). */
    val router: String = LinkAddr.PHONE.joinToString(".") { "${it.toInt() and 0xff}" } + ":9000",
)

class LiveServer(
    private val config: LiveConfig = LiveConfig(),
    private val traffic: IncidentSource,
    private val weather: (lat: Double, lon: Double) -> Map<*, *>,
    private val fuel: FuelPriceSets = FuelPriceSets { null },
    private val store: LiveStore = MemoryLiveStore(),
    net: Net? = null,
    private val log: (String) -> Unit = {},
) : LocalService {
    /** Convenience: real Open-Meteo weather and TomTom traffic with the user's key. */
    constructor(net: Net, tomtomKey: () -> String?, fuel: FuelPriceSets, store: LiveStore, log: (String) -> Unit = {}) :
        this(LiveConfig(), TomTomSource(tomtomKey, net, log = log), Weather.openMeteo(net), fuel, store, net, log)

    class Reply(val code: Int, val type: String, val body: ByteArray)

    private val hercules = Hercules(traffic, net, log)
    private val portland = Portland(traffic, log)
    private val discovery = XMsg.reply(listOf(XMsg.str(discoveryBlob(config.base, config.router))))

    init {   // sessions survive an app restart: replay each device's last login
        for ((serial, body) in store.lastLogins()) runCatching { hercules.handleBody(body) }
            .onSuccess { log("restored Hercules session for $serial") }
    }

    override fun serve(input: InputStream, output: OutputStream) {
        val req = readRequest(input) ?: return
        val r = try { respond(req.method, req.path, req.body) } catch (e: Exception) {
            log("live: ${req.method} ${req.path}: ${e.message}"); Reply(200, "text/plain", XMsg.reply(emptyList(), 204))
        }
        val head = "HTTP/1.0 ${r.code} OK\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII)); output.write(r.body); output.flush()
    }

    fun respond(method: String, path: String, body: ByteArray = ByteArray(0)): Reply {
        val p = path.substringBefore('?'); val query = path.substringAfter('?', "")
        log("live: $method $p")
        if (method == "POST") {
            if (p != "/h204") return text(XMsg.reply(emptyList(), 204))
            val out = try {
                hercules.handleBody(body).also { hercules.loginSerial(body)?.let { store.saveLogin(it, body) } }
            } catch (e: Exception) {
                log("hercules: bad request ${e.message}"); Hermes.packBody(HStruct(4 to 9L, 5 to "bad request"))
            }
            return Reply(200, "application/octet-stream", out)
        }
        return text(when (p) {
            "/services/directory.php" -> (config.base + "/h204").toByteArray()
            "/proxy/directory.php" -> discovery
            "/portland" -> portland.handle(query)
            "/weather" -> Weather.reply(query, weather, log)
            "/logon" -> XMsg.reply(listOf(XMsg.buf(ByteArray(32) { it.toByte() }), XMsg.int(0)))
            "/account2" -> XMsg.reply(listOf(XMsg.table(accountRows())))
            "/svcact", "/prodact" -> XMsg.reply(List(4) { XMsg.str("live") } + XMsg.int(200))
            "/fuelprices" -> fuelPrices(query)
            "/apn" -> "APNOK".toByteArray()                          // raw text, not XMsg (verified on the v4)
            "/apn_test" -> XMsg.reply(listOf(XMsg.buf(APN_TEST_MAGIC)))
            else -> XMsg.reply(emptyList(), 204)                     // unknown service: "no content"
        })
    }

    private fun text(b: ByteArray) = Reply(200, "text/plain", b)

    /** a=fp m=gfl2 t=LII: p1 area, p2 fuel type, p3 set id. */
    private fun fuelPrices(query: String): ByteArray {
        val q = parseQuery(query)
        val set = fuel.find(q["p3"] ?: "")?.takeIf { it.setId.toString() == q["p3"] }
            ?: return XMsg.reply(emptyList(), 600).also { log("fuel: unknown set ${q["p3"]}") }   // wrong-version message
        val type = q["p2"]?.toIntOrNull()?.takeIf { it.toString() == q["p2"] }
        val prices = type?.let { set.prices[it] }
        if (q["a"] != "fp" || prices == null || prices.none { it != 0 }) return XMsg.reply(emptyList(), 204)   // type not available
        return XMsg.reply(listOf(XMsg.str("OK"), XMsg.buf(fpu0(type, prices))))
    }

    private class Request(val method: String, val path: String, val body: ByteArray)

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val c = input.read()
            if (c < 0) return null
            head.write(c)
            last4 = (last4 shl 8) or c
            if (last4 == 0x0d0a0d0a || (last4 and 0xffff) == 0x0a0a) break
            if (head.size() > 16_384) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n", "\n")
        val parts = lines[0].split(' ')
        if (parts.size < 2) return null
        val len = lines.drop(1).firstOrNull { it.startsWith("content-length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = ByteArray(len); var got = 0
        while (got < len) { val n = input.read(body, got, len - got); if (n < 0) break; got += n }
        return Request(parts[0], parts[1], body.copyOf(got))
    }

    companion object {
        val APN_TEST_MAGIC = "A9PxN7O3Kv-*26_`.?/e~=XxYq@".toByteArray(Charsets.US_ASCII)   // ttn VA 0x77203c

        /** Subscription rows for AccountInfo (docs/captured_requests.md); 13 columns as NavCore reads them. */
        private val PRODUCTS = listOf("SPD_DD", "SPD_DDS", "SPD_GD", "SPD_MD", "SPD_PD", "SPD_CD", "SPD_NV", "SPD_VD", "SPD_RT",
            "SPD_CU", "SPD_SD", "ROADCONDITIONS", "FUELPRICES", "LOCALSEARCH", "TRACK", "DYNSC", "BUDDY", "MISC", "NAVTRAFFIC",
            "WEATHER2", "NTadespt", "301E17F32843842C10C07")
        private val TRAFFIC = setOf("NAVTRAFFIC", "NTadespt", "301E17F32843842C10C07")

        fun accountRows(start: Long = 1262304000, expiry: Long = 2147000000): List<List<ByteArray>> = PRODUCTS.map { p ->
            listOf(p, "$start", "$expiry", "1", "1", "1", "live", "live", "1", if (p in TRAFFIC) "2" else "0", "", "1", "1")
                .map { it.toByteArray() }
        }

        /** FPU0 update blob: 0x14-byte header (fuel type at 0x0c), then per station 0xfc + u16 cents, or 0x00 = no price. */
        fun fpu0(type: Int, cents: List<Int>): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(byteArrayOf(0x30, 0x55, 0x50, 0x46)); o.write(ByteArray(8)); o.write(type); o.write(ByteArray(7))
            for (p in cents) if (p == 0) o.write(0) else { val v = minOf(0xffff, p); o.write(0xfc); o.write(v and 0xff); o.write(v shr 8) }
            return o.toByteArray()
        }

        /** Service discovery text (docs/service_discovery.md): every service the client looks up points at this server. */
        fun discoveryBlob(base: String, router: String): String {
            val groups = mutableListOf(
                "HERCULES" to listOf("HERCULES" to "$base/h204"),
                "ROADCONDITIONS" to listOf("ROADCONDITIONS" to "$base/roadconditions"),
                "ROUTER2" to listOf("PORTLAND" to "$base/portland", "ROUTER" to router),
                "WEATHER2" to listOf("WEATHER" to "$base/weather"),
                "LOCALSEARCH" to listOf("LOCALSEARCH" to "$base/localsearch"),
                "APN" to listOf("APN_LOG" to "$base/apn", "TEST_BIN" to "$base/apn_test"),
                "NAVTRAFFIC" to listOf("NTadespt" to "$base/portland", "NAVTRAFFIC" to "$base/portland"),
            )
            for ((g, k) in listOf("LOGON" to "LOGON", "PRODACT" to "PRODACT", "SVCACT" to "SVCACT", "ACCOUNT" to "ACCOUNT",
                "ACCOUNT2" to "ACCOUNT", "MESSAGE" to "MESSAGE", "BUDDY" to "BUDDY", "DYNSC" to "DYNSC", "FUELPRICES" to "FUELPRICES",
                "FUELPRICES" to "FUELPRICESEUROPE", "MISC" to "MB", "ONDEVPAYMENT" to "ONDEVPAYMENT", "TRACK" to "TRACK"))
                groups += g to listOf(k to "$base/${g.lowercase()}")
            return groups.joinToString("") { (name, entries) -> name + "\n" + entries.joinToString("") { (k, v) -> "$k|$v\n" } + "\n" }
        }
    }
}

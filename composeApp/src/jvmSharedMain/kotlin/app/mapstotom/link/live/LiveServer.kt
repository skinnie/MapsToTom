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
    private val outbox: app.mapstotom.link.CarOutbox? = null,
) : LocalService {
    /** Convenience: real Open-Meteo weather and TomTom traffic with the user's key. */
    constructor(net: Net, tomtomKey: () -> String?, fuel: FuelPriceSets, store: LiveStore, log: (String) -> Unit = {},
                outbox: app.mapstotom.link.CarOutbox? = null) :
        this(LiveConfig(), TomTomSource(tomtomKey, net, log = log), Weather.openMeteo(net), fuel, store, net, log, outbox)

    /** [sending]: the card path of a file in this reply, so the app can show its progress. */
    class Reply(val code: Int, val type: String, val body: ByteArray, val sending: String? = null)

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
        output.write(head.toByteArray(Charsets.US_ASCII))
        if (r.sending == null) output.write(r.body) else sendWithProgress(output, r)
        output.flush()
    }

    private fun sendWithProgress(output: OutputStream, r: Reply) {
        val state = app.mapstotom.link.CarLinkState
        state.transferStart(r.sending!!, r.body.size)
        try {
            var off = 0
            while (off < r.body.size) {
                val n = minOf(1024, r.body.size - off)
                output.write(r.body, off, n); output.flush()
                off += n; state.transferSent(off)
            }
        } catch (e: Exception) { state.transferEnd(); throw e }
    }

    fun respond(method: String, path: String, body: ByteArray = ByteArray(0)): Reply {
        val query = path.substringAfter('?', "")
        // The TomTom keeps the service list it fetched and only asks again when the address in servicediscovery.txt
        // changes (it drops any "?query"), so /proxy/<anything>/directory.php is the same list: bump the path to refetch.
        val p = path.substringBefore('?').let { if (it.startsWith("/proxy/") && it.endsWith("/directory.php")) "/proxy/directory.php" else it }
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
        if (p == "/m2t/next") app.mapstotom.link.CarLinkState.report(app.mapstotom.link.CarDeviceReport.fromQuery(query))
        if (p == "/m2t/next") {
            val item = outbox?.next(gps = "caps=ephem" in query.split('&'))
            if (item == null) app.mapstotom.link.CarLinkState.transferEnd()
            return Reply(200, "application/octet-stream", app.mapstotom.link.CarOutbox.frame(item), item?.path)
        }
        if (p == "/m2t/done") {
            query.split('&').firstOrNull { it.startsWith("id=") }?.substring(3)?.toLongOrNull()?.let { outbox?.done(it); app.mapstotom.link.CarLinkState.transferEnd(); log("car link: file $it saved on the TomTom") }
            return text("OK".toByteArray())
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

        /**
         * Traffic keys of the maps the car link knows: a map's traffic.dat lines 2 and 3 (service key, product code).
         * The TomTom only asks for traffic when the discovery and the account have its own map's key.
         */
        private val MAP_TRAFFIC = listOf(
            "NTadespt" to "301E17F32843842C10C07",                               // Iberia (ONE v4)
            "NTweu" to "409A01F32D01501C10701C07601817301E17843842933C12242",  // Western Europe (GO 950, Europe Zone 4)
        )

        /** Subscription rows for AccountInfo (docs/captured_requests.md); 13 columns as NavCore reads them. */
        private val PRODUCTS = listOf("SPD_DD", "SPD_DDS", "SPD_GD", "SPD_MD", "SPD_PD", "SPD_CD", "SPD_NV", "SPD_VD", "SPD_RT",
            "SPD_CU", "SPD_SD", "ROADCONDITIONS", "FUELPRICES", "LOCALSEARCH", "TRACK", "DYNSC", "BUDDY", "MISC", "NAVTRAFFIC",
            "WEATHER2") + MAP_TRAFFIC.flatMap { it.toList() }
        private val TRAFFIC = setOf("NAVTRAFFIC") + MAP_TRAFFIC.flatMap { it.toList() }

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
                "NAVTRAFFIC" to MAP_TRAFFIC.map { it.first to "$base/portland" } + ("NAVTRAFFIC" to "$base/portland"),
            )
            for ((g, k) in listOf("LOGON" to "LOGON", "PRODACT" to "PRODACT", "SVCACT" to "SVCACT", "ACCOUNT" to "ACCOUNT",
                "ACCOUNT2" to "ACCOUNT", "MESSAGE" to "MESSAGE", "BUDDY" to "BUDDY", "DYNSC" to "DYNSC", "FUELPRICES" to "FUELPRICES",
                "FUELPRICES" to "FUELPRICESEUROPE", "MISC" to "MB", "ONDEVPAYMENT" to "ONDEVPAYMENT", "TRACK" to "TRACK"))
                groups += g to listOf(k to "$base/${g.lowercase()}")
            return groups.joinToString("") { (name, entries) -> name + "\n" + entries.joinToString("") { (k, v) -> "$k|$v\n" } + "\n" }
        }
    }
}

package app.mapstotom.link.live

import app.mapstotom.core.Json
import app.mapstotom.core.Net
import app.mapstotom.core.USER_AGENT
import app.mapstotom.core.urlEncode
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/*
 * Hercules (POST /h204): login, subscribe, calls (traffic incidents, local search), parameter uploads.
 * Port of server/hercules.py and localsearch.py. Payloads are Blowfish-ECB, zero padded.
 */
class Hercules(
    private val source: IncidentSource,
    private val net: Net? = null,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val KIND_LOGIN = 3L; const val KIND_SUBSCRIBE = 6L; const val KIND_UNSUBSCRIBE = 7L; const val KIND_CALL = 0x10L
        const val KIND_UPLOAD = 14L; const val KIND_PARAM_ACK = 0x13L
        const val SLOT_INCIDENTS = 7L; const val SLOT_LOCALSEARCH = 4L
        const val MAX_INCIDENTS = 100
        /** Our login key: the device only stores it; any multiple of 8 bytes. */
        val LOGIN_KEY = ByteArray(8) { it.toByte() }
        /** Static key of the login payload and of the session-key unwrap (captured 2026-10-26). */
        val LOGIN_CIPHER_KEY = "#p<l]u*s".toByteArray(Charsets.US_ASCII)

        fun blowfish(key: ByteArray, data: ByteArray, decrypt: Boolean): ByteArray =
            Cipher.getInstance("Blowfish/ECB/NoPadding").run {
                init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Blowfish"))
                doFinal(data)
            }

        /** BF-decrypt(static, loginKey) + TAB + serial + TAB + IMEI + SIM id, cut to 56 bytes, TAB-padded to a multiple of 8. */
        fun sessionKey(loginKey: ByteArray, serial: ByteArray, imei: ByteArray, simId: ByteArray): ByteArray {
            var k = blowfish(LOGIN_CIPHER_KEY, loginKey, true) + byteArrayOf(9) + serial + byteArrayOf(9) + imei + simId
            if (k.size > 56) k = k.copyOf(56)
            return k + ByteArray((8 - k.size % 8) % 8) { 9 }
        }

        fun decodePayload(buf: ByteArray, key: ByteArray): Any? =
            Hermes.decode(blowfish(key, buf.copyOf(buf.size / 8 * 8), true))

        fun error(code: Long, text: String = "") = HStruct(4 to code, 5 to text)
    }

    /** serial -> Blowfish key of later requests. */
    private val sessions = ConcurrentHashMap<String, ByteArray>()

    fun hasSession(serial: String) = sessions.containsKey(serial)

    private fun bytes(v: Any?): ByteArray = when (v) { is String -> v.toByteArray(Charsets.UTF_8); is ByteArray -> v; null -> ByteArray(0); else -> v.toString().toByteArray() }

    fun handleMessage(msg: Any?): HStruct {
        if (msg !is HStruct) return error(9, "bad message")
        val kind = msg[1] as? Long
        var payload = msg[3]
        val serial = msg[2]?.toString()
        if (payload is ByteArray) {
            try {
                if (kind == KIND_LOGIN) {
                    val lp = decodePayload(payload, LOGIN_CIPHER_KEY) as HStruct   // {1: serial, 2: IMEI, 3: SIM id, 4: serial}
                    if (serial != null) sessions[serial] = sessionKey(LOGIN_KEY, bytes(lp[1]), bytes(lp[2]), bytes(lp[3]))
                    payload = lp
                } else if (serial != null && sessions.containsKey(serial)) {
                    payload = decodePayload(payload, sessions.getValue(serial))
                }
            } catch (e: Exception) {
                log("hercules: payload decrypt failed: ${e.message}")
            }
        }
        val p = payload as? HStruct
        return when (kind) {
            KIND_LOGIN -> HStruct(1 to KIND_LOGIN, 6 to HStruct(0x12 to LOGIN_KEY))
            KIND_SUBSCRIBE, KIND_UNSUBSCRIBE -> HStruct(1 to 1L)
            KIND_CALL -> {
                val slot = p?.get(5) as? Long
                val args = p?.get(0x10)
                when (slot) {
                    SLOT_INCIDENTS, null -> {
                        val bb = bbox(args)
                        var incs = source.incidents(bb)
                        if (bb != null && incs.size > MAX_INCIDENTS) {   // the nearest to the middle of the view
                            val cx = (bb[0] + bb[2]) / 2e5; val cy = (bb[1] + bb[3]) / 2e5
                            incs = incs.sortedBy { (it.lon - cx) * (it.lon - cx) + (it.lat - cy) * (it.lat - cy) }.take(MAX_INCIDENTS)
                        }
                        log("hercules: ${incs.size} incidents")
                        HStruct(1 to KIND_CALL, 6 to trafficPayload(SLOT_INCIDENTS.toInt(), incs))
                    }
                    SLOT_LOCALSEARCH -> LocalSearch.handle(args, net)
                    else -> HStruct(1 to KIND_CALL, 6 to HStruct(5 to slot))   // road conditions etc.: empty payload
                }
            }
            // parameter upload {8: index, 9: value}; ack = kind 0x13 (tag 0x14 true = done, no resend)
            KIND_UPLOAD -> (p?.get(8) as? Long)?.let { HStruct(1 to KIND_PARAM_ACK, 8 to it, 0x13 to true, 0x14 to true) }
                ?: error(9, "bad parameter upload")
            else -> error(9, "unknown kind $kind")
        }
    }

    /** payload tag 0x10: {3: [{30, 31, 32, 33: Long}]} */
    private fun bbox(args: Any?): LongArray? = runCatching {
        val r = ((args as HStruct)[3] as List<*>)[0] as HStruct
        longArrayOf(r[30] as Long, r[31] as Long, r[32] as Long, r[33] as Long)
    }.getOrNull()

    fun handleBody(body: ByteArray): ByteArray {
        val msg = Hermes.unpackBody(body)
        val reply: Any = if (msg is List<*>) msg.map { handleMessage(it) } else handleMessage(msg)
        return Hermes.packBody(reply)
    }

    /** The serial of a single (not batched) login message, or null. */
    fun loginSerial(body: ByteArray): String? = runCatching {
        val m = Hermes.unpackBody(body) as? HStruct
        if (m != null && m[1] == KIND_LOGIN) m[2]?.toString() else null
    }.getOrNull()
}

/* LOCALSEARCH (Hercules slot 4): Photon (OpenStreetMap) lookups. */
object LocalSearch {
    private fun result(name: String, addr: String, lat: Double, lon: Double, phone: String = "") =
        HStruct(1 to name, 2 to addr, 3 to pyRound(lat * 1e5), 4 to pyRound(lon * 1e5), 6 to 0L, 7 to 0L, 8 to phone)

    fun reply(results: List<HStruct>, total: Int? = null): HStruct {
        val data = HStruct(0 to (total ?: results.size).toLong(), 1 to results.size.toLong(), 2 to "", 3 to "", 4 to results)
        return HStruct(1 to 0x10L, 6 to HStruct(5 to 4L, 0x10 to data))
    }

    fun results(doc: Map<*, *>): List<HStruct> = (doc["features"] as? List<*>).orEmpty().map { f ->
        f as Map<*, *>
        val p = f["properties"] as Map<*, *>; val c = (f["geometry"] as Map<*, *>)["coordinates"] as List<*>
        fun s(k: String) = (p[k] as? String)?.takeIf { it.isNotEmpty() }
        val street = listOfNotNull(s("street"), s("housenumber")).joinToString(" ")
        val town = listOfNotNull(s("postcode"), s("city")).joinToString(" ")
        val addr = listOfNotNull(street.ifEmpty { null }, town.ifEmpty { null }, s("country")).joinToString(", ")
        result(s("name") ?: street.ifEmpty { addr }, addr, (c[1] as Number).toDouble(), (c[0] as Number).toDouble())
    }

    fun handle(args: Any?, net: Net?): HStruct {
        if (args !is HStruct || args[0] != 1L || net == null)   // details (mode 2) not decoded: "no result"
            return HStruct(1 to 0x10L, 6 to HStruct(5 to 4L, 0x10 to HStruct(0 to 0L)))
        return try {
            val n = (args[5] as? Long ?: 10L).toInt(); val off = (args[6] as? Long ?: 0L).toInt()
            val a = (args[7] as? Long ?: 0L) / 1e5; val b = (args[8] as? Long ?: 0L) / 1e5
            val (lat, lon) = if (Math.abs(a) <= 90) a to b else b to a   // tag 7/8 order inferred
            val lang = (args[4] as? String)?.takeIf { it in setOf("en", "de", "fr", "it") } ?: "en"
            val q = urlEncode(listOf("q" to (args[2] as? String ?: ""), "lat" to pyFixed(lat, 5), "lon" to pyFixed(lon, 5),
                "limit" to "${n + off}", "lang" to lang))
            val r = net.get("https://photon.komoot.io/api/?$q", mapOf("User-Agent" to USER_AGENT), timeoutMs = 20_000)
            val res = results(Json.parse(r.text) as Map<*, *>)
            reply(res.drop(off).take(n), total = res.size)
        } catch (e: Exception) {
            reply(emptyList())
        }
    }
}

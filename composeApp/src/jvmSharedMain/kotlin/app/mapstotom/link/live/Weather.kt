package app.mapstotom.link.live

import app.mapstotom.core.Json
import app.mapstotom.core.Net
import app.mapstotom.core.USER_AGENT
import app.mapstotom.core.urlEncode
import java.time.LocalDate
import java.time.ZoneOffset

/* WEATHER2: GET /weather?a=w2&m=gwl&p1=<lon>x<lat> -> 5-day forecast from Open-Meteo (no key). Port of server/weather.py. */
object Weather {
    // WMO code -> 6-character icon code (2 sky + 4 precipitation)
    private val WMO = mapOf(0 to "so____", 1 to "ms____", 2 to "wb____", 3 to "bd____", 45 to "nb____", 48 to "nb____",
        51 to "bwr1__", 53 to "bwr1__", 55 to "bwr1__", 56 to "bwgr1_", 57 to "bwgr1_",
        61 to "bwr1__", 63 to "bwr2__", 65 to "bwr3__", 66 to "bwgr2_", 67 to "bwgr2_",
        71 to "bws1__", 73 to "bws2__", 75 to "bws3__", 77 to "bws1__",
        80 to "bwsr1_", 81 to "bwsr2_", 82 to "bwsr3_", 85 to "bwsns1", 86 to "bwsns2",
        95 to "bwg1__", 96 to "bwg2__", 99 to "bwg3__")

    fun openMeteo(net: Net): (Double, Double) -> Map<*, *> = { lat, lon ->
        val q = urlEncode(listOf("latitude" to pyFixed(lat, 4), "longitude" to pyFixed(lon, 4), "timezone" to "UTC",
            "forecast_days" to "5", "daily" to "temperature_2m_max,temperature_2m_min,weather_code,wind_speed_10m_max," +
                "wind_direction_10m_dominant,precipitation_probability_max,uv_index_max,relative_humidity_2m_mean,visibility_mean"))
        val r = net.get("https://api.open-meteo.com/v1/forecast?$q", mapOf("User-Agent" to USER_AGENT), timeoutMs = 20_000)
        if (r.code != 200) error("Open-Meteo HTTP ${r.code}")
        Json.parse(r.text) as Map<*, *>
    }

    private fun n(v: Any?, default: Long): Long = (v as? Number)?.let { pyRound(it.toDouble()) } ?: default

    fun rows(d: Map<*, *>): List<List<ByteArray>> {
        val dl = d["daily"] as Map<*, *>
        return (dl["time"] as List<*>).take(5).mapIndexed { i, day ->
            val ts = LocalDate.parse(day as String).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            fun g(k: String): Any? = (dl[k] as? List<*>)?.getOrNull(i)
            val vis = g("visibility_mean") as? Number
            val hum = g("relative_humidity_2m_mean")
            listOf(ts, n(g("temperature_2m_max"), -999), n(g("temperature_2m_min"), -999),
                WMO[(g("weather_code") as? Number)?.toInt()] ?: "bd____", maxOf(n(g("wind_speed_10m_max"), 0), 0),
                Math.floorMod(n(g("wind_direction_10m_dominant"), 0), 361L), 0,
                n(g("precipitation_probability_max"), 0), n(g("uv_index_max"), 0).coerceIn(0, 16),
                -1, 0, "", 0, if (hum == null) "" else n(hum, 0), if (vis == null) "" else (vis.toDouble() / 1000).toLong(),
            ).map { it.toString().toByteArray() }
        }
    }

    fun reply(query: String, fetch: (Double, Double) -> Map<*, *>, log: (String) -> Unit = {}): ByteArray {
        val m = Regex("(-?\\d+)x(-?\\d+)").matchEntire(parseQuery(query)["p1"] ?: "") ?: return XMsg.reply(emptyList(), 204)
        val lon = m.groupValues[1].toLong() / 1e5; val lat = m.groupValues[2].toLong() / 1e5
        val rows = try { rows(fetch(lat, lon)) } catch (e: Exception) { log("weather fetch failed: ${e.message}"); return XMsg.reply(emptyList(), 204) }
        return XMsg.reply(listOf(XMsg.list(listOf(pyFixed(lat, 2).toByteArray(), pyFixed(lon, 2).toByteArray())),
            XMsg.int(rows.size.toLong()), XMsg.table(rows)))
    }
}

package app.mapstotom.link.live

import app.mapstotom.core.Json
import app.mapstotom.core.Net
import app.mapstotom.core.USER_AGENT
import app.mapstotom.core.urlEncode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/*
 * TomTom Traffic incidentDetails -> Incident rows (port of server/sources.py parse_tomtom and tomtom).
 * Each user brings a free developer key (2,500 requests/day). The fields MUST include length,roadNumbers,
 * or no road counts as major.
 */
object TomTomTraffic {
    const val FIELDS = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay," +
        "delay,from,length,roadNumbers,events{description}}}}"

    /** Speed sent for closures: 0 and negative percents are not counted in the device's route time. */
    const val CLOSED_SPEED = 300L
    private const val SPLIT_M = 500.0
    private val TT_CAT = mapOf(0 to 3, 1 to 4, 2 to 2, 3 to 3, 4 to 1, 5 to 5, 6 to 6, 7 to 7, 8 to 8, 9 to 9, 10 to 10, 11 to 1, 14 to 3)
    private val MOTORWAY = listOf("A", "IC", "IP", "E", "AP", "AG", "AU")
    private val MAJOR = MOTORWAY + listOf("EN", "N")
    private fun refKmh(major: Boolean) = if (major) 90 else 45

    private fun pattern(prefix: List<String>): Regex =
        Regex("^(" + prefix.sortedByDescending { it.length }.joinToString("|") + ")(\\d|(?<=N)[IVX]+\\b)")
    private val MAJOR_RE = pattern(MAJOR)
    private val MOTORWAY_RE = pattern(MOTORWAY)

    /** A road number of that class: A1, IC19, AP7, EN125, N8, N-II. Municipal EM588/CM1185 are not major. */
    fun isMajor(roads: List<*>?, motorwayOnly: Boolean = false): Boolean {
        val re = if (motorwayOnly) MOTORWAY_RE else MAJOR_RE
        return roads.orEmpty().any { r -> r is String && re.containsMatchIn(r.uppercase().replace("-", "").replace(" ", "")) }
    }

    private fun num(v: Any?): Double? = (v as? Number)?.toDouble()
    private fun truthy(v: Any?) = when (v) { null -> false; is Number -> v.toDouble() != 0.0; is String -> v.isNotEmpty(); is Boolean -> v; is List<*> -> v.isNotEmpty(); else -> true }

    /** Vertices [lon, lat] of the geometry (the longest part of a MultiLineString). */
    private fun line(g: Map<*, *>): List<List<Double>> {
        val c = g["coordinates"] as List<*>
        fun pt(x: Any?) = (x as List<*>).map { (it as Number).toDouble() }
        if (g["type"] == "Point" || c[0] !is List<*>) return listOf(pt(c))
        val parts = if ((c[0] as List<*>)[0] is List<*>) c.maxByOrNull { (it as List<*>).size } as List<*> else c
        return parts.map { pt(it) }
    }

    private fun pointsAlong(c: List<List<Double>>, step: Double): Pair<Double, List<List<Double>>> {
        if (c.size < 2) return 0.0 to listOf(c[0])
        val k = cos(Math.toRadians(c[0][1])) * 111195
        val cum = ArrayList<Double>().apply { add(0.0) }
        for (i in 1 until c.size) {
            val a = c[i - 1]; val b = c[i]
            cum += cum.last() + hypot((b[0] - a[0]) * k, (b[1] - a[1]) * 111195)
        }
        val total = cum.last()
        val n = maxOf(1, ceil(total / step).toInt())
        val idx = ArrayList<Int>()
        for (j in 0 until n) {
            val m = total * (j + 0.5) / n
            var best = 0
            for (i in c.indices) if (Math.abs(cum[i] - m) < Math.abs(cum[best] - m)) best = i
            if (best !in idx) idx += best
        }
        return total to idx.map { c[it] }
    }

    private fun crc(s: String) = CRC32().apply { update(s.toByteArray(Charsets.UTF_8)) }.value

    /** The first 59 characters (Python str slicing counts code points). */
    internal fun cut(s: String, n: Int = 59): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    fun parse(doc: Map<*, *>): List<Incident> {
        val out = ArrayList<Incident>()
        for (f in doc["incidents"] as? List<*> ?: emptyList<Any>()) {
            f as Map<*, *>
            val p = f["properties"] as Map<*, *>; val g = f["geometry"] as Map<*, *>
            val events = (p["events"] as? List<*>)?.takeIf { it.isNotEmpty() }
            var ev = ((events?.get(0) as? Map<*, *>)?.get("description") as? String) ?: ""
            val where = p["from"] as? String ?: ""
            if (where.isNotEmpty()) ev = if (ev.isNotEmpty()) "$ev $where" else where
            val mag = (p["magnitudeOfDelay"] as? Number)?.toInt()
            val delay = num(p["delay"]) ?: 0.0
            val icon = (p["iconCategory"] as? Number)?.toInt() ?: 0
            val roads = p["roadNumbers"] as? List<*>
            val major = isMajor(roads)
            val (computed, pts) = pointsAlong(line(g), SPLIT_M)
            val length = if (truthy(p["length"])) num(p["length"])!! else computed
            val closed = icon == 8 || mag == 4
            if (closed && !major) continue   // small city-street closures would make the device ask "road closed, avoid?" everywhere
            val speed: Long? = when {
                closed -> CLOSED_SPEED
                delay != 0.0 && length > 0 -> {
                    val ref = refKmh(major) / 3.6
                    val v = length / (length / ref + delay) * 3.6
                    (maxOf(3.0, minOf((refKmh(major) - 5).toDouble(), v)) * 100).toLong()
                }
                else -> when (mag) { 3 -> 500L; 2 -> 1500L; 1 -> 3000L; else -> if (delay != 0.0) 1500L else null }
            }
            val motorway = isMajor(roads, motorwayOnly = true)
            val tier = if (motorway && (closed || icon == 6 || (mag ?: 0) >= 2)) 0 else if (major || icon == 6 || delay != 0.0) 1 else 2
            val id = p["id"] as String
            val base = crc(id) and 0xFFFFFF
            val share = if (length != 0.0) pyRound(length / pts.size / 10) else 0L
            pts.forEachIndexed { k, pt ->
                out += Incident(
                    id = if (k == 0) base else crc("$id#$k") and 0xFFFFFF,
                    lat = pt[1], lon = pt[0], category = TT_CAT[icon] ?: 3, speed = speed, text = cut(ev),
                    group = base, tier = tier, delay = if (closed) 1e6 else delay, length10 = share,
                )
            }
        }
        return out
    }
}

/**
 * Incidents for any area from the TomTom API: the area is cut into grid-aligned 1 x 1 degree tiles (the API takes at
 * most 10,000 km2), each cached [ttlMs]; at most [maxTiles] per fetch, nearest the centre first. Stale tiles are served
 * while they refresh; missing ones are waited for at most [waitMs] in total (the rest comes with the next refresh).
 */
class TomTomSource(
    private val key: () -> String?,
    private val net: Net,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 600_000,
    private val maxTiles: Int = 30,
    private val waitMs: Long = 5_000,
    private val log: (String) -> Unit = {},
) : IncidentSource {
    private val cache = ConcurrentHashMap<Pair<Int, Int>, Pair<Long, List<Incident>>>()
    private val busy = ConcurrentHashMap<Pair<Int, Int>, Future<*>>()
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "tomtom-traffic").apply { isDaemon = true } }

    private fun fetch(t: Pair<Int, Int>, k: String) {
        try {
            val (w, s) = t
            val q = urlEncode(listOf(
                "key" to k, "bbox" to "%f,%f,%f,%f".format(java.util.Locale.ROOT, w.toDouble(), s.toDouble(), w + 1.0, s + 1.0),
                "fields" to TomTomTraffic.FIELDS, "language" to "en-GB", "timeFilter" to "present"))
            val r = net.get("https://api.tomtom.com/traffic/services/5/incidentDetails?$q", mapOf("User-Agent" to USER_AGENT), timeoutMs = 30_000)
            if (r.code != 200) error("HTTP ${r.code}")
            cache[t] = now() to TomTomTraffic.parse(Json.parse(r.text) as Map<*, *>)
        } catch (e: Exception) {
            log("TomTom traffic tile $t failed: ${e.message}")
        } finally {
            busy.remove(t)
        }
    }

    override fun incidents(bbox: LongArray?): List<Incident> {
        val k = key()?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (bbox == null) return emptyList()
        val v = bbox.map { it / Incident.SCALE }
        val w = minOf(v[0], v[2]); val s = minOf(v[1], v[3]); val e = maxOf(v[0], v[2]); val n = maxOf(v[1], v[3])
        val cx = (w + e) / 2; val cy = (s + n) / 2
        val tiles = (floor(w).toInt() until ceil(e).toInt()).flatMap { tx -> (floor(s).toInt() until ceil(n).toInt()).map { tx to it } }
            .sortedBy { (tx, ty) -> (tx + .5 - cx) * (tx + .5 - cx) + (ty + .5 - cy) * (ty + .5 - cy) }.take(maxTiles)
        val missing = ArrayList<Future<*>>()
        for (t in tiles) {
            val hit = cache[t]
            if (hit != null && now() - hit.first <= ttlMs) continue
            val f = busy.computeIfAbsent(t) { pool.submit { fetch(t, k) } }
            if (hit == null) missing += f
        }
        val deadline = System.nanoTime() + waitMs * 1_000_000
        for (f in missing) runCatching { f.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
        val seen = HashSet<Long>(); val out = ArrayList<Incident>()
        for (t in tiles) for (i in cache[t]?.second.orEmpty())
            if (i.id !in seen && i.lon in w..e && i.lat in s..n) { seen += i.id; out += i }
        return out
    }
}

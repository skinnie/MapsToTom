package app.mapstotom.link.live

import java.util.zip.CRC32

/*
 * Portland (LD traffic over the phone): GET /portland?...&a=nt&m=gtuo|gtd2, a=sv m=son, a=pr m=gpid.
 * Port of server/portland.py; formats decoded from NavCore 9.541 and verified on the ONE v4.
 */
class Portland(private val source: IncidentSource, private val log: (String) -> Unit = {}) {
    companion object {
        val DEFAULT_BBOX = longArrayOf(-1072332, 3726000, -656052, 4050000)
        const val MAX_INCIDENTS = 150
        private const val NO_SPEED = 3000L
        private val NT_OPS = setOf("gtmo", "gstuo", "gtuo", "gtd2")
    }

    /** Last area replies by 24-bit id: gtd2 (details) carries no area. */
    private val sent = HashMap<Long, Incident>()

    /** Request parameters typed by the t= letters: I -> Long, B -> Boolean, L -> LongArray area (or the raw text), S -> String. */
    internal fun params(q: Map<String, String>): List<Any> = (q["t"] ?: "").mapIndexed { idx, letter ->
        val raw = q["p${idx + 1}"] ?: ""
        when (letter) {
            'L' -> raw.split('x').map { it.toLongOrNull() }.let { l -> if (l.all { it != null }) l.map { it!! }.toLongArray() else raw }
            'I' -> raw.ifEmpty { "0" }.toLongOrNull() ?: 0L
            'B' -> raw in setOf("1", "true", "True")
            else -> raw
        }
    }

    /** At most 150 rows: closures/jams on motorways first, then other main roads, then the rest; nearest the centre within a tier. */
    fun incidents(bbox: LongArray?): List<Incident> {
        val bb = bbox ?: DEFAULT_BBOX
        val incs = source.incidents(bb).filter { !it.removed }
        val cx = (bb[0] + bb[2]) / 2e5; val cy = (bb[1] + bb[3]) / 2e5
        val groups = LinkedHashMap<Long, MutableList<Incident>>()
        incs.forEach { groups.getOrPut(it.group) { ArrayList() } += it }
        val sorted = groups.values.sortedWith(compareBy<List<Incident>>({ it[0].tier },
            { g -> g.minOf { (it.lon - cx) * (it.lon - cx) + (it.lat - cy) * (it.lat - cy) } }))
        val out = ArrayList<Incident>()
        for (g in sorted) if (out.size + g.size <= MAX_INCIDENTS) out += g
        return out
    }

    private fun row(i: Incident) = listOf("1", "${i.id24}", if (i.direction) "+" else "-",
        ">%dx%d".format(pyRound(i.lon * Incident.SCALE), pyRound(i.lat * Incident.SCALE)),
        "${i.category.coerceIn(1, 10)}", "${i.speed ?: NO_SPEED}", "0", "${i.length10}").map { it.toByteArray() }

    /** 6 columns: flag, id, text (ASCII, one '?' per other character, <= 59), three empty strings. */
    private fun detail(i: Incident): List<ByteArray> {
        val t = StringBuilder()
        i.text.codePoints().forEach { t.append(if (it < 128) it.toChar() else '?') }
        val txt = t.toString().replace('\t', ' ').replace('\n', ' ').take(59)
        return listOf("1".toByteArray(), "${i.id24}".toByteArray(), txt.toByteArray(Charsets.US_ASCII), ByteArray(0), ByteArray(0), ByteArray(0))
    }

    fun handle(query: String): ByteArray {
        val q = parseQuery(query, keepBlank = true)
        val a = q["a"]; val m = q["m"]
        if (a == "pr") return XMsg.reply(emptyList(), 204)         // keeps the push channel off
        if (a == "sv") return XMsg.reply(emptyList())              // any OK reply
        if (a != "nt" || m !in NT_OPS) { log("portland: unexpected $query"); return XMsg.reply(emptyList(), 204) }
        val p = params(q)
        val area = p.firstOrNull { it is LongArray && it.size == 4 } as LongArray?
        val incs = incidents(area)
        if (m == "gtd2") {
            val want = ((p.getOrNull(2) as? String)?.ifEmpty { "0" }?.toLongOrNull() ?: (p.getOrNull(2) as? Long) ?: 0L) and 0xFFFFFF
            val hit = sent[want] ?: incs.firstOrNull { it.id24 == want }
            return XMsg.reply(listOf(XMsg.table(if (hit != null) listOf(detail(hit)) else emptyList())))
        }
        incs.forEach { sent[it.id24] = it }
        val crc = CRC32().apply { update(incs.joinToString(",") { "${it.id24}" }.toByteArray()) }.value
        val counter = crc % 1_000_000_000 + 1                      // changes with the incident set
        log("portland $m: ${incs.size} incidents")
        return XMsg.reply(listOf(XMsg.int(counter), XMsg.int(incs.size.toLong()), XMsg.table(incs.map(::row))))
    }
}

package app.mapstotom.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/*
 * The GL1 GPS program (bin/gltt, GLL 185.5001.041) has a table of satellite orbits from 2008 built in, which it uses to
 * predict the sky when it has no newer almanac. Most of those satellites have since moved or been replaced. This
 * writes a current public almanac (YUMA format) into that table, in the user's own file.
 *
 * The table has 30 records of 88 bytes: PRN (u32), then doubles e, toa, inclination, rate of right ascension, sqrt(A),
 * right ascension, argument of perigee, mean anomaly, af0, af1, then the 10-bit week (u32).
 */

/** Offered to users only once it has proved itself on a real TomTom (docs/gps-almanac.md). A card already updated can still undo it. */
const val ALMANAC_FEATURE = false

const val ALMANAC_URL = "https://celestrak.org/GPS/almanac/Yuma/almanac.yuma.txt"
const val GLTT_PATH = "bin/gltt"
const val GLTT_BACKUP = "bin/gltt.mapstotom-orig"
private const val GLTT_NEW = "bin/gltt.mapstotom-new"
private const val GLTT_ORIGINAL_MD5 = "645d173e3b7bb0fd2a10d99657da5611"
private const val GLTT_SIZE = 897_732
private const val TABLE = 0xd38c0
private const val RECORD = 88
private const val SLOTS = 30
private const val GPS_EPOCH = 315_964_800L
private const val WEEK_SECONDS = 604_800L

class YumaSatellite(val prn: Int, val healthy: Boolean, val values: DoubleArray, val week: Int)

private val YUMA_FIELDS = listOf("Eccentricity", "Time of Applicability", "Orbital Inclination", "Rate of Right Ascen", "SQRT(A)",
    "Right Ascen at Week", "Argument of Perigee", "Mean Anom", "Af0", "Af1")

/** The satellites of a YUMA almanac. Anything that doesn't look like a GPS orbit is an error. */
fun parseYuma(text: String): List<YumaSatellite> {
    val out = text.split(Regex("\\*{4,} Week")).drop(1).map { block ->
        val fields = block.lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        fun field(name: String) = fields.entries.firstOrNull { it.key.startsWith(name) }?.value ?: error("YUMA almanac without $name")
        val v = DoubleArray(YUMA_FIELDS.size) { field(YUMA_FIELDS[it]).toDouble() }
        val s = YumaSatellite(field("ID").toInt(), field("Health").toInt() == 0, v, field("week").toInt())
        require(s.prn in 1..32 && s.week in 0..1023 && v[0] in 0.0..0.1 && v[1] in 0.0..WEEK_SECONDS.toDouble() &&
            v[2] in 0.8..1.15 && v[4] in 5100.0..5200.0 && (5..7).all { kotlin.math.abs(v[it]) <= 7.0 }) { "Satellite ${s.prn} doesn't look like a GPS orbit" }
        s
    }
    require(out.size >= 24) { "Only ${out.size} satellites in the almanac" }
    return out
}

/** [gltt] with its table filled from [satellites]: the same PRNs where possible, then spare healthy ones in the free slots. */
fun patchGlttAlmanac(gltt: ByteArray, satellites: List<YumaSatellite>): ByteArray {
    require(gltt.size == GLTT_SIZE && md5(gltt) == GLTT_ORIGINAL_MD5) { "This GPS program isn't the supported version" }
    val out = gltt.copyOf()
    val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    val healthy = satellites.filter { it.healthy }.associateBy { it.prn }
    val slots = (0 until SLOTS).map { b.getInt(TABLE + it * RECORD) }
    val spare = ArrayDeque(healthy.keys.filter { it !in slots }.sorted())
    slots.forEachIndexed { i, prn ->
        val s = healthy[prn] ?: spare.removeFirstOrNull()?.let(healthy::getValue) ?: return@forEachIndexed
        val at = TABLE + i * RECORD
        b.putInt(at, s.prn)
        s.values.forEachIndexed { k, v -> b.putDouble(at + 4 + k * 8, v) }
        b.putInt(at + 84, s.week)
    }
    return out
}

/** What the card's GPS program holds. [date] is the almanac's reference time. */
class AlmanacInfo(val builtIn: Boolean, val date: Instant, val canRemove: Boolean)

fun almanacInfo(fs: DeviceFs, now: Instant = Instant.now()): AlmanacInfo? {
    if (!fs.exists(GLTT_PATH) || fs.size(GLTT_PATH) != GLTT_SIZE.toLong()) return null
    val data = fs.read(GLTT_PATH) ?: return null
    val builtIn = md5(data) == GLTT_ORIGINAL_MD5
    val kept = fs.read(GLTT_BACKUP)?.let { md5(it) == GLTT_ORIGINAL_MD5 } == true
    if (!builtIn && !kept) return null
    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val week = b.getInt(TABLE + 84).toLong()
    val toa = b.getDouble(TABLE + 12).toLong()
    // A 10-bit week: the built-in table is from 2008, a written one is the rollover nearest to now.
    val full = if (builtIn) 1428L else {
        val nowWeek = (now.epochSecond - GPS_EPOCH) / WEEK_SECONDS
        week + 1024 * Math.round((nowWeek - week) / 1024.0)
    }
    return AlmanacInfo(builtIn, Instant.ofEpochSecond(GPS_EPOCH + full * WEEK_SECONDS + toa), kept)
}

private fun replaceGltt(fs: DeviceFs, data: ByteArray) {
    fs.write(GLTT_NEW, data)
    check(fs.read(GLTT_NEW)?.contentEquals(data) == true) { "The card didn't store the new file correctly" }
    check(fs.delete(GLTT_PATH) && fs.rename(GLTT_NEW, nameOf(GLTT_PATH))) { "Couldn't replace $GLTT_PATH" }
}

/** Writes the almanac into the GPS program, always starting from the original, which stays on the card. */
fun installAlmanac(fs: DeviceFs, satellites: List<YumaSatellite>) {
    val kept = fs.read(GLTT_BACKUP)
    val original = kept ?: fs.read(GLTT_PATH) ?: error("No $GLTT_PATH on this TomTom")
    val patched = patchGlttAlmanac(original, satellites)
    if (kept == null) {
        fs.write(GLTT_BACKUP, original)
        check(fs.read(GLTT_BACKUP)?.contentEquals(original) == true) { "The card didn't store the backup correctly" }
    }
    replaceGltt(fs, patched)
}

fun removeAlmanac(fs: DeviceFs) {
    val original = fs.read(GLTT_BACKUP) ?: error("The original GPS program isn't on this TomTom")
    check(md5(original) == GLTT_ORIGINAL_MD5) { "$GLTT_BACKUP isn't the original GPS program" }
    replaceGltt(fs, original)
    fs.delete(GLTT_BACKUP)
}

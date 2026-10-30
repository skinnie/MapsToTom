package app.mapstotom.core

import java.security.MessageDigest
import java.time.Instant

// ------------------------------------------------------------------ chip detection

enum class Chip(val label: String, val quickFixProvider: String?, val dateFix: String) {
    GL1("Global Locate GL1 (Hammerhead)", "globalLocate", "TTconv"),
    GL2("Broadcom BCM4750 / GL2 (Barracuda)", "globalLocate", "glgps 9.541 driver"),
    SIRF("SiRFstarIII", "SIRFStarIII", "SiRF firmware 3.2.5 / 3.6.0"),
    AR1520("Atheros AR1520", null, "TTconv"),
    UNKNOWN("Unknown", null, "none known");
}

class ChipInfo(val chip: Chip, val firmware: String, val reason: String)

/** Work out the GPS chip from the files on the TomTom.
 *  The GPS firmware string in ttgo.bif is the best clue:
 *  "GSW3.x" = SiRF, "185.x" (Global Locate GLL) = GL1, "2.x" (Broadcom GLL) = GL2. */
fun detectChip(fs: DeviceFs, bif: Map<String, String> = readBif(fs)): ChipInfo {
    val fw = bif["GPSFirmwareVersion"].orEmpty()
    fs.read("gl/gpstype.txt")?.toString(Charsets.US_ASCII)?.trim()?.toIntOrNull()?.let { code ->
        val chip = when (code) { 128, 129 -> Chip.GL1; 130 -> Chip.GL2; 65 -> Chip.AR1520; else -> Chip.SIRF }
        return ChipInfo(chip, fw, "chip code $code reported by the TomTom")
    }
    if (fw.startsWith("GSW", ignoreCase = true)) return ChipInfo(Chip.SIRF, fw, "GPS firmware $fw")
    val major = fw.substringBefore('.').trim().toIntOrNull()
    if (major != null && major >= 100) return ChipInfo(Chip.GL1, fw, "GPS firmware $fw (Global Locate)")
    if (major != null) return ChipInfo(Chip.GL2, fw, "GPS firmware $fw (Broadcom)")
    if (bif["DeviceName"].orEmpty().contains("Rider", true) && fs.exists("fixdate/rc.athgps") && fs.exists("fixdate/Orion.ini"))
        return ChipInfo(Chip.AR1520, fw, "Rider with AR1520 files")
    if (fs.exists("ephem/packedephemeris.ee") && !fs.exists("ephem/lto.dat"))
        return ChipInfo(Chip.SIRF, fw, "SiRF QuickGPSFix file on the device")
    if (fs.exists("ephem/lto.dat")) return ChipInfo(Chip.GL1, fw, "Global Locate QuickGPSFix file on the device (GL1 or GL2)")
    return ChipInfo(Chip.UNKNOWN, fw, "no GPS firmware version in ttgo.bif")
}

fun md5(data: ByteArray): String = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

// ------------------------------------------------------------------ QuickGPSFix

class QuickFix(val files: Map<String, ByteArray>, val expiry: Instant?)

fun expiryOf(eeMeta: ByteArray?): Instant? =
    eeMeta?.toString(Charsets.US_ASCII)?.let { Regex("""Expiry=(\d+)""").find(it) }?.groupValues?.get(1)?.toLongOrNull()?.let(Instant::ofEpochSecond)

/** lto.dat: magic ff ca de 00, MD5 at 0x0c over the data from 0x1c. */
fun ltoValid(lto: ByteArray): Boolean =
    lto.size > 0x1c && lto[0] == 0xff.toByte() && lto[1] == 0xca.toByte() && lto[2] == 0xde.toByte() &&
        MessageDigest.getInstance("MD5").digest(lto.copyOfRange(0x1c, lto.size)).contentEquals(lto.copyOfRange(0x0c, 0x1c))

fun quickFixOnDevice(fs: DeviceFs): QuickFix {
    val files = listOf("lto.dat", "packedephemeris.ee", "ee_meta.txt").mapNotNull { n -> fs.read("ephem/$n")?.let { n to it } }.toMap()
    return QuickFix(files, expiryOf(files["ee_meta.txt"]))
}

/** Download QuickGPSFix from TomTom's own server (the same address TomTom HOME and TTActivator use). */
fun downloadQuickFix(chip: Chip, net: Net): QuickFix {
    val provider = chip.quickFixProvider ?: error("No QuickGPSFix exists for ${chip.label}")
    val url = "https://home.tomtom.com/download/Ephemeris.cab?type=ephemeris&eeProvider=$provider&devicecode=1"
    val r = net.get(url, mapOf("User-Agent" to USER_AGENT), timeoutMs = 60_000)
    if (r.code != 200) error("TomTom server answered HTTP ${r.code}")
    val files = Cab.extract(r.body).mapKeys { nameOf(it.key) }
    files["lto.dat"]?.let { require(ltoValid(it)) { "downloaded lto.dat failed its checksum" } }
    require(files.containsKey("lto.dat") || files.containsKey("packedephemeris.ee")) { "unexpected download: ${files.keys}" }
    return QuickFix(files, expiryOf(files["ee_meta.txt"]))
}

/** Put QuickGPSFix on the device. On a GL1 whose GPS program has the week bug (see [ltoWeekShift])
 *  lto.dat's dates are first moved to that program's calendar. Returns a note when that happened. */
fun installQuickFix(fs: DeviceFs, q: QuickFix, chip: Chip): String? {
    var files = q.files
    var note: String? = null
    val lto = files["lto.dat"]
    val shift = if (lto != null && chip == Chip.GL1) ltoWeekShift(lto, gl1EngineVersion(fs)) else 0
    if (lto != null && shift != 0) {
        files = files + ("lto.dat" to shiftLto(lto, shift))
        note = "Adjusted for the 2019 week bug in this TomTom's GPS program, which would otherwise ignore it."
    }
    files.forEach { (name, data) -> fs.write("ephem/$name", data) }
    return note
}

fun removeQuickFix(fs: DeviceFs) {
    listOf("lto.dat", "packedephemeris.ee", "ee_meta.txt").forEach { if (fs.exists("ephem/$it")) fs.delete("ephem/$it") }
}

// ------------------------------------------------------------------ QuickGPSFix and the 2019 week rollover (GL1)

/* The GL1 GPS programs (gltt, 2007-08) count GPS weeks from August 1999 (week 1024). Since the
 * April 2019 rollover their clock is 1024 weeks (19.6 years) behind, so they reject TomTom's
 * lto.dat as "not valid for 172026 hours" and search the sky from an 18-year-old almanac.
 * Moving the file's dates back by the same amount makes them use it again. The orbit data itself
 * is not touched (it is encrypted; the GPS program decodes it). Checked in an emulator and on a
 * real ONE v4 on 30 Oct 2026: 9-10 satellites instead of 4, fix in seconds. */

const val GPS_WEEK_SECONDS = 604_800L

/** GPS programs known to have the bug, by the version string inside them. Only tested ones:
 *  185.5001.041 (Mar 2008; NavCore 8.x/9.x and the modded packs) on a real ONE v4;
 *  185.5.039 (May 2007; NavCore 7.x) in the emulator: same rejection ("not valid for 172027 hours"),
 *  accepts the moved file, and its week-handling code is instruction-for-instruction the same. */
val LTO_WEEK_BUG_ENGINES = setOf("Global Locate GLL 185.5001.041", "Global Locate GLL 185.5.039")

/** Version of the GL1 GPS program the TomTom runs: the card's bin/gltt when there (modded systems link
 *  /bin/gltt to it), else an Outsourced.<navcore>/bin/gltt, else the one inside ttsystem. */
fun gl1EngineVersion(fs: DeviceFs): String? {
    fs.read("bin/gltt")?.let { return glgpsVersion(it) }
    fs.list().filter { it.startsWith("Outsourced.") }.forEach { dir -> fs.read("$dir/bin/gltt")?.let { b -> glgpsVersion(b)?.let { return it } } }
    val ttsystem = fs.read("ttsystem") ?: return null
    return runCatching { TtSystem.extract(ttsystem) { it == "bin/gltt" }["bin/gltt"]?.let(::glgpsVersion) }.getOrNull()
}

/** The week a program counting from week 1024 shows for real GPS week [week] (2442 -> 1418).
 *  Holds until the next rollover in November 2038, when it gives -2048 instead. */
fun engineWeekFrom1999(week: Int) = 1024 + Math.floorMod(week - 1024, 1024)

private fun u32(d: ByteArray, o: Int) =
    (d[o].toLong() and 0xff) or ((d[o + 1].toLong() and 0xff) shl 8) or ((d[o + 2].toLong() and 0xff) shl 16) or ((d[o + 3].toLong() and 0xff) shl 24)

/** The date table of a Global Locate lto.dat: (byte offset, epoch in GPS seconds) per block.
 *  After the 0x1c header (magic, size-8, version, MD5) come chunks of (u32 tag, u32 length); chunk
 *  0x00bbcc00 holds kind, mode, n, n id/value pairs, then a count and count x (epoch, block offset). */
fun ltoEpochs(lto: ByteArray): List<Pair<Int, Long>> {
    var o = 0x1c
    while (o + 8 <= lto.size && u32(lto, o) != 0xbbcc00L) {
        val len = u32(lto, o + 4)
        if (len > lto.size) return emptyList()
        o += 8 + len.toInt()
    }
    if (o + 20 > lto.size) return emptyList()
    val c = o + 8
    val p = c + 12 + 8 * u32(lto, c + 8).toInt()
    if (p + 4 > lto.size) return emptyList()
    val count = u32(lto, p).toInt()
    if (count < 0 || p + 4 + 8L * count > lto.size) return emptyList()
    return (0 until count).map { i -> (p + 4 + 8 * i) to u32(lto, p + 4 + 8 * i) }
}

/** lto.dat with every date moved by [weeks], and its MD5 recomputed. */
fun shiftLto(lto: ByteArray, weeks: Int): ByteArray {
    val out = lto.copyOf()
    val epochs = ltoEpochs(out)
    require(epochs.isNotEmpty()) { "Unexpected lto.dat layout" }
    for ((off, t) in epochs) {
        val v = t + weeks * GPS_WEEK_SECONDS
        require(v in 0..0xffffffffL) { "Date out of range" }
        for (b in 0..3) out[off + b] = (v shr (8 * b)).toByte()
    }
    MessageDigest.getInstance("MD5").digest(out.copyOfRange(0x1c, out.size)).copyInto(out, 0x0c)
    return out
}

/** Weeks to move lto.dat for the GPS program [engine]: 0 unless it is a tested one with the week bug. */
fun ltoWeekShift(lto: ByteArray, engine: String?): Int {
    if (engine !in LTO_WEEK_BUG_ENGINES) return 0
    val first = ltoEpochs(lto).firstOrNull()?.second ?: return 0
    val week = (first / GPS_WEEK_SECONDS).toInt()
    return engineWeekFrom1999(week) - week
}

/** How far lto.dat's dates sit from its real expiry, in whole rollovers of 1024 weeks
 *  (0 = TomTom's own file, -1024 = already moved for the week bug), or null when unknown. */
fun ltoWeekOffset(q: QuickFix): Int? {
    val lto = q.files["lto.dat"] ?: return null
    val exp = q.expiry ?: return null
    val first = ltoEpochs(lto).firstOrNull()?.second ?: return null
    val gpsNow = exp.epochSecond - 315_964_800L + 18   // Unix to GPS seconds (18 leap seconds)
    val diffWeeks = (first - gpsNow) / GPS_WEEK_SECONDS.toDouble()
    return (Math.round(diffWeeks / 1024.0) * 1024).toInt()
}

// ------------------------------------------------------------------ date fix: TTconv (GL1, AR1520)

enum class TtconvState { NOT_INSTALLED, INSTALLED, INSTALLED_IMPROVED }

fun ttconvState(fs: DeviceFs): TtconvState {
    val ttn = fs.read("ttn")?.toString(Charsets.ISO_8859_1).orEmpty()
    if (!fs.exists("bin/ttconv") || !fs.exists("fixdate/glttconv.so") || "resetgps" !in ttn) return TtconvState.NOT_INSTALLED
    val rc = fs.read("fixdate/rc.gltt")?.toString(Charsets.ISO_8859_1).orEmpty()
    return if ("ttconv-loop" in rc) TtconvState.INSTALLED_IMPROVED else TtconvState.INSTALLED
}

/** Install TTconv (by treysis, GPL-3.0) with MapsToTom's self-restarting converter.
 *  [files] maps device paths to contents. An existing different `ttn` is kept as ttn.mapstotom-backup. */
fun installTtconv(fs: DeviceFs, files: Map<String, ByteArray>) {
    val oldTtn = fs.read("ttn")
    val newTtn = files["ttn"]
    if (oldTtn != null && newTtn != null && !oldTtn.contentEquals(newTtn) && !fs.exists("ttn.mapstotom-backup"))
        fs.write("ttn.mapstotom-backup", oldTtn)
    files.forEach { (path, data) ->
        val old = fs.read(path)
        if (path.startsWith("fixdate/") && old != null && !old.contentEquals(data) && !fs.exists("$path.orig")) fs.write("$path.orig", old)
        fs.write(path, data)
    }
    fs.mkdirs("gl/log")
}

// ------------------------------------------------------------------ date fix: glgps driver (GL2)

/** NavCore 9.541's glgps: "Broadcom GLL ver. 2.16.201", built 8 Oct 2018, handles the 2019 rollover. */
val GLGPS_FIXED_MD5 = setOf("3880d28834498b2e5ace95e6a0f7ef8e")

class GlgpsState(val present: Boolean, val md5: String?, val version: String?) {
    val fixed get() = md5 in GLGPS_FIXED_MD5
}

fun glgpsVersion(bin: ByteArray): String? =
    Regex("""@\(#\)(Broadcom|Global Locate) GLL ver\. ([0-9.]+)""").find(bin.toString(Charsets.ISO_8859_1))?.let { "${it.groupValues[1]} GLL ${it.groupValues[2]}" }

fun glgpsState(fs: DeviceFs): GlgpsState {
    val b = fs.read("bin/glgps") ?: return GlgpsState(false, null, null)
    return GlgpsState(true, md5(b), glgpsVersion(b))
}

/** Put a known-good glgps (from the user's own NavCore 9.541 files) on the device. The old one is kept. */
fun installGlgps(fs: DeviceFs, bin: ByteArray) {
    require(md5(bin) in GLGPS_FIXED_MD5) { "This glgps is not the NavCore 9.541 version (${glgpsVersion(bin) ?: "unknown"})." }
    fs.read("bin/glgps")?.let { if (!it.contentEquals(bin) && !fs.exists("bin/glgps.orig")) fs.write("bin/glgps.orig", it) }
    fs.write("bin/glgps", bin)
}

// ------------------------------------------------------------------ clock fix (GL2)

const val CLOCK_FIX_PATH = "gl/cfg/glconfig.xml"

fun clockFixInstalled(fs: DeviceFs) =
    fs.read(CLOCK_FIX_PATH)?.toString(Charsets.UTF_8)?.contains("MapsToTom clock fix") == true

/** Build the override config from the device's own ttsystem: the normal start ignores the
 *  TomTom's clock (which old units lose) and takes the time from the satellites first. */
fun buildClockFix(fs: DeviceFs, log: Boolean): ByteArray {
    val ttsystem = fs.read("ttsystem") ?: error("No ttsystem on the device.")
    val orig = TtSystem.extract(ttsystem) { it == "etc/glconfig.xml" }["etc/glconfig.xml"]
        ?: error("This TomTom's system has no glconfig.xml (not a Broadcom/GL2 unit?).")
    var s = orig.toString(Charsets.UTF_8)
    if (log) s = s.replaceFirst("cLogEnabled=\"false\"", "cLogEnabled=\"true\"")
    val normal = Regex("""<job id="normal">\s*<task>\s*<req_pos />""").find(s) ?: error("Unexpected glconfig.xml layout.")
    val fixed = """<job id="normal">
    <task>
      <!-- MapsToTom clock fix: do not trust the TomTom clock; take the time from the satellites -->
      <startup ignore_time="true" />
      <req_pos />"""
    s = s.replaceRange(normal.range, fixed)
    return s.toByteArray(Charsets.UTF_8)
}

fun installClockFix(fs: DeviceFs, log: Boolean = false) = fs.write(CLOCK_FIX_PATH, buildClockFix(fs, log))
fun removeClockFix(fs: DeviceFs) { if (fs.exists(CLOCK_FIX_PATH)) fs.delete(CLOCK_FIX_PATH) }

// ------------------------------------------------------------------ SiRF

/** SiRFstarIII GSW 3.2.4 firmware fails after the 2019 rollover; 3.2.5 / 3.6.0 work. */
fun sirfNeedsUpdate(firmware: String) = Regex("""GSW3\.2\.[0-4]""", RegexOption.IGNORE_CASE).containsMatchIn(firmware)

// ------------------------------------------------------------------ power-off ("suicide")

/** suicide.dat in the root: the TomTom switches fully off instead of going to standby. */
fun powerOffEnabled(fs: DeviceFs) = fs.exists("suicide.dat")
fun setPowerOff(fs: DeviceFs, on: Boolean) {
    if (on) fs.write("suicide.dat", "10".toByteArray()) else if (fs.exists("suicide.dat")) fs.delete("suicide.dat")
}

/**
 * NavCore starts a 10 s "going to switch off" countdown when external power drops, which weak car
 * radio USB ports trigger all the time. An empty noautosuspend.dat in the card root turns that off
 * (checked on a ONE v4 with NavCore 9.541). The TomTom then no longer switches off with the car.
 */
fun stayOnEnabled(fs: DeviceFs) = fs.exists("noautosuspend.dat")
fun setStayOn(fs: DeviceFs, on: Boolean) {
    if (on) fs.write("noautosuspend.dat", ByteArray(0)) else if (fs.exists("noautosuspend.dat")) fs.delete("noautosuspend.dat")
}

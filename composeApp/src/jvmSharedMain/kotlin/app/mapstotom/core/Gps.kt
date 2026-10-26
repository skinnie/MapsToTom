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

fun installQuickFix(fs: DeviceFs, q: QuickFix) {
    q.files.forEach { (name, data) -> fs.write("ephem/$name", data) }
}

fun removeQuickFix(fs: DeviceFs) {
    listOf("lto.dat", "packedephemeris.ee", "ee_meta.txt").forEach { if (fs.exists("ephem/$it")) fs.delete("ephem/$it") }
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

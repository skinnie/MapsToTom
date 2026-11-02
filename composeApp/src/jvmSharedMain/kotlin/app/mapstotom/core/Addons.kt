package app.mapstotom.core

// Add-ons the TomTom can run, each with a switch.
//
// Live services and Bluetooth are words in Config/setconfig.txt. The community-modified system on some ONE/XL
// units reads that file at every full start (not when waking from standby): "model:x40" makes NavCore act as a
// GO x40 and turns its Live features on, "bton"/"btoff" force Bluetooth on or off. Its start-up script finds a
// word anywhere in a line (grep), so a word that is switched off must not appear in any line.

/** Models that give NavCore 9.541 its Live features (x40light3 = the same without PlusTraffic). */
val LIVE_MODELS = setOf("x40", "x40light3")

/** What Live off goes back to when MapsToTom didn't turn Live on itself (the model the car-link setup replaced). */
const val MODEL_WITHOUT_LIVE = "x50"

/** Remembers the model from before Live was turned on. Not one of the names the start-up script reads. */
const val MODEL_BEFORE_LIVE = "mapstotom-model-before-live.txt"

/** Present once the TomTom is set up for MapsToTom's car link (it points the TomTom's Live services at the phone). */
const val CAR_LINK_MARKER = "herculesdiscovery.txt"

/** Config/setconfig.txt as the start-up script sees it. */
class SetConfig(val path: String, val text: String) {
    private val lines = text.split('\n').map { it.removeSuffix("\r") }

    /** True if the script would find [word] (it searches every line for it). */
    fun has(word: String) = lines.any { word in it }

    /** The model the script uses (text after the last ':' of the model line), or null if there is none or several. */
    val model: String? get() = lines.filter { "model" in it && "modelid" !in it }.singleOrNull()
        ?.substringAfterLast(':')?.trim()?.takeIf { it.isNotEmpty() }

    /** A modelid line overrides the model, so the Live switch can't work. */
    val hasModelId get() = has("modelid")
    val live get() = !hasModelId && model in LIVE_MODELS
    val bluetoothOff get() = has("btoff")
}

/** The script reads setconfig.txt from Config/ when that folder exists, else from the card root. */
fun setConfigPath(fs: DeviceFs) = if (fs.isDir("Config")) "Config/setconfig.txt" else "setconfig.txt"

/** Null if this TomTom has no setconfig.txt (a TomTom with its own, unmodified system). */
fun readSetConfig(fs: DeviceFs): SetConfig? {
    val path = setConfigPath(fs)
    val raw = fs.read(path) ?: return null
    return SetConfig(path, raw.toString(Charsets.ISO_8859_1))
}

/** Edit the lines of setconfig text, keeping its line ends (the script writes DOS ones) and every other line. */
private fun editLines(text: String, block: (MutableList<String>) -> Unit): String {
    val eol = if ("\r\n" in text || '\n' !in text) "\r\n" else "\n"
    val lines = if (text.isEmpty()) mutableListOf("") else text.split('\n').map { it.removeSuffix("\r") }.toMutableList()
    if (lines.last().isNotEmpty()) lines += ""
    block(lines)
    return lines.joinToString(eol)
}

/** Index after the last non-blank line, where a new line goes. */
private fun endOfContent(lines: List<String>) = lines.indexOfLast { it.isNotBlank() } + 1

/** Make [word] present and [opposite] absent (e.g. "bton" / "btoff"). The first opposite line is replaced in place. */
internal fun setWord(text: String, word: String, opposite: String) = editLines(text) { lines ->
    val first = lines.indexOfFirst { opposite in it }
    if (first >= 0 && lines.none { word in it }) lines[first] = word
    lines.removeAll { opposite in it }
    if (lines.none { word in it }) lines.add(endOfContent(lines), word)
}

/** Set the model line (one line, "model:<name>"). A file without one gets it as its first line. */
internal fun setModel(text: String, model: String) = editLines(text) { lines ->
    val isModel = { l: String -> "model" in l && "modelid" !in l }
    val first = lines.indexOfFirst(isModel)
    if (first < 0) lines.add(0, "model:$model")
    else {
        lines[first] = "model:$model"
        for (i in lines.indices.reversed()) if (i != first && isModel(lines[i])) lines.removeAt(i)
    }
}

private fun writeSetConfig(fs: DeviceFs, sc: SetConfig, text: String) = fs.write(sc.path, text.toByteArray(Charsets.ISO_8859_1))

/** Live on: model x40 and Bluetooth on (Live reaches the phone by Bluetooth). Live off: the model from before. */
fun setLive(fs: DeviceFs, on: Boolean) {
    val sc = readSetConfig(fs) ?: error("This TomTom has no setconfig.txt.")
    if (sc.hasModelId) error("${sc.path} has a modelid line, which overrides the model. Remove that line first.")
    val before = join(parentOf(sc.path), MODEL_BEFORE_LIVE)
    if (on) {
        sc.model?.takeIf { it !in LIVE_MODELS }?.let { fs.write(before, (it + "\r\n").toByteArray()) }
        writeSetConfig(fs, sc, setWord(setModel(sc.text, "x40"), "bton", "btoff"))
    } else {
        val saved = fs.read(before)?.toString(Charsets.ISO_8859_1)?.trim()
            ?.takeIf { Regex("[A-Za-z0-9]+").matches(it) && it !in LIVE_MODELS }
        writeSetConfig(fs, sc, setModel(sc.text, saved ?: MODEL_WITHOUT_LIVE))
        if (fs.exists(before)) fs.delete(before)
    }
}

fun setBluetooth(fs: DeviceFs, on: Boolean) {
    val sc = readSetConfig(fs) ?: error("This TomTom has no setconfig.txt.")
    writeSetConfig(fs, sc, if (on) setWord(sc.text, "bton", "btoff") else setWord(sc.text, "btoff", "bton"))
}

// ------------------------------------------------------------------ what the TomTom used at its last start

/** From text/config.txt, which the start-up script rewrites at every full start. */
class LastStart(val emulation: String?, val bluetooth: Boolean?, val time: Long?)

fun readLastStart(fs: DeviceFs): LastStart? {
    val path = "text/config.txt"
    val lines = fs.read(path)?.toString(Charsets.ISO_8859_1)?.lines() ?: return null
    fun value(key: String) = lines.firstOrNull { it.startsWith(key) }?.substringAfter(key)?.trim()?.takeIf { it.isNotEmpty() }
    return LastStart(value("Current Emulation:"), value("BT:")?.let { it != "0" }, fs.modified(path))
}

/** True when setconfig.txt changed after the last start, so the TomTom hasn't used the change yet. */
fun waitsForRestart(fs: DeviceFs, sc: SetConfig, last: LastStart?): Boolean {
    val changed = fs.modified(sc.path) ?: return false
    val started = last?.time ?: return false
    return changed > started
}

// ------------------------------------------------------------------ fuel prices on/off

/** A switched-off fuel set keeps its files under this ending, which the TomTom ignores. */
const val FUEL_OFF_SUFFIX = ".mapstotom-off"

/** Switched-off fuel sets in the map folder (paths of their .ov2 files, with the ending). */
fun offFuelSets(fs: DeviceFs, mapDir: String): List<String> =
    fs.list(mapDir).filter { it.lowercase().endsWith(".ov2$FUEL_OFF_SUFFIX") }
        .map { join(mapDir, it) }.filter { p -> fs.read(p)?.let(::isFuelOv2) == true }

/** Switch the fuel sets in [mapDir] off (rename their files) or back on. Returns how many sets changed. */
fun setFuelPrices(fs: DeviceFs, mapDir: String, on: Boolean): Int {
    var n = 0
    if (!on) {
        for (set in findFuelSets(fs, mapDir)) {
            for (p in listOfNotNull(set.ov2, set.fpf)) {
                val target = nameOf(p) + FUEL_OFF_SUFFIX
                if (fs.exists(join(mapDir, target))) fs.delete(join(mapDir, target))
                if (!fs.rename(p, target)) error("Couldn't rename $p")
            }
            n++
        }
    } else {
        val names = fs.list(mapDir)
        for (p in offFuelSets(fs, mapDir)) {
            val ov2 = nameOf(p).dropLast(FUEL_OFF_SUFFIX.length)
            if (fs.exists(join(mapDir, ov2))) continue
            if (!fs.rename(p, ov2)) error("Couldn't rename $p")
            val fpfOff = ov2.dropLast(4) + ".fpf" + FUEL_OFF_SUFFIX
            names.firstOrNull { it.equals(fpfOff, ignoreCase = true) }?.let { fs.rename(join(mapDir, it), it.dropLast(FUEL_OFF_SUFFIX.length)) }
            n++
        }
    }
    return n
}

// ------------------------------------------------------------------ everything the Device tab shows

class FuelAddon(val name: String, val stations: Int, val bytes: Long)

class Addons(
    /** Null: not the modified system, so no Live or Bluetooth switch. */
    val setConfig: SetConfig?,
    val carLinkReady: Boolean,
    val last: LastStart?,
    val waitsForRestart: Boolean,
    val fuelOn: List<FuelAddon>,
    val fuelOff: List<FuelAddon>,
    /** Null: not offered on this TomTom. */
    val gpsLog: Boolean?,
    val swapBytes: Long?,
) {
    val any get() = setConfig != null || fuelOn.isNotEmpty() || fuelOff.isNotEmpty() || gpsLog != null
}

fun readAddons(fs: DeviceFs, mapDir: String?, chip: Chip): Addons {
    val sc = readSetConfig(fs)
    val last = if (sc != null) readLastStart(fs) else null
    fun fuel(ov2: String, fpf: String?): FuelAddon {
        val data = fs.read(ov2) ?: ByteArray(0)
        val name = nameOf(ov2).removeSuffix(FUEL_OFF_SUFFIX).dropLast(4)
        return FuelAddon(name, fuelOv2Count(data), data.size.toLong() + (fpf?.let { fs.size(it) } ?: 0L))
    }
    val on = mapDir?.let { dir -> findFuelSets(fs, dir).map { fuel(it.ov2, it.fpf) } }.orEmpty()
    val off = mapDir?.let { dir ->
        val names = fs.list(dir)
        offFuelSets(fs, dir).map { p ->
            val fpf = nameOf(p).dropLast(FUEL_OFF_SUFFIX.length + 4) + ".fpf" + FUEL_OFF_SUFFIX
            fuel(p, names.firstOrNull { it.equals(fpf, ignoreCase = true) }?.let { join(dir, it) })
        }
    }.orEmpty()
    val logOn = fs.exists("gl/debug-gltt")
    val logOffered = logOn || (chip == Chip.GL1 && ttconvState(fs) == TtconvState.INSTALLED_IMPROVED)
    return Addons(sc, fs.exists(CAR_LINK_MARKER), last, sc != null && waitsForRestart(fs, sc, last), on, off,
        if (logOffered) logOn else null, if (sc != null && fs.exists("swap")) fs.size("swap") else null)
}

package app.mapstotom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mapstotom.core.*
import app.mapstotom.res.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DeviceInfo(val fs: DeviceFs, val bif: Map<String, String>, val mapFolder: String?, val chip: ChipInfo) {
    val model get() = bif["DeviceVersionHW"]?.takeIf { it.isNotBlank() }?.let { hw ->
        val name = bif["DeviceName"].orEmpty()
        if (name.isNotEmpty() && !hw.startsWith(name.removePrefix("TomTom ").trim())) "$name $hw" else "TomTom $hw"
    } ?: bif["DeviceName"] ?: "TomTom"
    val navcore get() = bif["ApplicationVersionVersionNumber"]?.let { v -> if (v.length == 4) "${v[0]}.${v.substring(1)}" else v }
}

class PoiCategory(val name: String, val ov2: String, val bmp: String?, val ogg: String?, val count: Int)

/** All app state and actions, shared by desktop and Android. Work runs off the UI thread. */
class AppState(val platform: Platform, private val scope: CoroutineScope, private val net: Net = RealNet()) {
    // ---------------------------------------------------------- status
    var busy by mutableStateOf<String?>(null); private set
    var status by mutableStateOf("Ready."); private set
    var statusIsError by mutableStateOf(false); private set
    val warnings = mutableStateListOf<String>()

    fun say(msg: String, error: Boolean = false) { status = msg; statusIsError = error }

    private fun work(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Throwable) {
                say("${label.removeSuffix("…")} failed: ${e.message ?: e::class.simpleName}", error = true)
            } finally {
                busy = null
            }
        }
    }

    // ---------------------------------------------------------- device
    var device by mutableStateOf<DeviceInfo?>(null); private set
    var gen by mutableStateOf(0); private set // bumps after every change on the device, so screens re-read it

    fun detect() = work("Looking for the TomTom…") {
        val found = platform.detectDevices().firstOrNull()
        if (found == null) { device = null; say(platform.detectNote() ?: "No TomTom found. Plug it in, or choose its drive.") } else use(found)
    }

    fun eject() { device?.let { d -> work("Ejecting…") {
        if (platform.eject(d.fs)) { device = null; say("Ejected. You can unplug the TomTom.") }
        else say("Couldn't eject: a file may still be in use. Try again.", error = true)
    } } }

    fun chooseDevice() = scope.launch {
        val fs = platform.pickFolder(FolderPurpose.DEVICE) ?: return@launch
        work("Reading the TomTom…") { use(fs) }
    }

    /** Use this drive now (also used by tests). */
    fun connect(fs: DeviceFs) = use(fs)

    private fun use(fs: DeviceFs) {
        if (!isTomTomRoot(fs)) { say("${fs.displayName} doesn't look like a TomTom (no ttgo.bif).", error = true); return }
        val bif = readBif(fs)
        device = DeviceInfo(fs, bif, findMapFolder(fs), detectChip(fs, bif))
        gen++
        say("Connected: ${device!!.model}.")
    }

    fun refresh() { device?.let { d -> work("Reading the TomTom…") { use(d.fs) } } }

    private fun requireDevice(): DeviceInfo = device ?: error("No TomTom selected")

    // ---------------------------------------------------------- send
    val points = mutableStateListOf<Point>()
    var routeName by mutableStateOf("Route")
    var poiCategory by mutableStateOf("Google Maps")
    var firstIsDeparture by mutableStateOf(true)
    var followRoads by mutableStateOf(false)
    var shapeTotal by mutableStateOf(30)
    /** Desktop: add Google Maps links copied to the clipboard. */
    var watchClipboard by mutableStateOf(true)

    fun addText(text: String, onDone: (Boolean) -> Unit = {}) {
        if (text.isBlank()) return
        work("Reading the link…") {
            val r = parseInput(text, net)
            withContext(Dispatchers.Main) {
                points.addAll(r.points)
                warnings.clear(); warnings.addAll(r.warnings)
                if (points.isNotEmpty() && (routeName == "Route" || routeName.isBlank())) routeName = points.last().name.take(40)
            }
            say(if (r.points.isEmpty()) "Nothing found in that text." else "Added ${r.points.size} point(s).", r.points.isEmpty())
            onDone(r.points.isNotEmpty())
        }
    }

    fun move(i: Int, delta: Int) {
        val j = i + delta
        if (i in points.indices && j in points.indices) { val p = points.removeAt(i); points.add(j, p) }
    }

    fun toggleKind(i: Int) {
        val p = points[i]
        points[i] = p.copy(kind = if (p.kind == Point.Kind.STOP) Point.Kind.VIA else Point.Kind.STOP)
    }

    fun rename(i: Int, name: String) { points[i] = points[i].copy(name = name) }

    private fun routePoints(): List<Point> {
        val pts = points.toList()
        if (!followRoads) return pts
        return addShapingPoints(pts, shapeTotal, net)
    }

    fun saveRoute() = work("Saving the route…") {
        val d = requireDevice()
        require(points.size >= 2) { "A route needs at least 2 points" }
        val pts = routePoints()
        val path = writeItn(d.fs, routeName, pts, firstIsDeparture)
        gen++
        val warn = if (pts.size > ITN_MAX) " Note: ${pts.size} points; older TomToms may only load $ITN_MAX." else ""
        say("Saved $path (${pts.size} points). On the TomTom: Itinerary planning → Options → Load itinerary.$warn")
    }

    fun savePois() = work("Saving POIs…") {
        val d = requireDevice()
        require(points.isNotEmpty()) { "No points to save" }
        val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        val (path, added) = writeOv2(d.fs, map, poiCategory, points.filter { it.kind == Point.Kind.STOP })
        gen++
        say("Added $added POI(s) to category \"${poiCategory}\" ($path). On the TomTom: Navigate to → Point of Interest.")
    }

    fun exportItn() = scope.launch {
        if (points.size < 2) { say("A route needs at least 2 points", true); return@launch }
        val data = withContext(Dispatchers.IO) { runCatching { buildItn(routePoints(), firstIsDeparture) } }
        data.onFailure { say("Export failed: ${it.message}", true) }.onSuccess {
            platform.saveFile(safeFilename(routeName) + ".itn", it)?.let { where -> say("Exported to $where.") }
        }
    }

    fun openItn() = scope.launch {
        val (name, data) = platform.pickFile("Open an itinerary (.itn)") ?: return@launch
        val pts = readItn(data)
        points.addAll(pts)
        routeName = name.substringBeforeLast('.')
        say("Loaded ${pts.size} point(s) from $name.")
    }

    // ---------------------------------------------------------- GPS
    fun updateQuickFix() = work("Downloading QuickGPSFix…") {
        val d = requireDevice()
        val q = downloadQuickFix(d.chip.chip.takeIf { it.quickFixProvider != null } ?: error("Unknown GPS chip: choose it first"), net)
        installQuickFix(d.fs, q)
        gen++
        val warn = if (d.chip.chip == Chip.GL2 && !clockFixInstalled(d.fs)) " Tip: also install the clock fix below." else ""
        say("QuickGPSFix updated, valid until ${fmt(q.expiry)}.$warn")
    }

    fun removeQuickFix() = work("Removing QuickGPSFix…") {
        app.mapstotom.core.removeQuickFix(requireDevice().fs); gen++
        say("QuickGPSFix removed. The TomTom will find satellites without help (slower first fix).")
    }

    @OptIn(ExperimentalResourceApi::class)
    fun installTtconv() = work("Installing TTconv…") {
        val d = requireDevice()
        val files = TTCONV_FILES.associateWith { Res.readBytes("files/ttconv/$it") }
        app.mapstotom.core.installTtconv(d.fs, files)
        gen++
        say("TTconv installed. Restart the TomTom (hold the power button) to start it.")
    }

    fun installGlgps() = scope.launch {
        val (name, data) = platform.pickFile("Choose glgps from NavCore 9.541 (bin/glgps)") ?: return@launch
        work("Installing glgps…") {
            app.mapstotom.core.installGlgps(requireDevice().fs, data); gen++
            say("$name installed as bin/glgps (old one kept as bin/glgps.orig). Restart the TomTom.")
        }
    }

    fun setClockFix(on: Boolean, log: Boolean = false) = work(if (on) "Installing the clock fix…" else "Removing the clock fix…") {
        val d = requireDevice()
        if (on) installClockFix(d.fs, log) else removeClockFix(d.fs)
        gen++
        say(if (on) "Clock fix installed ($CLOCK_FIX_PATH). Restart the TomTom." else "Clock fix removed.")
    }

    fun setGlttDebug(on: Boolean) = work("Changing the GPS log…") {
        val fs = requireDevice().fs
        if (on) fs.write("gl/debug-gltt", ByteArray(0)) else if (fs.exists("gl/debug-gltt")) fs.delete("gl/debug-gltt")
        gen++
        say(if (on) "GPS engine log on (gl/log/gltt.txt) from the next start." else "GPS engine log off.")
    }

    // ---------------------------------------------------------- device tools
    fun togglePowerOff(on: Boolean) = work("Saving…") {
        app.mapstotom.core.setPowerOff(requireDevice().fs, on); gen++
        say(if (on) "The TomTom will switch fully off instead of standby." else "The TomTom will use standby again.")
    }

    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set

    fun backup() = scope.launch {
        val d = device ?: return@launch
        val target = platform.pickFolder(FolderPurpose.BACKUP_TARGET) ?: return@launch
        work("Backing up…") {
            val folder = "MapsToTom-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))
            val n = copyTree(d.fs, "", target, folder)
            target.write("$folder/MapsToTom-backup.txt", "Backup of ${d.model} made by MapsToTom on ${LocalDateTime.now()}\n$n files\n".toByteArray())
            say("Backed up $n files to ${target.displayName}/$folder.")
        }
    }

    fun restore() = scope.launch {
        val d = device ?: return@launch
        val src = platform.pickFolder(FolderPurpose.BACKUP_SOURCE) ?: return@launch
        work("Restoring…") {
            require(isTomTomRoot(src) || src.exists("MapsToTom-backup.txt")) { "That folder isn't a TomTom backup" }
            val n = copyTree(src, "", d.fs, "", skip = setOf("MapsToTom-backup.txt"))
            gen++
            say("Restored $n files to the TomTom.")
        }
    }

    private fun copyTree(from: DeviceFs, fromPath: String, to: DeviceFs, toPath: String, skip: Set<String> = emptySet()): Int {
        val files = mutableListOf<String>()
        fun walk(p: String) {
            for (n in from.list(p)) {
                val c = join(p, n)
                if (n in skip || n == "System Volume Information" || n.startsWith(".Trash")) continue
                if (from.isDir(c)) walk(c) else files += c
            }
        }
        walk(fromPath)
        files.forEachIndexed { i, f ->
            progress = i to files.size
            to.write(join(toPath, f.removePrefix(fromPath).trimStart('/')), from.read(f) ?: ByteArray(0))
        }
        progress = null
        return files.size
    }

    // ---------------------------------------------------------- POI library
    var poiSource by mutableStateOf<DeviceFs?>(null); private set
    val libraryCategories = mutableStateListOf<PoiCategory>()

    fun choosePoiSource() = scope.launch {
        val src = platform.pickFolder(FolderPurpose.POI_SOURCE) ?: return@launch
        work("Reading POI files…") {
            val cats = scanPoiFolder(src)
            poiSource = src
            withContext(Dispatchers.Main) { libraryCategories.clear(); libraryCategories.addAll(cats) }
            say("Found ${cats.size} POI categories in ${src.displayName}.")
        }
    }

    fun installPois(selected: List<PoiCategory>) = work("Installing POIs…") {
        val d = requireDevice(); val src = poiSource ?: error("Choose a POI folder first")
        val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        var sounds = 0
        for (c in selected) {
            d.fs.write(join(map, nameOf(c.ov2)), src.read(c.ov2)!!)
            c.bmp?.let { d.fs.write(join(map, c.name + ".bmp"), src.read(it)!!) }
            c.ogg?.let { d.fs.write("sounds/" + nameOf(it), src.read(it)!!); sounds++ }
        }
        gen++
        say("Installed ${selected.size} POI categories into $map" + (if (sounds > 0) " and $sounds warning sound(s) into sounds/." else "."))
    }

    fun deviceCategories(): List<PoiCategory> {
        val d = device ?: return emptyList()
        val map = d.mapFolder ?: return emptyList()
        return scanPoiFolder(d.fs, map)
    }

    fun deletePoiCategory(c: PoiCategory) = work("Deleting…") {
        val fs = requireDevice().fs
        fs.delete(c.ov2); c.bmp?.let { fs.delete(it) }
        gen++
        say("Deleted POI category \"${c.name}\".")
    }

    companion object {
        val TTCONV_FILES = listOf(
            "ttn", "bin/ttconv", "fixdate/glttconv.so", "fixdate/Orion.ini", "fixdate/rc.athgps",
            "fixdate/rc.gltt", "fixdate/resetgps", "fixdate/ttconv.sh",
            "sdkregistry/ttconv.bmp", "sdkregistry/ttconv.cap", "README-ttconv.txt",
        )

        fun fmt(t: Instant?): String = t?.let {
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").format(it.atZone(ZoneId.systemDefault()))
        } ?: "unknown"
    }
}

/** POI categories in a folder: each .ov2 with a matching .bmp icon and .ogg sound (same name, any case). */
fun scanPoiFolder(fs: DeviceFs, dir: String = ""): List<PoiCategory> {
    val names = fs.list(dir)
    fun find(base: String, ext: String) = names.firstOrNull { it.equals("$base.$ext", ignoreCase = true) }?.let { join(dir, it) }
    return names.filter { it.lowercase().endsWith(".ov2") }.map { f ->
        val base = f.dropLast(4)
        val count = runCatching { readOv2(fs.read(join(dir, f)) ?: ByteArray(0)).size }.getOrDefault(0)
        PoiCategory(base, join(dir, f), find(base, "bmp"), find(base, "ogg"), count)
    }.sortedBy { it.name.lowercase() }
}

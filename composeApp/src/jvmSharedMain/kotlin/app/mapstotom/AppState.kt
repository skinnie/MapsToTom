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

class PoiCategory(val name: String, val ov2: String, val bmp: String?, val ogg: String?, val count: Int,
                  val modified: Long? = null, val isProtected: Boolean = false, val editable: Boolean = !isProtected)

/** All app state and actions, shared by desktop and Android. Work runs off the UI thread. */
class AppState(val platform: Platform, private val scope: CoroutineScope, private val net: Net = RealNet()) {
    // ---------------------------------------------------------- status
    var busy by mutableStateOf<String?>(null); private set
    var status by mutableStateOf("Ready."); private set
    var statusIsError by mutableStateOf(false); private set
    val warnings = mutableStateListOf<String>()

    fun say(msg: String, error: Boolean = false) { status = msg; statusIsError = error }

    /** Update the running task's label (only while one runs). */
    private fun busyDetail(text: String) { if (busy != null) busy = text }

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
        if (found == null) { device = null; say(startupNote() ?: platform.detectNote() ?: "No TomTom found. Plug it in, or choose its drive.") } else use(found)
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
    fun connect(fs: DeviceFs) = use(fs, checkGps = false)

    private fun use(fs: DeviceFs, checkGps: Boolean = true) {
        if (!isTomTomRoot(fs)) { say("${fs.displayName} doesn't look like a TomTom (no ttgo.bif).", error = true); return }
        val bif = readBif(fs)
        val d = DeviceInfo(fs, bif, findMapFolder(fs), detectChip(fs, bif))
        device = d
        if (osmCountries.isBlank()) osmCountries = countriesForMap(d.mapFolder).joinToString(", ")
        gen++
        say("Connected: ${d.model}.")
        if (checkGps) checkQuickFix(d)
    }

    // ---------------------------------------------------------- QuickGPSFix reminder
    /** Update QuickGPSFix by itself when it has expired or expires within a day. */
    var autoQuickFix by mutableStateOf(platform.loadSetting("autoQuickFix") != "off")
        private set

    fun changeAutoQuickFix(on: Boolean) { autoQuickFix = on; platform.saveSetting("autoQuickFix", if (on) "on" else "off") }

    /** Expiry of the QuickGPSFix last seen on the TomTom, remembered between runs. */
    val lastQuickFixExpiry: Instant? get() = platform.loadSetting("quickFixExpiry")?.toLongOrNull()?.let(Instant::ofEpochSecond)

    private fun rememberExpiry(exp: Instant?) {
        platform.saveSetting("quickFixExpiry", exp?.epochSecond?.toString())
        platform.scheduleQuickFixReminder(exp?.minus(java.time.Duration.ofDays(1)))
    }

    private fun checkQuickFix(d: DeviceInfo) {
        if (d.chip.chip.quickFixProvider == null) return
        val onDevice = runCatching { quickFixOnDevice(d.fs) }.getOrNull()
        val exp = onDevice?.expiry
        rememberExpiry(exp)
        val soon = exp == null || exp.isBefore(Instant.now().plus(java.time.Duration.ofDays(1)))
        // TomTom's file as it is (from another tool) is ignored by a GPS program with the week bug.
        val ignored = !soon && d.chip.chip == Chip.GL1 && onDevice != null && ltoWeekOffset(onDevice) == 0 &&
            runCatching { gl1EngineVersion(d.fs) }.getOrNull() in LTO_WEEK_BUG_ENGINES
        if (!soon && !ignored) return
        if (!autoQuickFix) {
            say("Connected: ${d.model}. QuickGPSFix " + (if (ignored) "is not used by this GPS" else if (exp == null) "is missing" else "expires ${fmt(exp)}") +
                ": update it on the GPS tab."); return
        }
        busyDetail("Updating QuickGPSFix…")
        runCatching {
            val q = downloadQuickFix(d.chip.chip, net)
            val note = installQuickFix(d.fs, q, d.chip.chip); rememberExpiry(q.expiry); gen++
            say("Connected: ${d.model}. QuickGPSFix updated by itself, valid until ${fmt(q.expiry)}." + (note?.let { " $it" } ?: ""))
        }.onFailure { say("Connected: ${d.model}. QuickGPSFix needs updating but the download failed: ${it.message}", true) }
    }

    /** Shown at start when the remembered QuickGPSFix has run out. */
    fun startupNote(): String? = lastQuickFixExpiry?.takeIf { it.isBefore(Instant.now()) }?.let {
        "QuickGPSFix on your TomTom expired on ${fmt(it)}. Connect the TomTom to update it."
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

    /** Open an itinerary (.itn) or a GPX route/track (thinned to what the TomTom loads). */
    fun openRouteFile() = scope.launch {
        val (name, data) = platform.pickFile("Open a route (.itn or .gpx)") ?: return@launch
        val r = withContext(Dispatchers.IO) {
            runCatching {
                if (name.endsWith(".gpx", true) || data.take(400).toByteArray().toString(Charsets.UTF_8).contains("<gpx")) {
                    val g = readGpx(data)
                    Triple(gpxToPoints(g), g.name, "${g.line.size} track points → ${minOf(g.line.size, ITN_MAX)} for the TomTom" +
                        if (g.line.size >= 2 && g.waypoints.isNotEmpty()) "; ${g.waypoints.size} waypoints left out" else "")
                } else Triple(readItn(data), null, null)
            }
        }
        r.onFailure { say("Couldn't read $name: ${it.message}", true) }.onSuccess { (pts, gName, note) ->
            if (pts.isEmpty()) { say("No points in $name.", true); return@onSuccess }
            points.addAll(pts)
            routeName = (gName ?: name.substringBeforeLast('.')).take(40)
            say("Loaded ${pts.size} point(s) from $name" + (note?.let { " ($it)" } ?: "") + ".")
        }
    }

    // ---------------------------------------------------------- GPS
    fun updateQuickFix() = work("Downloading QuickGPSFix…") {
        val d = requireDevice()
        val q = downloadQuickFix(d.chip.chip.takeIf { it.quickFixProvider != null } ?: error("Unknown GPS chip: choose it first"), net)
        val note = installQuickFix(d.fs, q, d.chip.chip)
        rememberExpiry(q.expiry)
        gen++
        val warn = if (d.chip.chip == Chip.GL2 && !clockFixInstalled(d.fs)) " Tip: also install the clock fix below." else ""
        say("QuickGPSFix updated, valid until ${fmt(q.expiry)}." + (note?.let { " $it" } ?: "") + warn)
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

    fun toggleStayOn(on: Boolean) = work("Saving…") {
        app.mapstotom.core.setStayOn(requireDevice().fs, on); gen++
        say(if (on) "The TomTom will stay on when car power dips. Switch it off with the power button." else "The TomTom will switch off again when external power is lost.")
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

    // ---------------------------------------------------------- POI editing
    fun deletePoi(c: PoiCategory, e: Ov2Entry) = work("Deleting…") {
        val left = editPoiFile(requireDevice().fs, c.ov2) { list -> list.filterNot { sameEntry(it, e) } }
        gen++
        say("Deleted \"${e.name}\" from ${c.name} ($left left).")
    }

    fun renamePoi(c: PoiCategory, e: Ov2Entry, name: String) = work("Renaming…") {
        val n = safeName(name)
        editPoiFile(requireDevice().fs, c.ov2) { list -> list.map { if (sameEntry(it, e)) Ov2Entry(n, it.lat, it.lon) else it } }
        gen++
        say("Renamed to \"$n\".")
    }

    fun poiEntries(c: PoiCategory): List<Ov2Entry>? = device?.fs?.read(c.ov2)?.let(::parseOv2)

    // ---------------------------------------------------------- OpenStreetMap POIs
    var osmCountries by mutableStateOf("")

    fun installOsm(types: List<OsmPoiType>) = work("Downloading from OpenStreetMap…") {
        val d = requireDevice(); val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        val cc = osmCountries.split(',', ' ', ';').filter { it.isNotBlank() }
        val done = mutableListOf<String>()
        types.forEachIndexed { i, t ->
            progress = i to types.size
            busyDetail("${t.label} (${i + 1} of ${types.size})…")
            val pts = fetchOsmPois(t, cc, net)
            val sound = if (t.warns) Res.readBytes("files/poi/warning.ogg") else null
            writePoiCategory(d.fs, map, t.category, pts, categoryIconBmp(t.rgb, t.glyph), sound)
            done += "${t.label} ${pts.size}"
        }
        progress = null
        gen++
        say("Installed from OpenStreetMap: ${done.joinToString(", ")}. On the TomTom: Navigate to → Point of Interest.")
    }

    // ---------------------------------------------------------- Google saved places
    val takeoutLists = mutableStateListOf<SavedList>()

    fun openTakeout() = scope.launch {
        val (name, data) = platform.pickFile("Open your Google Takeout (.zip, or a .csv / .json from it)") ?: return@launch
        work("Reading $name…") {
            val lists = readTakeout(name, data)
            withContext(Dispatchers.Main) { takeoutLists.clear(); takeoutLists.addAll(lists) }
            say(if (lists.isEmpty()) "No saved places found in $name." else "Found ${lists.size} lists with ${lists.sumOf { it.rows.size }} places.", lists.isEmpty())
        }
    }

    fun installTakeout(selected: List<SavedList>) = work("Looking up saved places…") {
        val d = requireDevice(); val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        val total = selected.sumOf { it.rows.size }
        var base = 0
        val notes = mutableListOf<String>()
        for (l in selected) {
            busyDetail("${l.name}…")
            val (pts, missed) = resolveSaved(l, net) { i, _ -> progress = (base + i) to total }
            base += l.rows.size
            if (pts.isNotEmpty()) writePoiCategory(d.fs, map, "Google " + l.name, pts, categoryIconBmp(0x1A73E8, 'G'))
            notes += "${l.name} ${pts.size}" + if (missed.isNotEmpty()) " (${missed.size} not found)" else ""
        }
        progress = null
        gen++
        say("Installed Google lists: ${notes.joinToString(", ")}.")
    }

    // ---------------------------------------------------------- Garmin POI files (.gpi)
    var gpiFile by mutableStateOf<GpiFile?>(null)
        private set
    /** Keep only places inside the countries typed for OpenStreetMap (the map's area). */
    var gpiAreaOnly by mutableStateOf(true)

    fun openGpi() = scope.launch {
        val (name, data) = platform.pickFile("Open a Garmin POI file (.gpi)") ?: return@launch
        work("Reading $name…") {
            val g = readGpi(data)
            withContext(Dispatchers.Main) { gpiFile = g }
            say("${g.title}: ${g.categories.size} categories, ${g.categories.sumOf { it.points.size }} places.")
        }
    }

    /** The area filter in use, or null for the whole file. */
    fun gpiFilter(): ((Point) -> Boolean)? = if (gpiAreaOnly) areaFilter(osmCountries.split(',', ' ', ';').filter { it.isNotBlank() }) else null

    /** "iOverlander Wild Camping": the file's first word keeps categories from different files apart. */
    fun gpiCategoryName(c: GpiCategory) = listOfNotNull(gpiFile?.title?.substringBefore(' ')?.takeIf { it.isNotBlank() }, c.name).joinToString(" ")

    fun installGpi(selected: List<GpiCategory>) = work("Installing…") {
        val d = requireDevice(); val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        val keep = gpiFilter()
        val done = mutableListOf<String>()
        selected.forEachIndexed { i, c ->
            progress = i to selected.size
            val pts = if (keep == null) c.points else c.points.filter(keep)
            if (pts.isEmpty()) return@forEachIndexed
            writePoiCategory(d.fs, map, gpiCategoryName(c), pts, gpiIconBmp(c.icon) ?: categoryIconBmp(0x6B4E2E, 'P'))
            done += "${c.name} ${pts.size}"
        }
        progress = null
        gen++
        say(if (done.isEmpty()) "Nothing to install: no places in the chosen countries." else "Installed: ${done.joinToString(", ")}.", done.isEmpty())
    }

    fun deviceCategories(): List<PoiCategory> {
        val d = device ?: return emptyList()
        val map = d.mapFolder ?: return emptyList()
        return scanPoiFolder(d.fs, map)
    }

    fun deletePoiCategory(c: PoiCategory) = work("Deleting…") {
        val fs = requireDevice().fs
        fs.delete(c.ov2); c.bmp?.let { fs.delete(it) }; c.ogg?.let { fs.delete(it) }
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
        val signed = names.any { it.equals("$f.dct", ignoreCase = true) }
        val c = runCatching { readOv2Contents(fs.read(join(dir, f)) ?: ByteArray(0)) }.getOrNull()
        val entries = c?.let { if (signed) null else parseOv2(fs.read(join(dir, f)) ?: ByteArray(0)) }
        PoiCategory(base, join(dir, f), find(base, "bmp"), find(base, "ogg"), entries?.size ?: 0,
            fs.modified(join(dir, f)), isProtected = signed, editable = entries != null && c.complete && !signed)
    }.sortedBy { it.name.lowercase() }
}

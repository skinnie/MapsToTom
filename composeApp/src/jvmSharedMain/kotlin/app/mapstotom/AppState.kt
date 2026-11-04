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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DeviceInfo(val fs: DeviceFs, val bif: Map<String, String>, val mapFolder: String?, val chip: ChipInfo) {
    val model get() = bif["DeviceVersionHW"]?.takeIf { it.isNotBlank() }?.let { hw ->
        val name = bif["DeviceName"].orEmpty()
        if (name.isNotEmpty() && !hw.startsWith(name.removePrefix("TomTom ").trim())) "$name $hw" else "TomTom $hw"
    } ?: bif["DeviceName"] ?: "TomTom"
    /** The TomTom's menu language ("pt", "en"…), for naming POI categories. */
    val language get() = app.mapstotom.core.tomtomLanguage(bif)
    val navcore get() = bif["ApplicationVersionVersionNumber"]?.let { v -> if (v.length == 4) "${v[0]}.${v.substring(1)}" else v }
}

/** [fpf] is set for a fuel price set: its price file, deleted together with the .ov2. */
class PoiCategory(val name: String, val ov2: String, val bmp: String?, val ogg: String?, val count: Int,
                  val modified: Long? = null, val isProtected: Boolean = false, val editable: Boolean = !isProtected,
                  val fpf: String? = null)

/** Fuel prices for the connected TomTom: its map's countries, those with a price source, and the set already there. */
class FuelInfo(val countries: List<String>, val sources: List<FuelSource>, val sets: List<FuelSet>)

/** Where on screen a job was started, so its steps, result and busy button show there. */
object Job {
    const val DETECT = "detect"; const val SEND_INPUT = "send.input"; const val ROUTE = "send.route"; const val SEND_POIS = "send.pois"
    const val GPS = "gps"; const val FUEL = "fuel"; const val OSM = "osm"; const val GOOGLE = "google"; const val GPI = "gpi"
    const val FOLDER = "folder"; const val ON_DEVICE = "ondevice"; const val DEVICE = "device"; const val BACKUP = "backup"
}

/** One step of a long job, shown as a checklist where it was started. */
class Step(val label: String, userStep: Boolean = false) {
    /** AFTER: for the user to do once the job is done (restart the TomTom). */
    enum class State { TODO, RUNNING, DONE, FAILED, AFTER }
    var state by mutableStateOf(if (userStep) State.AFTER else State.TODO)
    var detail by mutableStateOf<String?>(null)
}

/** The result of the last job, shown under the button that started it. */
class Note(val key: String?, val text: String, val error: Boolean, val time: LocalTime)

/** All app state and actions, shared by desktop and Android. Work runs off the UI thread. */
class AppState(val platform: Platform, private val scope: CoroutineScope, private val net: Net = RealNet()) {
    // ---------------------------------------------------------- status
    var busy by mutableStateOf<String?>(null); private set
    /** The [Job] key of the running job. */
    var busyKey by mutableStateOf<String?>(null); private set
    var status by mutableStateOf("Ready."); private set
    var statusIsError by mutableStateOf(false); private set
    /** When [status] was said; null for "Ready.". */
    var statusTime by mutableStateOf<LocalTime?>(null); private set
    var note by mutableStateOf<Note?>(null); private set
    var steps by mutableStateOf<List<Step>>(emptyList()); private set
    var stepsKey by mutableStateOf<String?>(null); private set
    /** A change waits for a full restart of the TomTom (hold the power button). */
    var restartPending by mutableStateOf(false); private set
    /** The TomTom was ejected and can be unplugged. */
    var ejected by mutableStateOf(false); private set
    val warnings = mutableStateListOf<String>()

    /** [key] puts the message under that section too; inside a job it is the job's own. */
    fun say(msg: String, error: Boolean = false, key: String? = busyKey) {
        status = msg; statusIsError = error; statusTime = LocalTime.now()
        note = Note(key, msg, error, statusTime!!)
    }

    fun dismissNote(key: String) {
        if (note?.key == key) note = null
        if (stepsKey == key && busyKey != key) { steps = emptyList(); stepsKey = null }
    }

    fun restartDone() { restartPending = false }

    /** Update the running task's label (only while one runs). */
    private fun busyDetail(text: String) { if (busy != null) busy = text }

    /** Show these steps for the running job; [restart] adds "restart the TomTom" for the user at the end. */
    private fun plan(labels: List<String>, restart: Boolean = false) {
        steps = labels.map { Step(it) } + if (restart) listOf(Step(RESTART_STEP, userStep = true)) else emptyList()
        stepsKey = busyKey
    }

    /** Step [i] is running now (the ones before it are done). */
    private fun step(i: Int, detail: String? = null) {
        steps.forEachIndexed { j, s ->
            if (s.state == Step.State.AFTER) return@forEachIndexed
            if (j < i && s.state != Step.State.FAILED) s.state = Step.State.DONE
            if (j == i) { s.state = Step.State.RUNNING; s.detail = detail }
        }
        busyDetail(steps.getOrNull(i)?.let { s -> s.label + (detail?.let { ": $it" } ?: "") + "…" } ?: return)
    }

    private fun stepDetail(i: Int, detail: String) { steps.getOrNull(i)?.detail = detail }

    private fun needsRestart() { restartPending = true }

    private fun work(label: String, key: String? = null, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label; busyKey = key
        if (stepsKey != key) { steps = emptyList(); stepsKey = null }
        if (note?.key == key) note = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                steps.forEach { if (it.state == Step.State.RUNNING || it.state == Step.State.TODO) it.state = Step.State.DONE }
            } catch (e: Throwable) {
                steps.forEach { if (it.state == Step.State.RUNNING) it.state = Step.State.FAILED }
                say("${label.removeSuffix("…")} failed: ${e.message ?: e::class.simpleName}", error = true)
            } finally {
                busy = null; busyKey = null; progress = null
            }
        }
    }

    // ---------------------------------------------------------- device
    var device by mutableStateOf<DeviceInfo?>(null); private set
    var gen by mutableStateOf(0); private set // bumps after every change on the device, so screens re-read it

    fun detect() = work("Looking for the TomTom…", Job.DETECT) {
        val found = platform.detectDevices().firstOrNull()
        if (found == null) {
            device = null
            if (!ejected) say(startupNote() ?: platform.detectNote() ?: "No TomTom found. Plug it in, or choose its drive.")
        } else use(found)
    }

    fun eject() { device?.let { d -> work("Ejecting…", Job.DETECT) {
        if (platform.eject(d.fs)) { device = null; ejected = true; say("Ejected. You can unplug the TomTom.") }
        else say("Couldn't eject: a file may still be in use. Try again.", error = true)
    } } }

    fun chooseDevice() = scope.launch {
        val fs = platform.pickFolder(FolderPurpose.DEVICE) ?: return@launch
        work("Reading the TomTom…", Job.DETECT) { use(fs) }
    }

    /** Use this drive now (also used by tests). */
    fun connect(fs: DeviceFs) = use(fs, checkGps = false)

    private fun use(fs: DeviceFs, checkGps: Boolean = true) {
        if (!isTomTomRoot(fs)) { say("${fs.displayName} doesn't look like a TomTom (no ttgo.bif).", error = true); return }
        val bif = readBif(fs)
        val d = DeviceInfo(fs, bif, findMapFolder(fs), detectChip(fs, bif))
        device = d
        ejected = false
        fuelTypeChecked = false
        if (osmCountries.isBlank()) osmCountries = countriesForMap(d.mapFolder).joinToString(", ")
        // The TomTom doesn't translate added POI files, so ours follow its language: rename them if it changed.
        val renamed = d.mapFolder?.let { m -> runCatching { renameTranslatedCategories(fs, m, d.language) }.getOrNull() }.orEmpty()
        val unhooked = runCatching { removeBootRename(fs) }.getOrNull() == true
        gen++
        say("Connected: ${d.model}." + if (renamed.isEmpty()) "" else
            " Renamed ${renamed.size} OpenStreetMap ${if (renamed.size == 1) "category" else "categories"} to the TomTom's language (${bif["UserLanguage"]}).")
        if (renamed.isNotEmpty()) needsRestart()
        if (unhooked) { needsRestart(); say((note?.text?.let { "$it " } ?: "") + "Removed the old start-up rename from the TomTom (it could stop the TomTom from starting).") }
        if (checkGps) checkQuickFix(d)
    }

    // ---------------------------------------------------------- QuickGPSFix reminder
    /** Update QuickGPSFix by itself when it has expired or expires within a day. */
    var autoQuickFix by mutableStateOf(platform.loadSetting("autoQuickFix") != "off")
        private set

    fun changeAutoQuickFix(on: Boolean) { autoQuickFix = on; platform.saveSetting("autoQuickFix", if (on) "on" else "off") }

    // ---------------------------------------------------------- appearance
    var themeMode by mutableStateOf(platform.loadSetting("theme")?.let { v -> app.mapstotom.ui.ThemeMode.entries.find { it.name == v } }
        ?: app.mapstotom.ui.ThemeMode.AUTO)
        private set
    var accent by mutableStateOf(platform.loadSetting("accent")?.let { v -> app.mapstotom.ui.Accent.entries.find { it.name == v } }
        ?: app.mapstotom.ui.Accent.BLUE)
        private set

    fun changeThemeMode(m: app.mapstotom.ui.ThemeMode) { themeMode = m; platform.saveSetting("theme", m.name) }
    fun changeAccent(a: app.mapstotom.ui.Accent) { accent = a; platform.saveSetting("accent", a.name) }

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

    fun refresh() { device?.let { d -> work("Reading the TomTom…", Job.DETECT) { use(d.fs) } } }

    private fun requireDevice(): DeviceInfo = device ?: error("No TomTom selected")

    // ---------------------------------------------------------- send
    val points = mutableStateListOf<Point>()
    var routeName by mutableStateOf("Route")
    var poiCategory by mutableStateOf("Google Maps")
    var firstIsDeparture by mutableStateOf(true)
    var followRoads by mutableStateOf(false)
    var shapeTotal by mutableStateOf(30)
    /** Roads to stay off when following the roads (from a ViaMichelin link's route options). */
    var avoid by mutableStateOf(Avoid())
    /** Desktop: add Google Maps and ViaMichelin links copied to the clipboard. */
    var watchClipboard by mutableStateOf(true)

    fun addText(text: String, onDone: (Boolean) -> Unit = {}) {
        if (text.isBlank()) return
        work("Reading the link…", Job.SEND_INPUT) {
            val r = parseInput(text, net)
            val linkAvoid = r.avoid?.takeIf { it.any && r.points.isNotEmpty() }
            withContext(Dispatchers.Main) {
                points.addAll(r.points)
                warnings.clear(); warnings.addAll(r.warnings)
                if (points.isNotEmpty() && (routeName == "Route" || routeName.isBlank())) routeName = points.last().name.take(40)
                // the TomTom only stays off those roads if the route is pinned to them
                if (linkAvoid != null) { avoid = linkAvoid; followRoads = true }
            }
            val msg = when {
                r.points.isEmpty() -> "Nothing found in that text."
                linkAvoid != null -> "Added ${r.points.size} point(s). The route avoids ${linkAvoid.label()}, so " +
                    "\"Follow the same roads\" is on: it pins the TomTom to a route without them."
                else -> "Added ${r.points.size} point(s)."
            }
            say(msg, r.points.isEmpty())
            onDone(r.points.isNotEmpty())
        }
    }

    fun clearPoints() { points.clear(); warnings.clear(); avoid = Avoid() }

    fun move(i: Int, delta: Int) {
        val j = i + delta
        if (i in points.indices && j in points.indices) { val p = points.removeAt(i); points.add(j, p) }
    }

    fun toggleKind(i: Int) {
        val p = points[i]
        points[i] = p.copy(kind = if (p.kind == Point.Kind.STOP) Point.Kind.VIA else Point.Kind.STOP)
    }

    fun rename(i: Int, name: String) { points[i] = points[i].copy(name = name) }

    private fun routePoints(pts: List<Point> = points.toList()): List<Point> {
        if (!followRoads) return pts
        return addShapingPoints(pts, shapeTotal, net, avoid)
    }

    fun saveRoute() {
        val snapshot = points.toList() // the list can't change while saving, but take it now anyway
        work("Saving the route…", Job.ROUTE) {
            val d = requireDevice()
            require(snapshot.size >= 2) { "A route needs at least 2 points" }
            if (followRoads) { plan(listOf("Find the roads", "Write the route to the TomTom")); step(0, "OSRM route planner") }
            val pts = routePoints(snapshot)
            if (followRoads) step(1)
            val path = writeItn(d.fs, routeName, pts, firstIsDeparture)
            gen++
            val warn = if (pts.size > ITN_MAX) " Note: ${pts.size} points; older TomToms may only load $ITN_MAX." else ""
            say("Saved $path (${pts.size} points). On the TomTom: Itinerary planning → Options → Load itinerary.$warn")
        }
    }

    fun savePois() = work("Saving POIs…", Job.SEND_POIS) {
        val d = requireDevice()
        require(points.isNotEmpty()) { "No points to save" }
        val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        val (path, added) = writeOv2(d.fs, map, poiCategory, points.filter { it.kind == Point.Kind.STOP })
        gen++
        say("Added $added POI(s) to category \"${poiCategory}\" ($path). On the TomTom: Navigate to → Point of Interest.")
    }

    fun exportItn() = scope.launch {
        if (points.size < 2) { say("A route needs at least 2 points", true, Job.ROUTE); return@launch }
        val data = withContext(Dispatchers.IO) { runCatching { buildItn(routePoints(), firstIsDeparture) } }
        data.onFailure { say("Export failed: ${it.message}", true, Job.ROUTE) }.onSuccess {
            platform.saveFile(safeFilename(routeName) + ".itn", it)?.let { where -> say("Exported to $where.", key = Job.ROUTE) }
        }
    }

    /** Open an itinerary (.itn) or a GPX route/track (thinned to what the TomTom loads). */
    fun openRouteFile() = scope.launch {
        val (name, data) = platform.pickFile("Open a route (.itn or .gpx)") ?: return@launch
        loadRoute(name, data)
    }

    private suspend fun loadRoute(name: String, data: ByteArray) {
        val say = { m: String, e: Boolean -> say(m, e, Job.SEND_INPUT) }
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
            say("Loaded ${pts.size} point(s) from $name" + (note?.let { " ($it)" } ?: "") + ".", false)
        }
    }

    /** Files dropped on the window (desktop): each goes where its kind belongs. */
    fun openDropped(files: List<java.io.File>) = scope.launch {
        if (busy != null) { say("Wait for the current job to finish, then drop the file again.", true, null); return@launch }
        for (f in files) {
            val n = f.name.lowercase()
            when {
                f.isDirectory -> { usePoiSource(LocalFs(f)); return@launch }
                n.endsWith(".ov2") -> { f.parentFile?.let { usePoiSource(LocalFs(it)) }; return@launch }
                n.endsWith(".itn") || n.endsWith(".gpx") -> loadRoute(f.name, withContext(Dispatchers.IO) { f.readBytes() })
                n.endsWith(".gpi") -> { loadGpi(f.name, withContext(Dispatchers.IO) { f.readBytes() }); return@launch }
                n.endsWith(".zip") || n.endsWith(".csv") || n.endsWith(".json") -> { loadTakeout(f.name, withContext(Dispatchers.IO) { f.readBytes() }); return@launch }
                else -> say("${f.name}: MapsToTom opens .itn and .gpx routes, .gpi files, Google Takeout .zip files and folders of .ov2 files.", true, null)
            }
        }
    }

    // ---------------------------------------------------------- GPS
    fun updateQuickFix() = work("Downloading QuickGPSFix…", Job.GPS) {
        val d = requireDevice()
        val q = downloadQuickFix(d.chip.chip.takeIf { it.quickFixProvider != null } ?: error("Unknown GPS chip: choose it first"), net)
        val note = installQuickFix(d.fs, q, d.chip.chip)
        rememberExpiry(q.expiry)
        gen++
        val warn = if (d.chip.chip == Chip.GL2 && !clockFixInstalled(d.fs)) " Tip: also install the clock fix below." else ""
        say("QuickGPSFix updated, valid until ${fmt(q.expiry)}." + (note?.let { " $it" } ?: "") + warn)
    }

    fun removeQuickFix() = work("Removing QuickGPSFix…", Job.GPS) {
        app.mapstotom.core.removeQuickFix(requireDevice().fs); gen++
        say("QuickGPSFix removed. The TomTom will find satellites without help (slower first fix).")
    }

    @OptIn(ExperimentalResourceApi::class)
    fun installTtconv() = work("Installing TTconv…", Job.GPS) {
        val d = requireDevice()
        val files = TTCONV_FILES.associateWith { Res.readBytes("files/ttconv/$it") }
        app.mapstotom.core.installTtconv(d.fs, files)
        gen++; needsRestart()
        say("TTconv installed. Restart the TomTom (hold the power button) to start it.")
    }

    fun installGlgps() = scope.launch {
        val (name, data) = platform.pickFile("Choose glgps from NavCore 9.541 (bin/glgps)") ?: return@launch
        work("Installing glgps…", Job.GPS) {
            app.mapstotom.core.installGlgps(requireDevice().fs, data); gen++; needsRestart()
            say("$name installed as bin/glgps (old one kept as bin/glgps.orig). Restart the TomTom.")
        }
    }

    fun setClockFix(on: Boolean, log: Boolean = false) = work(if (on) "Installing the clock fix…" else "Removing the clock fix…", Job.GPS) {
        val d = requireDevice()
        if (on) installClockFix(d.fs, log) else removeClockFix(d.fs)
        gen++; needsRestart()
        say(if (on) "Clock fix installed ($CLOCK_FIX_PATH). Restart the TomTom." else "Clock fix removed.")
    }

    fun setGlttDebug(on: Boolean) = work("Changing the GPS log…", Job.GPS) {
        val fs = requireDevice().fs
        if (on) fs.write("gl/debug-gltt", ByteArray(0)) else if (fs.exists("gl/debug-gltt")) fs.delete("gl/debug-gltt")
        gen++; needsRestart()
        say(if (on) "GPS engine log on (gl/log/gltt.txt) from the next start." else "GPS engine log off.")
    }

    // ---------------------------------------------------------- device tools
    fun togglePowerOff(on: Boolean) = work("Saving…", Job.DEVICE) {
        app.mapstotom.core.setPowerOff(requireDevice().fs, on); gen++
        say(if (on) "The TomTom will switch fully off instead of standby." else "The TomTom will use standby again.")
    }

    fun toggleStayOn(on: Boolean) = work("Saving…", Job.DEVICE) {
        app.mapstotom.core.setStayOn(requireDevice().fs, on); gen++
        say(if (on) "The TomTom will stay on when car power dips. Switch it off with the power button." else "The TomTom will switch off again when external power is lost.")
    }

    // ---------------------------------------------------------- add-ons
    fun setLive(on: Boolean) = work(if (on) "Turning Live services on…" else "Turning Live services off…", Job.DEVICE) {
        app.mapstotom.core.setLive(requireDevice().fs, on); gen++; needsRestart()
        say((if (on) "Live services on, with Bluetooth." else "Live services off.") + " The TomTom uses it from its next full start.")
    }

    fun setBluetooth(on: Boolean) = work(if (on) "Turning Bluetooth on…" else "Turning Bluetooth off…", Job.DEVICE) {
        app.mapstotom.core.setBluetooth(requireDevice().fs, on); gen++; needsRestart()
        say("Bluetooth ${if (on) "on" else "off"} from the TomTom's next full start.")
    }

    fun setFuelPrices(on: Boolean) = work(if (on) "Turning fuel prices on…" else "Turning fuel prices off…", Job.DEVICE) {
        val d = requireDevice()
        val dir = d.mapFolder ?: error("No map folder on the TomTom")
        val n = app.mapstotom.core.setFuelPrices(d.fs, dir, on); gen++; if (n > 0) needsRestart()
        say(when {
            n == 0 && on -> "No fuel set turned on: a set with the same name is already there."
            on -> "Fuel prices back on from the TomTom's next start."
            else -> "Fuel prices off from the TomTom's next start. The files stay on the card."
        }, error = n == 0 && on)
    }

    var progress by mutableStateOf<Pair<Int, Int>?>(null); private set
    /** What [progress] counts ("files", "areas"), for the label under the bar. */
    var progressUnit by mutableStateOf(""); private set
    /** When the current count started, for the time left. */
    var progressSince = 0L; private set

    private fun progress(i: Int, n: Int, unit: String) {
        val p = progress
        if (p == null || p.second != n || i < p.first || unit != progressUnit) { progressSince = System.currentTimeMillis(); progressUnit = unit }
        progress = i to n
    }

    /** "about 3 min left", once there is enough to go on. */
    fun timeLeft(): String? {
        val (i, n) = progress ?: return null
        val spent = System.currentTimeMillis() - progressSince
        if (i <= 0 || spent < 3000 || i >= n) return null
        val left = spent * (n - i) / i / 1000
        return when {
            left < 60 -> "under a minute left"
            left < 3600 -> "about ${(left + 30) / 60} min left"
            else -> "about %.1f h left".format(left / 3600.0)
        }
    }

    fun backup() = scope.launch {
        val d = device ?: return@launch
        val target = platform.pickFolder(FolderPurpose.BACKUP_TARGET) ?: return@launch
        work("Backing up…", Job.BACKUP) {
            plan(listOf("List the files on the TomTom", "Copy the files"))
            step(0)
            val folder = "MapsToTom-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))
            val n = copyTree(d.fs, "", target, folder) { step(1) }
            target.write("$folder/MapsToTom-backup.txt", "Backup of ${d.model} made by MapsToTom on ${LocalDateTime.now()}\n$n files\n".toByteArray())
            say("Backed up $n files to ${target.displayName}/$folder.")
        }
    }

    fun restore() = scope.launch {
        val d = device ?: return@launch
        val src = platform.pickFolder(FolderPurpose.BACKUP_SOURCE) ?: return@launch
        work("Restoring…", Job.BACKUP) {
            require(isTomTomRoot(src) || src.exists("MapsToTom-backup.txt")) { "That folder isn't a TomTom backup" }
            plan(listOf("List the files in the backup", "Copy them to the TomTom"), restart = true)
            step(0)
            val n = copyTree(src, "", d.fs, "", skip = setOf("MapsToTom-backup.txt")) { step(1) }
            gen++; needsRestart()
            say("Restored $n files to the TomTom.")
        }
    }

    private fun copyTree(from: DeviceFs, fromPath: String, to: DeviceFs, toPath: String, skip: Set<String> = emptySet(), listed: () -> Unit = {}): Int {
        val files = mutableListOf<String>()
        fun walk(p: String) {
            for (n in from.list(p)) {
                val c = join(p, n)
                if (n in skip || n == "System Volume Information" || n.startsWith(".Trash")) continue
                if (from.isDir(c)) walk(c) else files += c
            }
        }
        walk(fromPath)
        listed()
        files.forEachIndexed { i, f ->
            progress(i, files.size, "files")
            if (i % 20 == 0) stepDetail(1, "%,d of %,d".format(i, files.size))
            to.write(join(toPath, f.removePrefix(fromPath).trimStart('/')), from.read(f) ?: ByteArray(0))
        }
        progress = null
        return files.size
    }

    // ---------------------------------------------------------- POI library
    var poiSource by mutableStateOf<DeviceFs?>(null); private set
    val libraryCategories = mutableStateListOf<PoiCategory>()

    fun choosePoiSource() = scope.launch {
        usePoiSource(platform.pickFolder(FolderPurpose.POI_SOURCE) ?: return@launch)
    }

    private fun usePoiSource(src: DeviceFs) {
        work("Reading POI files…", Job.FOLDER) {
            val cats = scanPoiFolder(src)
            poiSource = src
            withContext(Dispatchers.Main) { libraryCategories.clear(); libraryCategories.addAll(cats) }
            say("Found ${cats.size} POI categories in ${src.displayName}.")
        }
    }

    fun installPois(selected: List<PoiCategory>) = work("Installing POIs…", Job.FOLDER) {
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
    fun deletePoi(c: PoiCategory, e: Ov2Entry) = work("Deleting…", Job.ON_DEVICE) {
        val left = editPoiFile(requireDevice().fs, c.ov2) { list -> list.filterNot { sameEntry(it, e) } }
        gen++
        say("Deleted \"${e.name}\" from ${c.name} ($left left).")
    }

    fun renamePoi(c: PoiCategory, e: Ov2Entry, name: String) = work("Renaming…", Job.ON_DEVICE) {
        val n = safeName(name)
        editPoiFile(requireDevice().fs, c.ov2) { list -> list.map { if (sameEntry(it, e)) Ov2Entry(n, it.lat, it.lon) else it } }
        gen++
        say("Renamed to \"$n\".")
    }

    fun poiEntries(c: PoiCategory): List<Ov2Entry>? = device?.fs?.read(c.ov2)?.let(::parseOv2)

    // ---------------------------------------------------------- OpenStreetMap POIs
    var osmCountries by mutableStateOf("")

    /** The TomTom developer key (shared with the car link's traffic), or null. */
    val tomtomKey: String? get() = platform.loadSetting(app.mapstotom.link.CarLinkSettings.TRAFFIC_KEY)?.trim()?.takeIf { it.isNotEmpty() }
    fun saveTomtomKey(key: String) { platform.saveSetting(app.mapstotom.link.CarLinkSettings.TRAFFIC_KEY, key.trim().ifEmpty { null }) }

    /** Leave out OSM places the TomTom's own map already has (needs [tomtomKey]). */
    var osmSkipKnown by mutableStateOf(platform.loadSetting("osm.skipKnown") != "0")
        private set
    fun changeOsmSkipKnown(on: Boolean) { osmSkipKnown = on; platform.saveSetting("osm.skipKnown", if (on) "1" else "0") }
    /** What the last install left out, so the user can check it. */
    var osmSkipped by mutableStateOf<List<MapOverlap.Skipped>>(emptyList())
        private set

    private val overlapBudget = RequestBudget(platform::loadSetting, platform::saveSetting)
    fun overlapRequestsLeft() = overlapBudget.left()

    fun installOsm(types: List<OsmPoiType>) = work("Downloading from OpenStreetMap…", Job.OSM) {
        val d = requireDevice(); val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        val cc = app.mapstotom.core.parseCountries(osmCountries)
        val key = tomtomKey
        val overlap = if (osmSkipKnown && key != null)
            MapOverlap(key, net, OverlapCache(platform.dataDir?.let { java.io.File(it, "tomtom-poi-check.txt") }), overlapBudget) else null
        val done = mutableListOf<String>(); val skipped = mutableListOf<MapOverlap.Skipped>(); var unchecked = 0
        plan(types.map { it.label })
        types.forEachIndexed { i, t ->
            progress(i, types.size, "types")
            step(i, "downloading")
            val all = fetchOsmPois(t, cc, net)
            val r = if (overlap != null && t.tomtom != null) {
                step(i, "comparing with the TomTom's map")
                overlap.filter(t, all) { n, of -> step(i, "comparing with the TomTom's map, $n of $of areas") }
            } else null
            val pts = r?.kept ?: all
            r?.let { skipped += it.skipped; unchecked += it.unchecked }
            val sound = if (t.warns) Res.readBytes("files/poi/warning.ogg") else null
            writePoiCategory(d.fs, map, t.categoryName(d.language), pts, categoryIconBmp(t.rgb, t.glyph), sound)
            done += if (r == null || r.skipped.isEmpty()) "${t.label} ${pts.size}" else "${t.label} ${pts.size} (${r.skipped.size} already on the map)"
            stepDetail(i, "%,d added".format(pts.size) + if (r != null && r.skipped.isNotEmpty()) ", %,d already on the map".format(r.skipped.size) else "")
        }
        osmSkipped = skipped
        gen++
        val later = if (unchecked > 0) " $unchecked places couldn't be compared today (TomTom's free daily limit); they were added anyway, so install again tomorrow to drop any duplicates." else ""
        say("Installed from OpenStreetMap: ${done.joinToString(", ")}.$later On the TomTom: Navigate to → Point of Interest.")
    }

    // ---------------------------------------------------------- fuel prices
    /** The fuel whose prices go on the TomTom (its .fpf holds one type). */
    var fuelType by mutableStateOf(platform.loadSetting("fuelType")?.toIntOrNull()?.let(FuelType::of) ?: FuelType.DIESEL)
        private set
    private var fuelTypeChecked = false
    /** The last download per country and time, reused for an hour so changing the fuel type doesn't fetch everything again. */
    private val fuelCache = HashMap<String, Pair<Instant, List<FuelStation>>>()

    fun changeFuelType(t: FuelType) { fuelType = t; platform.saveSetting("fuelType", t.code.toString()) }

    /** Read off the UI thread. The first time per connection, the fuel type follows the set already on the TomTom. */
    fun loadFuelInfo(): FuelInfo? {
        val d = device ?: return null
        val map = d.mapFolder ?: return null
        val countries = mapCountries(d.fs, map)
        val sets = findFuelSets(d.fs, map)
        if (!fuelTypeChecked) { fuelTypeChecked = true; sets.firstOrNull()?.type?.let { fuelType = it } }
        return FuelInfo(countries, countries.mapNotNull(FuelSource::of), sets)
    }

    fun installFuel() = work("Downloading fuel prices…", Job.FUEL) {
        val d = requireDevice(); val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        val sources = mapCountries(d.fs, map).mapNotNull(FuelSource::of)
        require(sources.isNotEmpty()) { "There is no open price data for this map's countries yet" }
        val type = fuelType
        val fresh = Instant.now().minusSeconds(3600)
        var time = Instant.now()
        plan(sources.map { "${it.country}: download prices" } + "Write to the TomTom", restart = true)
        val stations = sources.flatMapIndexed { si, s ->
            val key = s.iso3 + if (s.byType) "/${type.code}" else ""
            step(si)
            fuelCache[key]?.takeIf { it.first.isAfter(fresh) }?.let { (t, list) ->
                time = minOf(time, t); stepDetail(si, "downloaded at ${fmtTime(t)}, reused"); return@flatMapIndexed list }
            fetchFuel(s, net, type) { i, n ->
                progress(i, n, "areas")
                if (i < n) step(si, "${i * 100 / n}%")
            }.also { fuelCache[key] = Instant.now() to it; stepDetail(si, "%,d stations".format(it.size)) }
        }
        progress = null
        step(sources.size)
        installFuelPrices(d.fs, map, stations, type, time)
        needsRestart()
        // So the car link can send today's prices for this set later.
        platform.dataDir?.let { runCatching { app.mapstotom.link.StoredFuelSets.record(java.io.File(it, "carlink"), fuelSetId(map), sources, fuelSetOrder(stations)) } }
        gen++
        val sold = stations.count { (it.prices[type.code] ?: 0) > 0 }
        say("Fuel prices installed: ${"%,d".format(stations.size)} stations, $sold with ${type.label}. " +
            "Restart the TomTom (hold the power button until it restarts) so it finds them.")
    }

    // ---------------------------------------------------------- Google saved places
    val takeoutLists = mutableStateListOf<SavedList>()

    fun openTakeout() = scope.launch {
        val (name, data) = platform.pickFile("Open your Google Takeout (.zip, or a .csv / .json from it)") ?: return@launch
        loadTakeout(name, data)
    }

    private fun loadTakeout(name: String, data: ByteArray) {
        work("Reading $name…", Job.GOOGLE) {
            val lists = readTakeout(name, data)
            withContext(Dispatchers.Main) { takeoutLists.clear(); takeoutLists.addAll(lists) }
            say(if (lists.isEmpty()) "No saved places found in $name." else "Found ${lists.size} lists with ${lists.sumOf { it.rows.size }} places.", lists.isEmpty())
        }
    }

    fun installTakeout(selected: List<SavedList>) = work("Looking up saved places…", Job.GOOGLE) {
        val d = requireDevice(); val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        val total = selected.sumOf { it.rows.size }
        var base = 0
        val notes = mutableListOf<String>()
        plan(selected.map { "Google " + it.name })
        for ((li, l) in selected.withIndex()) {
            step(li)
            val (pts, missed) = resolveSaved(l, net) { i, _ -> progress(base + i, total, "places") }
            stepDetail(li, "%,d places".format(pts.size) + if (missed.isNotEmpty()) ", ${missed.size} not found" else "")
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
        loadGpi(name, data)
    }

    private fun loadGpi(name: String, data: ByteArray) {
        work("Reading $name…", Job.GPI) {
            val g = readGpi(data)
            withContext(Dispatchers.Main) { gpiFile = g }
            say("${g.title}: ${g.categories.size} categories, ${g.categories.sumOf { it.points.size }} places.")
        }
    }

    /** The area filter in use, or null for the whole file. */
    fun gpiFilter(): ((Point) -> Boolean)? = if (gpiAreaOnly) areaFilter(runCatching { app.mapstotom.core.parseCountries(osmCountries) }.getOrDefault(emptyList())) else null

    /** "iOverlander Wild Camping": the file's first word keeps categories from different files apart. */
    fun gpiCategoryName(c: GpiCategory) = device?.let { d -> gpiTranslated(gpiFile?.title, c.name)?.nameIn(d.language) } ?: listOfNotNull(gpiFile?.title?.substringBefore(' ')?.takeIf { it.isNotBlank() }, c.name).joinToString(" ")

    fun installGpi(selected: List<GpiCategory>) = work("Installing…", Job.GPI) {
        val d = requireDevice(); val map = d.mapFolder ?: error("No active map folder found on the TomTom")
        val keep = gpiFilter()
        val done = mutableListOf<String>()
        selected.forEachIndexed { i, c ->
            progress(i, selected.size, "categories")
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

    fun deletePoiCategory(c: PoiCategory) = work("Deleting…", Job.ON_DEVICE) {
        val fs = requireDevice().fs
        fs.delete(c.ov2); c.bmp?.let { fs.delete(it) }; c.ogg?.let { fs.delete(it) }; c.fpf?.let { fs.delete(it) }
        gen++
        say("Deleted POI category \"${c.name}\".")
    }

    companion object {
        val TTCONV_FILES = listOf(
            "ttn", "bin/ttconv", "fixdate/glttconv.so", "fixdate/Orion.ini", "fixdate/rc.athgps",
            "fixdate/rc.gltt", "fixdate/resetgps", "fixdate/ttconv.sh",
            "sdkregistry/ttconv.bmp", "sdkregistry/ttconv.cap", "README-ttconv.txt",
        )

        fun fmtTime(t: Instant): String = DateTimeFormatter.ofPattern("HH:mm").format(t.atZone(ZoneId.systemDefault()))

        const val RESTART_STEP = "Restart the TomTom: hold the power button about 15 seconds"

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
        val data = fs.read(join(dir, f)) ?: ByteArray(0)
        if (isFuelOv2(data)) {
            return@map PoiCategory(base, join(dir, f), find(base, "bmp"), find(base, "ogg"), fuelOv2Count(data),
                fs.modified(join(dir, f)), editable = false, fpf = find(base, "fpf"))
        }
        val c = runCatching { readOv2Contents(data) }.getOrNull()
        val entries = c?.let { if (signed) null else parseOv2(data) }
        PoiCategory(base, join(dir, f), find(base, "bmp"), find(base, "ogg"), entries?.size ?: 0,
            fs.modified(join(dir, f)), isProtected = signed, editable = entries != null && c.complete && !signed)
    }.sortedBy { it.name.lowercase() }
}

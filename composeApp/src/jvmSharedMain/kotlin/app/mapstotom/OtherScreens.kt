package app.mapstotom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Info
import app.mapstotom.core.Addons
import app.mapstotom.core.LIVE_MODELS
import app.mapstotom.core.powerOffEnabled
import app.mapstotom.core.readAddons
import app.mapstotom.core.stayOnEnabled
import app.mapstotom.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ Device

@Composable
fun ColumnScope.DeviceScreen(state: AppState) {
    val t = LocalTokens.current
    val d = state.device
    if (d == null) { ScreenHeader("Device"); NeedDevice(state); AboutFooter(state); return }
    val idle = state.busy == null
    var confirmRestore by remember { mutableStateOf(false) }
    val infoLoad by produceLoad(d, state.gen) { Triple(d.fs.freeSpace(), powerOffEnabled(d.fs), stayOnEnabled(d.fs)) }
    val info = (infoLoad as? Load.Ready)?.value

    ScreenHeader(d.model, d.fs.displayName)

    Panel(Modifier.widthIn(max = Space.measure)) {
        d.navcore?.let { Fact("NavCore", it) }
        d.bif["BootLoaderVersion"]?.let { Fact("Bootloader", it) }
        Fact("Map", d.bif["CurrentMap"] ?: d.mapFolder ?: "unknown")
        Fact("GPS chip", d.chip.chip.label)
        if (d.chip.firmware.isNotEmpty()) Fact("GPS firmware", d.chip.firmware)
        info?.first?.let { Fact("Free space", "%.0f MB".format(it / 1e6)) }
        Spacer(Modifier.height(Space.sm))
        Row { TextButton(onClick = { state.refresh() }, enabled = idle, contentPadding = PaddingValues(horizontal = 0.dp, vertical = Space.sm)) { Text("Read again") } }
    }

    val addonsLoad by produceLoad(d, state.gen) { readAddons(d.fs, d.mapFolder, d.chip.chip) }
    when (val l = addonsLoad) {
        Load.Loading -> {}
        is Load.Failed -> { Spacer(Modifier.height(Space.xxl)); LoadFailed(state, "the add-ons", l) }
        is Load.Ready -> l.value.takeIf { it.any }?.let { AddonsSection(state, it, idle) }
    }
    JobFeedback(state, Job.DEVICE, Modifier.widthIn(max = Space.measure))
    (infoLoad as? Load.Failed)?.let { f ->
        Spacer(Modifier.height(Space.xxl))
        LoadFailed(state, "the power settings", f)
    }

    Spacer(Modifier.height(Space.xxl))
    Text("Power button", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.md))
    val off = info?.second ?: false
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(off, { state.togglePowerOff(it) }, enabled = idle && info != null)
        Spacer(Modifier.width(Space.md))
        Text(if (off) "Switches fully off" else "Goes to standby (TomTom default)", style = MaterialTheme.typography.titleSmall, color = t.text)
    }
    Spacer(Modifier.height(Space.sm))
    Prose("Fully off saves the battery, but every start is a cold boot and a unit with a worn clock battery loses the time, which slows the GPS. Standby keeps the clock.")

    Spacer(Modifier.height(Space.xxl))
    Text("Car power", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.md))
    val stayOn = info?.third ?: false
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(stayOn, { state.toggleStayOn(it) }, enabled = idle && info != null)
        Spacer(Modifier.width(Space.md))
        Text(if (stayOn) "Stays on when power drops" else "Counts down and switches off when power drops (TomTom default)",
            Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, color = t.text)
    }
    Spacer(Modifier.height(Space.sm))
    Prose("Turn on if the TomTom keeps showing a 10-second \"switching off\" countdown on a weak USB port, like a car radio's. It then no longer switches off with the car, so use the power button.")

    Spacer(Modifier.height(Space.xxl))
    Text("Backup", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.sm))
    Prose("Copies every file on the TomTom into a new dated folder. Back up before installing fixes.")
    Spacer(Modifier.height(Space.md))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        PrimaryButton("Back up", { state.backup() }, enabled = idle, icon = Icons.Filled.Backup,
            busy = state.running(Job.BACKUP) && state.busy == "Backing up…", busyText = "Backing up…")
        DangerTextButton("Restore a backup", { confirmRestore = true }, enabled = idle)
    }
    JobFeedback(state, Job.BACKUP)

    AboutFooter(state)

    if (confirmRestore) ConfirmDanger("Restore a backup?",
        "Files from the backup replace the ones on the TomTom. Files that exist only on the TomTom are kept.", "Choose backup",
        onConfirm = { confirmRestore = false; state.restore() }, onDismiss = { confirmRestore = false })
}

// Costs measured on a ONE v4 (29.5 MB memory, 264 MHz) with the TomTom's own memory and process figures, 31/10-1/11/2026.
private const val LIVE_COST = "Measured on a ONE v4: about 1.0–1.5 MB more memory for the navigation program, about 5% of the " +
    "29.5 MB it has. No processor cost measured: the navigation program used 70–83% of the processor while driving, with or " +
    "without Live. Needs Bluetooth, so switching Live on turns Bluetooth on too. The TomTom then acts as a GO x40 " +
    "(model:x40 in Config/setconfig.txt); switching Live off puts back the model it had before. A TomTom with 64 MB, like the GO 950, has more room for it. " +
    "The TomTom goes online through a phone: this app's car link on a phone with Android 8 or newer (no root), or the " +
    "TT Link app on an old rooted Android 4.0–4.1 phone (ttlink/README.md in this app's source)."
private const val BLUETOOTH_COST = "Measured on a ONE v4: about 0.5–0.8 MB more memory with Bluetooth on (the radio and its " +
    "link program). Bluetooth's four background programs, about 1–1.4 MB, run either way on a TomTom that has Bluetooth. " +
    "No processor difference measured, but in use the TomTom felt noticeably smoother with it off. With Live on, the " +
    "TomTom also calls the phone again once a minute while the link is down. Off also turns off hands-free calls."
private const val FUEL_COST = "Not measured yet. Off renames the set's files so the TomTom ignores them; on puts them back. " +
    "The files stay on the card either way."
private const val GPS_LOG_COST = "Not measured. It writes the GPS engine's messages to the card the whole time the TomTom " +
    "is on, so keep it for troubleshooting. The same switch is on the GPS tab."
private const val SWAP_COST = "Not something to switch: the TomTom's system sets the swap file back to 8 MB at every start " +
    "and then fills it on purpose, so a full swap is normal. In the ONE v4 tests the TomTom waited for the card only 0–3% " +
    "of the time, so slowness comes from the processor, not from swapping."

@Composable
private fun AddonsSection(state: AppState, a: Addons, idle: Boolean) {
    val t = LocalTokens.current
    val sc = a.setConfig
    Spacer(Modifier.height(Space.xxl))
    Text("Add-ons", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.sm))
    Prose("Each one uses some of the TomTom's memory or processor. " +
        (if (state.platform.isAndroid) "Tap the i" else "Point at the i") + " to see what it costs, so you can keep only what you use.")
    Spacer(Modifier.height(Space.md))
    Panel(Modifier.widthIn(max = Space.measure), padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
        val rows = mutableListOf<@Composable () -> Unit>()
        if (sc != null) {
            val live = sc.live
            rows += {
                AddonRow("Live services",
                    when {
                        sc.hasModelId -> "Can't switch: ${sc.path} sets a modelid, which overrides the model."
                        !a.carLinkReady && !live -> "Traffic, weather and fuel prices through a phone. Needs the car-link files on the card first."
                        live && sc.bluetoothOff -> "Needs Bluetooth, which is off."
                        else -> "Traffic, weather and fuel prices through a phone running the car link."
                    },
                    live, idle && !sc.hasModelId && (a.carLinkReady || live), { state.setLive(it) }, LIVE_COST, state.platform.isAndroid)
            }
            rows += {
                AddonRow("Bluetooth", "Hands-free calls, and the phone link for Live services.",
                    !sc.bluetoothOff, idle, { state.setBluetooth(it) }, BLUETOOTH_COST, state.platform.isAndroid)
            }
        }
        val fuel = a.fuelOn.ifEmpty { a.fuelOff }
        if (fuel.isNotEmpty()) rows += {
            val f = fuel.first()
            AddonRow("Fuel prices", "${f.name}: %,d stations, %.1f MB".format(f.stations, f.bytes / 1e6),
                a.fuelOn.isNotEmpty(), idle, { state.setFuelPrices(it) }, FUEL_COST, state.platform.isAndroid)
        }
        a.gpsLog?.let { on ->
            rows += { AddonRow("GPS engine log", "Writes gl/log/gltt.txt, for troubleshooting.", on, idle, { state.setGlttDebug(it) }, GPS_LOG_COST, state.platform.isAndroid) }
        }
        a.swapBytes?.let { b -> rows += { AddonRow("Swap file", "%.0f MB on the card".format(b / 1048576.0), false, false, null, SWAP_COST, state.platform.isAndroid) } }
        rows.forEachIndexed { i, row -> if (i > 0) Hairline(); row() }
    }
    if (sc != null) {
        Spacer(Modifier.height(Space.sm))
        val last = a.last
        val lastText = if (last?.emulation == null) "" else
            "At its last start: Live ${if (last.emulation in LIVE_MODELS) "on" else "off"}" +
                (last.bluetooth?.let { if (it) ", Bluetooth on. " else ", Bluetooth off. " } ?: ". ")
        Prose(lastText + if (a.waitsForRestart) "Changes made since then wait for the next full start, not a wake from standby: hold the power button until the TomTom restarts (about 15 seconds)."
            else "Changes apply at the next full start, not when it wakes from standby.")
    }
}

/**
 * One add-on: name, what it does, its switch (null: information only), and an i for what it costs.
 * Desktop: the cost shows in a hint on pointing (or a click). Touch: a tap opens it under the row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddonRow(title: String, detail: String, on: Boolean, enabled: Boolean, onChange: ((Boolean) -> Unit)?, cost: String, touch: Boolean) {
    val t = LocalTokens.current
    var open by remember(title) { mutableStateOf(false) }
    val tip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = t.text)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            }
            val button = @Composable {
                IconButton(onClick = { if (touch) open = !open else scope.launch { tip.show() } }) {
                    Icon(if (open || tip.isVisible) Icons.Filled.Info else Icons.Outlined.Info, contentDescription = "What $title costs",
                        tint = if (open || tip.isVisible) t.action else t.textMuted)
                }
            }
            if (touch) button() else TooltipBox(
                positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
                tooltip = {
                    RichTooltip(title = { Text("What $title costs") }, colors = TooltipDefaults.richTooltipColors(
                        containerColor = t.surface, contentColor = t.text, titleContentColor = t.text),
                        modifier = Modifier.border(BorderStroke(1.dp, t.hairline), Radius.control)) { Text(cost) }
                },
                state = tip, content = button)
            if (onChange != null) Switch(on, onChange, enabled = enabled)
            else Spacer(Modifier.width(52.dp)) // a switch's width, so the i stays in line with the rows above
        }
        if (open) Prose(cost, modifier = Modifier.padding(bottom = Space.md))
    }
}

@Composable
private fun AboutFooter(state: AppState) {
    val t = LocalTokens.current
    Spacer(Modifier.height(Space.xxxl))
    Hairline()
    Spacer(Modifier.height(Space.lg))
    Text("MapsToTom ${BuildInfo.VERSION}. MIT licence.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    Text("Includes TTconv by treysis (GPL-3.0). Place lookups by OpenStreetMap Nominatim and OSRM. QuickGPSFix from TomTom's server.",
        Modifier.widthIn(max = Space.measure), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    TextButton(onClick = { state.platform.openUrl("https://github.com/skinnie/MapsToTom") }, contentPadding = PaddingValues(0.dp)) {
        Text("github.com/skinnie/MapsToTom")
    }
}

object BuildInfo { const val VERSION = "1.0.0" }

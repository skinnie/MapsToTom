package app.mapstotom

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mapstotom.core.powerOffEnabled
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
    val info by produceState<Pair<Long?, Boolean>?>(null, d, state.gen) {
        value = withContext(Dispatchers.IO) { runCatching { d.fs.freeSpace() to powerOffEnabled(d.fs) }.getOrNull() }
    }

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
    Text("Backup", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.sm))
    Prose("Copies every file on the TomTom into a new dated folder. Back up before installing fixes.")
    Spacer(Modifier.height(Space.md))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        PrimaryButton("Back up", { state.backup() }, enabled = idle, icon = Icons.Filled.Backup)
        DangerTextButton("Restore a backup", { confirmRestore = true }, enabled = idle)
    }

    AboutFooter(state)

    if (confirmRestore) ConfirmDanger("Restore a backup?",
        "Files from the backup replace the ones on the TomTom. Files that exist only on the TomTom are kept.", "Choose backup",
        onConfirm = { confirmRestore = false; state.restore() }, onDismiss = { confirmRestore = false })
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

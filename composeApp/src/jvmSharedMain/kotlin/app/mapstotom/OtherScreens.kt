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

// ------------------------------------------------------------------ POIs

@Composable
fun ColumnScope.PoiScreen(state: AppState, wide: Boolean) {
    var confirmDelete by remember { mutableStateOf<PoiCategory?>(null) }
    ScreenHeader("Points of interest", "Install categories of places, with their icon and warning sound, and tidy up the ones on the TomTom.")
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            Column(Modifier.weight(7f)) { Library(state) }
            Column(Modifier.weight(5f)) { OnDevice(state) { confirmDelete = it } }
        }
    } else {
        Library(state)
        Spacer(Modifier.height(Space.xxl))
        OnDevice(state) { confirmDelete = it }
    }
    confirmDelete?.let { c ->
        ConfirmDanger("Delete \"${c.name}\"?", "This removes ${c.count} places and the category icon from the TomTom.", "Delete",
            onConfirm = { state.deletePoiCategory(c); confirmDelete = null }, onDismiss = { confirmDelete = null })
    }
}

@Composable
private fun Library(state: AppState) {
    val t = LocalTokens.current
    val idle = state.busy == null
    Text("Install from a folder", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.sm))
    Prose("Each .ov2 file is a category. A .bmp icon and an .ogg warning sound with the same name are installed with it.")
    Spacer(Modifier.height(Space.md))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(if (state.poiSource == null) "Choose folder" else "Change folder", { state.choosePoiSource() }, enabled = idle, icon = Icons.Filled.FolderOpen)
        state.poiSource?.let {
            Spacer(Modifier.width(Space.md))
            Text(it.displayName, style = MaterialTheme.typography.bodySmall, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (state.libraryCategories.isEmpty()) return
    val selected = remember(state.libraryCategories.toList()) { mutableStateListOf<PoiCategory>() }
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
        Row(Modifier.padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected.clear(); selected.addAll(state.libraryCategories) }) { Text("Select all") }
            TextButton(onClick = { selected.clear() }, enabled = selected.isNotEmpty()) { Text("None") }
            Spacer(Modifier.weight(1f))
            PrimaryButton(if (selected.isEmpty()) "Install" else "Install ${selected.size}",
                { state.installPois(selected.toList()) }, enabled = idle && state.device != null && selected.isNotEmpty())
        }
        state.libraryCategories.forEach { c ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(c in selected, { on -> if (on) selected.add(c) else selected.remove(c) })
                Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.bmp != null) Icon(Icons.Filled.Image, "Has icon", tint = t.textMuted, modifier = Modifier.size(16.dp))
                if (c.ogg != null) { Spacer(Modifier.width(Space.xs)); Icon(Icons.Filled.VolumeUp, "Has sound", tint = t.textMuted, modifier = Modifier.size(16.dp)) }
                Text("${c.count}", Modifier.width(56.dp).padding(end = Space.sm), style = MaterialTheme.typography.bodySmall, color = t.textMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@Composable
private fun OnDevice(state: AppState, onDelete: (PoiCategory) -> Unit) {
    val t = LocalTokens.current
    val d = state.device
    Text("On the TomTom", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.sm))
    if (d == null) { NeedDevice(state); return }
    val cats by produceState(emptyList<PoiCategory>(), d, state.gen) {
        value = withContext(Dispatchers.IO) { runCatching { state.deviceCategories() }.getOrDefault(emptyList()) }
    }
    Prose("${cats.size} categories in ${d.mapFolder ?: "no map folder"}.")
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(start = Space.lg, end = Space.xs, top = Space.xs, bottom = Space.xs)) {
        if (cats.isEmpty()) Text("None yet.", Modifier.padding(vertical = Space.md), color = t.textMuted)
        cats.forEachIndexed { i, c ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${c.count}", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                IconButton(onClick = { onDelete(c) }, enabled = state.busy == null) { Icon(Icons.Filled.DeleteOutline, "Delete ${c.name}", tint = t.danger) }
            }
        }
    }
}

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

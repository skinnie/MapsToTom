package app.mapstotom

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mapstotom.core.*
import app.mapstotom.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

@Composable
fun ColumnScope.PoiScreen(state: AppState, wide: Boolean) {
    var confirmDelete by remember { mutableStateOf<PoiCategory?>(null) }
    ScreenHeader("Points of interest", "Fresh places from OpenStreetMap, your Google Maps lists and Garmin POI files, and a tidy-up of what the TomTom already has.")
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            Column(Modifier.weight(6f)) { AddPois(state) }
            Column(Modifier.weight(6f)) { OnDevice(state) { confirmDelete = it } }
        }
    } else {
        AddPois(state)
        Spacer(Modifier.height(Space.xxl))
        OnDevice(state) { confirmDelete = it }
    }
    confirmDelete?.let { c ->
        ConfirmDanger("Delete \"${c.name}\"?", "This removes ${c.count} places and the category icon from the TomTom.", "Delete",
            onConfirm = { state.deletePoiCategory(c); confirmDelete = null }, onDismiss = { confirmDelete = null })
    }
}

@Composable
private fun AddPois(state: AppState) {
    OsmSection(state)
    Spacer(Modifier.height(Space.xxl))
    GoogleSection(state)
    Spacer(Modifier.height(Space.xxl))
    GpiSection(state)
    Spacer(Modifier.height(Space.xxl))
    FolderSection(state)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = LocalTokens.current.text)
    Spacer(Modifier.height(Space.sm))
}

// ------------------------------------------------------------------ OpenStreetMap

@Composable
private fun OsmSection(state: AppState) {
    val t = LocalTokens.current
    val idle = state.busy == null
    val chosen = remember { mutableStateListOf(OsmPoiType.FUEL) }
    SectionTitle("From OpenStreetMap")
    Prose("Up-to-date places, each type as its own category with an icon. Speed cameras also get a warning beep.")
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
        OsmPoiType.entries.forEachIndexed { i, type ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().clickable { if (type in chosen) chosen.remove(type) else chosen.add(type) }.padding(vertical = Space.xxs),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(type in chosen, { on -> if (on) chosen.add(type) else chosen.remove(type) })
                Text(type.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.text)
                if (type.warns) Icon(Icons.Filled.VolumeUp, "Warning sound", tint = t.textMuted, modifier = Modifier.padding(end = Space.md).size(18.dp))
            }
        }
    }
    Spacer(Modifier.height(Space.md))
    OutlinedTextField(state.osmCountries, { state.osmCountries = it }, Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        label = { Text("Countries") }, supportingText = { Text("2-letter codes, from your map: PT, ES") }, singleLine = true)
    val cams = OsmPoiType.SPEED_CAMERA in chosen
    val premium = state.device?.let { d -> d.mapFolder?.let { m -> d.fs.list(m).any { it.startsWith("Safety_Cam", true) } } } == true
    if (cams && premium) {
        Spacer(Modifier.height(Space.sm))
        StatusRow(Status.WARN, "You already have TomTom's premium cameras",
            "They list more cameras than OpenStreetMap. Installing both gives double warnings.") {}
    }
    if (cams && "FR" in state.osmCountries.uppercase()) {
        Spacer(Modifier.height(Space.sm))
        StatusRow(Status.BAD, "Not for France", "Warning of speed camera positions is illegal in France. Remove FR to continue.") {}
    }
    Spacer(Modifier.height(Space.md))
    PrimaryButton(if (chosen.isEmpty()) "Install" else "Install ${chosen.size}", { state.installOsm(chosen.toList()) },
        enabled = idle && state.device != null && chosen.isNotEmpty(), icon = Icons.Filled.Download)
    Spacer(Modifier.height(Space.xs))
    Text("Data © OpenStreetMap contributors (ODbL). A whole country takes a minute or two.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
}

// ------------------------------------------------------------------ Google

@Composable
private fun GoogleSection(state: AppState) {
    val t = LocalTokens.current
    val idle = state.busy == null
    SectionTitle("Your Google Maps lists")
    Prose("Your starred places and saved lists, one category per list. Export them at takeout.google.com: choose only \"Saved\" and \"Maps (your places)\", then open the .zip here.")
    Spacer(Modifier.height(Space.md))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton("Open Takeout file", { state.openTakeout() }, enabled = idle, icon = Icons.Filled.FolderOpen)
        TextButton(onClick = { state.platform.openUrl("https://takeout.google.com/settings/takeout/custom/saved,maps") }) { Text("Go to Google Takeout") }
    }
    if (state.takeoutLists.isEmpty()) return
    val selected = remember(state.takeoutLists.toList()) { mutableStateListOf<SavedList>().apply { addAll(state.takeoutLists) } }
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
        state.takeoutLists.forEachIndexed { i, l ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(l in selected, { on -> if (on) selected.add(l) else selected.remove(l) })
                Text("Google " + l.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${l.rows.size}", Modifier.padding(end = Space.sm), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            }
        }
    }
    Spacer(Modifier.height(Space.md))
    PrimaryButton(if (selected.isEmpty()) "Install" else "Install ${selected.size}", { state.installTakeout(selected.toList()) },
        enabled = idle && state.device != null && selected.isNotEmpty(), icon = Icons.Filled.Download)
    Spacer(Modifier.height(Space.xs))
    Text("Places saved without coordinates are looked up on Google, about one a second.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
}

// ------------------------------------------------------------------ Garmin .gpi

@Composable
private fun GpiSection(state: AppState) {
    val t = LocalTokens.current
    val idle = state.busy == null
    SectionTitle("From a Garmin POI file")
    Prose("Garmin .gpi files, like iOverlander or camper POI packs. Each category becomes its own TomTom category, with the file's icon.")
    Spacer(Modifier.height(Space.md))
    SecondaryButton("Open .gpi file", { state.openGpi() }, enabled = idle, icon = Icons.Filled.FolderOpen)
    val file = state.gpiFile ?: return
    val keep = state.gpiFilter()
    val counts = remember(file, keep != null, state.osmCountries) { file.categories.associateWith { c -> if (keep == null) c.points.size else c.points.count(keep) } }
    val selected = remember(file) { mutableStateListOf<GpiCategory>() }
    Spacer(Modifier.height(Space.md))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(state.gpiAreaOnly, { state.gpiAreaOnly = it })
        Spacer(Modifier.width(Space.sm))
        Text(if (state.gpiAreaOnly) "Only places in ${state.osmCountries.ifBlank { "your map's countries" }}" else "Whole file",
            style = MaterialTheme.typography.bodyMedium, color = t.text)
    }
    if (state.gpiAreaOnly && keep == null) Text("No known area for these countries, so the whole file is used.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    Spacer(Modifier.height(Space.sm))
    Panel(padding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
        Text(file.title, Modifier.padding(start = Space.sm, top = Space.xs), style = MaterialTheme.typography.titleSmall, color = t.text)
        file.categories.groupBy { it.group }.forEach { (group, cats) ->
            Text(group, Modifier.padding(start = Space.sm, top = Space.sm), style = MaterialTheme.typography.labelMedium, color = t.textMuted)
            cats.forEach { c ->
                val n = counts[c] ?: 0
                Row(Modifier.fillMaxWidth().clickable(enabled = n > 0) { if (c in selected) selected.remove(c) else selected.add(c) }.padding(vertical = Space.xxs),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(c in selected && n > 0, { on -> if (on) selected.add(c) else selected.remove(c) }, enabled = n > 0)
                    Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (n > 0) t.text else t.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$n", Modifier.padding(end = Space.sm), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                }
            }
        }
    }
    val chosen = selected.filter { (counts[it] ?: 0) > 0 }
    Spacer(Modifier.height(Space.md))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(if (chosen.isEmpty()) "Install" else "Install ${chosen.size}", { state.installGpi(chosen) },
            enabled = idle && state.device != null && chosen.isNotEmpty(), icon = Icons.Filled.Download)
        TextButton(onClick = { selected.clear(); if (chosen.size < counts.count { it.value > 0 }) selected.addAll(file.categories.filter { (counts[it] ?: 0) > 0 }) }) {
            Text(if (chosen.size < counts.count { it.value > 0 }) "Select all" else "Select none")
        }
    }
    Spacer(Modifier.height(Space.xs))
    Text("On the TomTom they're named after the file, like \"${file.categories.firstOrNull()?.let { state.gpiCategoryName(it) }.orEmpty()}\". Installing again replaces them.",
        style = MaterialTheme.typography.bodySmall, color = t.textMuted)
}

// ------------------------------------------------------------------ folder

@Composable
private fun FolderSection(state: AppState) {
    val t = LocalTokens.current
    val idle = state.busy == null
    SectionTitle("From a folder")
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
                    textAlign = TextAlign.End)
            }
        }
    }
}

// ------------------------------------------------------------------ on the TomTom

private fun yearOf(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year }

@Composable
private fun OnDevice(state: AppState, onDelete: (PoiCategory) -> Unit) {
    val t = LocalTokens.current
    val d = state.device
    SectionTitle("On the TomTom")
    if (d == null) { NeedDevice(state); return }
    val cats by produceState(emptyList<PoiCategory>(), d, state.gen) {
        value = withContext(Dispatchers.IO) { runCatching { state.deviceCategories() }.getOrDefault(emptyList()) }
    }
    var open by remember(d) { mutableStateOf<String?>(null) }
    val now = java.time.Year.now().value
    val old = cats.count { c -> yearOf(c.modified)?.let { it <= now - 3 } == true }
    val dup = cats.count { builtInEquivalent(it.name) != null }
    Prose("${cats.size} categories in ${d.mapFolder ?: "no map folder"}." +
        (if (old > 0) " $old are 3 years old or more." else "") +
        (if (dup > 0) " $dup look like categories your map already has, so they can usually go." else ""))
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(start = Space.lg, end = Space.xs, top = Space.xs, bottom = Space.xs)) {
        if (cats.isEmpty()) Text("None yet.", Modifier.padding(vertical = Space.md), color = t.textMuted)
        cats.forEachIndexed { i, c ->
            if (i > 0) Hairline()
            val year = yearOf(c.modified)
            val also = builtInEquivalent(c.name)
            Row(Modifier.fillMaxWidth().clickable(enabled = c.editable) { open = if (open == c.ov2) null else c.ov2 }.padding(vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val bits = listOfNotNull(
                        if (c.isProtected) "protected (TomTom service)" else "${c.count} places" + if (c.editable) "" else ", read-only",
                        year?.toString(),
                        also?.let { "map has \"$it\"" },
                    )
                    Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall,
                        color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!c.editable) Icon(Icons.Filled.Lock, "Protected", tint = t.textMuted, modifier = Modifier.padding(horizontal = Space.xs).size(18.dp))
                else Icon(if (open == c.ov2) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open == c.ov2) "Hide places" else "Show places", tint = t.textMuted)
                IconButton(onClick = { onDelete(c) }, enabled = state.busy == null) { Icon(Icons.Filled.DeleteOutline, "Delete ${c.name}", tint = t.danger) }
            }
            if (open == c.ov2) Places(state, c)
        }
    }
}

/** The places in one category, with a filter, rename and delete. */
@Composable
private fun Places(state: AppState, c: PoiCategory) {
    val t = LocalTokens.current
    val entries by produceState<List<Ov2Entry>?>(null, c.ov2, state.gen) {
        value = withContext(Dispatchers.IO) { runCatching { state.poiEntries(c) }.getOrNull() }
    }
    val list = entries ?: return
    var filter by remember(c.ov2) { mutableStateOf("") }
    var editing by remember(c.ov2) { mutableStateOf<Ov2Entry?>(null) }
    var confirm by remember(c.ov2) { mutableStateOf<Ov2Entry?>(null) }
    val shown = list.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
    Column(Modifier.padding(start = Space.sm, end = Space.sm, bottom = Space.sm)) {
        if (list.size > 8) OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Filter ${list.size} places") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
        shown.take(100).forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (editing === e) {
                    var name by remember(e) { mutableStateOf(e.name) }
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { state.renamePoi(c, e, name); editing = null }))
                    TextButton(onClick = { state.renamePoi(c, e, name); editing = null }) { Text("Save") }
                } else {
                    Column(Modifier.weight(1f).clickable { editing = e }.padding(vertical = Space.xs)) {
                        Text(e.name.ifEmpty { "(no name)" }, style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("%.5f, %.5f".format(java.util.Locale.ROOT, e.lat, e.lon), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    }
                    IconButton(onClick = { confirm = e }, enabled = state.busy == null) { Icon(Icons.Filled.Close, "Delete ${e.name}", tint = t.danger) }
                }
            }
        }
        if (shown.size > 100) Text("Showing 100 of ${shown.size}. Type to narrow down.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        Text("Tap a name to rename it.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    }
    confirm?.let { e ->
        ConfirmDanger("Delete \"${e.name}\"?", "It is removed from ${c.name} on the TomTom. The first change keeps a copy of the file.", "Delete",
            onConfirm = { state.deletePoi(c, e); confirm = null }, onDismiss = { confirm = null })
    }
}

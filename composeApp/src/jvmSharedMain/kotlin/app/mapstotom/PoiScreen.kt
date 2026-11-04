package app.mapstotom

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
            Column(Modifier.weight(6f)) { OnDevice(state, wideHint = true) { confirmDelete = it } }
        }
    } else {
        AddPois(state)
        Spacer(Modifier.height(Space.xxl))
        OnDevice(state, wideHint = false) { confirmDelete = it }
    }
    confirmDelete?.let { c ->
        ConfirmDanger("Delete \"${c.name}\"?", "This removes ${c.count} places and the category icon from the TomTom.", "Delete",
            onConfirm = { state.deletePoiCategory(c); confirmDelete = null }, onDismiss = { confirmDelete = null })
    }
}

@Composable
private fun AddPois(state: AppState) {
    FuelSection(state)
    Spacer(Modifier.height(Space.xxl))
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

// ------------------------------------------------------------------ fuel prices

private val COUNTRY_NAMES = FuelSource.entries.associate { it.iso3 to it.country } + mapOf(
    "AND" to "Andorra", "GIB" to "Gibraltar", "MCO" to "Monaco", "SMR" to "San Marino", "VAT" to "Vatican City", "LIE" to "Liechtenstein",
    "DEU" to "Germany", "CHE" to "Switzerland", "GBR" to "United Kingdom", "IRL" to "Ireland", "BEL" to "Belgium", "NLD" to "Netherlands",
    "LUX" to "Luxembourg", "DNK" to "Denmark", "SWE" to "Sweden", "NOR" to "Norway", "FIN" to "Finland", "POL" to "Poland",
    "CZE" to "Czechia", "SVK" to "Slovakia", "HUN" to "Hungary", "GRC" to "Greece", "USA" to "USA", "CAN" to "Canada")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FuelSection(state: AppState) {
    val t = LocalTokens.current
    val idle = state.busy == null
    val d = state.device
    SectionTitle("Fuel prices")
    Prose("Every fuel station with today's price, from official open data. On the TomTom: find fuel by price, " +
        "or the cheapest nearby or on your route.")
    Spacer(Modifier.height(Space.md))
    if (d == null) { NeedDevice(state); return }
    val load by produceLoad(d, state.gen) { state.loadFuelInfo() ?: error("no map folder found") }
    val i = when (val l = load) {
        Load.Loading -> { Panel(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) { SkeletonRows(2) }; return }
        is Load.Failed -> { LoadFailed(state, "the fuel prices", l); return }
        is Load.Ready -> l.value
    }
    val set = i.sets.firstOrNull()
    Panel(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
        if (set != null) {
            StatusRow(Status.OK, "On the TomTom: ${"%,d".format(set.stations)} stations" + (set.type?.let { ", ${it.label}" } ?: ""),
                "Prices from ${AppState.fmt(set.updated)}." + if (i.sets.size > 1) " ${i.sets.size} fuel sets: the TomTom uses only one, so installing tidies up." else "")
        } else {
            StatusRow(Status.NONE, "No fuel prices on the TomTom yet")
        }
        Hairline()
        val missing = i.countries.filter { FuelSource.of(it) == null }.map { COUNTRY_NAMES[it] ?: it }
        if (i.sources.isEmpty()) {
            StatusRow(Status.WARN, "No open price data for this map",
                "Prices are available for ${FuelSource.entries.joinToString { it.country }}. " +
                    "This map covers ${i.countries.joinToString { COUNTRY_NAMES[it] ?: it }.ifEmpty { "unknown countries" }}.")
        } else {
            StatusRow(Status.OK, "Prices for " + i.sources.joinToString(", ") { it.country },
                "From " + i.sources.joinToString(", ") { "${it.publisher} (${it.country})" } + "." +
                    (if (missing.isNotEmpty()) " None for ${missing.joinToString(", ")}." else "") +
                    i.sources.mapNotNull { it.note }.joinToString("") { " $it." })
        }
    }
    if (i.sources.isEmpty()) return
    Spacer(Modifier.height(Space.md))
    Text("Fuel", style = MaterialTheme.typography.labelLarge, color = t.textMuted)
    Spacer(Modifier.height(Space.xs))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        FuelType.entries.forEach { f ->
            FilterChip(
                selected = state.fuelType == f, onClick = { state.changeFuelType(f) }, enabled = idle,
                label = { Text(f.label) }, shape = Radius.pill,
                leadingIcon = if (state.fuelType == f) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = t.actionSoft, selectedLabelColor = t.onActionSoft, labelColor = t.textMuted),
            )
        }
    }
    Spacer(Modifier.height(Space.md))
    PrimaryButton(if (set == null) "Install fuel prices" else "Update fuel prices", { state.installFuel() },
        enabled = idle, icon = Icons.Filled.Download, busy = state.running(Job.FUEL), busyText = "Installing…")
    JobFeedback(state, Job.FUEL)
    Spacer(Modifier.height(Space.xs))
    Text("The TomTom holds one fuel type at a time: pick it here, not on the TomTom (changing it there empties the prices until the next install). " +
        (if (i.sources.any { it == FuelSource.PRT || it == FuelSource.AUT }) "Portugal and Austria take a few minutes: their servers answer one area at a time." else ""),
        style = MaterialTheme.typography.bodySmall, color = t.textMuted)
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
            CheckRow(type in chosen, { on -> if (on) chosen.add(type) else chosen.remove(type) }, Modifier.fillMaxWidth()) {
                Text(type.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.text)
                if (type.warns) Tip("Plays a warning sound when you get close") {
                    Icon(Icons.Filled.VolumeUp, "Warning sound", tint = t.textMuted, modifier = Modifier.padding(end = Space.xs).size(18.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(Space.md))
    val countryProblem = app.mapstotom.core.countriesProblem(state.osmCountries)
    OutlinedTextField(state.osmCountries, { state.osmCountries = it }, Modifier.widthIn(max = 360.dp).fillMaxWidth(),
        label = { Text("Countries") }, isError = countryProblem != null, singleLine = true,
        trailingIcon = if (state.osmCountries.isEmpty()) null else ({
            IconButton(onClick = { state.osmCountries = "" }) { Icon(Icons.Filled.Close, "Clear countries") } }),
        supportingText = { Text(countryProblem ?: "2-letter codes separated by commas, like PT, ES") })
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
    SkipKnownControls(state, idle)
    Spacer(Modifier.height(Space.md))
    PrimaryButton(if (chosen.isEmpty()) "Install" else "Install ${chosen.size}", { state.installOsm(chosen.toList()) },
        enabled = idle && state.device != null && chosen.isNotEmpty() && countryProblem == null, icon = Icons.Filled.Download,
        busy = state.running(Job.OSM), busyText = "Installing…")
    JobFeedback(state, Job.OSM)
    Spacer(Modifier.height(Space.xs))
    Text("Data © OpenStreetMap contributors (ODbL). A whole country takes a minute or two, longer the first time it is compared with the TomTom's map.",
        style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    SkippedList(state)
}

/** The "only what the map lacks" switch, and the TomTom key it needs. */
@Composable
private fun SkipKnownControls(state: AppState, idle: Boolean) {
    val t = LocalTokens.current
    var key by remember { mutableStateOf(state.tomtomKey.orEmpty()) }
    val hasKey = key.isNotBlank()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(state.osmSkipKnown && hasKey, { state.changeOsmSkipKnown(it) }, enabled = idle && hasKey)
        Spacer(Modifier.width(Space.md))
        Text("Skip places already on the TomTom's map", style = MaterialTheme.typography.titleSmall, color = t.text)
    }
    Spacer(Modifier.height(Space.xs))
    Prose(if (hasKey)
        "Each place is checked with TomTom's online search, using your key. " +
            "LPG, drinking water and speed cameras always go in full, because TomTom has no such category. " +
            "%,d checks left today; places checked once are remembered, so installing again is free.".format(state.overlapRequestsLeft())
        else "Add your free TomTom key to skip places the TomTom's map already has. Without a key, everything is installed.")
    if (!hasKey || state.platform.carLink == null) {
        Spacer(Modifier.height(Space.sm))
        OutlinedTextField(key, { key = it.trim(); state.saveTomtomKey(key) }, Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            label = { Text("TomTom API key") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            supportingText = { Text("Free from developer.tomtom.com; the same key the car link uses for traffic") })
    }
}

/** What the last install left out as already on the map, so the user can check the matches. */
@Composable
private fun SkippedList(state: AppState) {
    val t = LocalTokens.current
    val list = state.osmSkipped
    if (list.isEmpty()) return
    var open by remember(list) { mutableStateOf(false) }
    Spacer(Modifier.height(Space.sm))
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) "Hide the ${list.size} places left out" else "Show the ${list.size} places left out as already on the map")
    }
    if (open) Panel(Modifier.widthIn(max = Space.measure), padding = PaddingValues(horizontal = Space.md, vertical = Space.xs)) {
        list.take(300).forEachIndexed { i, s ->
            if (i > 0) Hairline()
            Column(Modifier.padding(vertical = Space.xxs)) {
                Text(s.osmName, style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.category} · on the map as \"${s.tomtomName.ifBlank { "unnamed" }}\", ${s.meters} m away",
                    style = MaterialTheme.typography.bodySmall, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (list.size > 300) Text("…and ${list.size - 300} more", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    }
}

// ------------------------------------------------------------------ Google

@Composable
private fun GoogleSection(state: AppState) {
    val t = LocalTokens.current
    val idle = state.busy == null
    SectionTitle("Your Google Maps lists")
    Prose("Your starred places and saved lists, one category per list. Export them at takeout.google.com: choose only \"Saved\" and \"Maps (your places)\", then open the .zip here.")
    Spacer(Modifier.height(Space.md))
    DropZone(state, "or drop the Takeout .zip here") {
        SecondaryButton("Open Takeout file", { state.openTakeout() }, enabled = idle, icon = Icons.Filled.FolderOpen,
            busy = state.running(Job.GOOGLE) && state.takeoutLists.isEmpty(), busyText = "Reading…")
        TextButton(onClick = { state.platform.openUrl("https://takeout.google.com/settings/takeout/custom/saved,maps") }) { Text("Go to Google Takeout") }
    }
    if (state.takeoutLists.isEmpty()) { JobFeedback(state, Job.GOOGLE); return }
    val selected = remember(state.takeoutLists.toList()) { mutableStateListOf<SavedList>().apply { addAll(state.takeoutLists) } }
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
        state.takeoutLists.forEachIndexed { i, l ->
            if (i > 0) Hairline()
            CheckRow(l in selected, { on -> if (on) selected.add(l) else selected.remove(l) }, Modifier.fillMaxWidth()) {
                Text("Google " + l.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${l.rows.size}", Modifier.padding(end = Space.sm), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
            }
        }
    }
    Spacer(Modifier.height(Space.md))
    PrimaryButton(if (selected.isEmpty()) "Install" else "Install ${selected.size}", { state.installTakeout(selected.toList()) },
        enabled = idle && state.device != null && selected.isNotEmpty(), icon = Icons.Filled.Download,
        busy = state.running(Job.GOOGLE), busyText = "Installing…")
    JobFeedback(state, Job.GOOGLE)
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
    DropZone(state, "or drop a .gpi file here") {
        SecondaryButton("Open .gpi file", { state.openGpi() }, enabled = idle, icon = Icons.Filled.FolderOpen,
            busy = state.running(Job.GPI) && state.gpiFile == null, busyText = "Reading…")
    }
    val file = state.gpiFile ?: run { JobFeedback(state, Job.GPI); return }
    val keep = state.gpiFilter()
    val counts = remember(file, keep != null, state.osmCountries) { file.categories.associateWith { c -> if (keep == null) c.points.size else c.points.count(keep) } }
    val selected = remember(file) { mutableStateListOf<GpiCategory>() }
    Spacer(Modifier.height(Space.md))
    Segmented(listOf("Only in " + state.osmCountries.ifBlank { "my map's countries" }, "Whole file"),
        if (state.gpiAreaOnly) 0 else 1, { state.gpiAreaOnly = it == 0 }, enabled = idle)
    if (state.gpiAreaOnly && keep == null) Text("No known area for these countries, so the whole file is used.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    Spacer(Modifier.height(Space.sm))
    Panel(padding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
        Text(file.title, Modifier.padding(start = Space.sm, top = Space.xs), style = MaterialTheme.typography.titleSmall, color = t.text)
        file.categories.groupBy { it.group }.forEach { (group, cats) ->
            Text(group, Modifier.padding(start = Space.sm, top = Space.sm), style = MaterialTheme.typography.labelMedium, color = t.textMuted)
            cats.forEach { c ->
                val n = counts[c] ?: 0
                CheckRow(c in selected && n > 0, { on -> if (on) selected.add(c) else selected.remove(c) }, Modifier.fillMaxWidth(), enabled = n > 0) {
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
            enabled = idle && state.device != null && chosen.isNotEmpty(), icon = Icons.Filled.Download,
            busy = state.running(Job.GPI), busyText = "Installing…")
        TextButton(onClick = { selected.clear(); if (chosen.size < counts.count { it.value > 0 }) selected.addAll(file.categories.filter { (counts[it] ?: 0) > 0 }) }) {
            Text(if (chosen.size < counts.count { it.value > 0 }) "Select all" else "Select none")
        }
    }
    Spacer(Modifier.height(Space.xs))
    JobFeedback(state, Job.GPI)
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
    DropZone(state, "or drop the folder here") {
        SecondaryButton(if (state.poiSource == null) "Choose folder" else "Change folder", { state.choosePoiSource() }, enabled = idle, icon = Icons.Filled.FolderOpen)
        state.poiSource?.let {
            Text(it.displayName, Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.bodySmall, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (state.libraryCategories.isEmpty()) { JobFeedback(state, Job.FOLDER); return }
    val selected = remember(state.libraryCategories.toList()) { mutableStateListOf<PoiCategory>() }
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(horizontal = Space.sm, vertical = Space.xs)) {
        Row(Modifier.padding(horizontal = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected.clear(); selected.addAll(state.libraryCategories) }) { Text("Select all") }
            TextButton(onClick = { selected.clear() }, enabled = selected.isNotEmpty()) { Text("None") }
            Spacer(Modifier.weight(1f))
            PrimaryButton(if (selected.isEmpty()) "Install" else "Install ${selected.size}",
                { state.installPois(selected.toList()) }, enabled = idle && state.device != null && selected.isNotEmpty(),
                busy = state.running(Job.FOLDER), busyText = "Installing…")
        }
        state.libraryCategories.forEach { c ->
            Hairline()
            CheckRow(c in selected, { on -> if (on) selected.add(c) else selected.remove(c) }, Modifier.fillMaxWidth()) {
                Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.bmp != null) Tip("Has its own icon") { Icon(Icons.Filled.Image, "Has icon", tint = t.textMuted, modifier = Modifier.size(16.dp)) }
                if (c.ogg != null) { Spacer(Modifier.width(Space.xs)); Tip("Has a warning sound") { Icon(Icons.Filled.VolumeUp, "Has sound", tint = t.textMuted, modifier = Modifier.size(16.dp)) } }
                Text("${c.count}", Modifier.width(56.dp).padding(end = Space.sm), style = MaterialTheme.typography.bodySmall, color = t.textMuted,
                    textAlign = TextAlign.End)
            }
        }
    }
    JobFeedback(state, Job.FOLDER)
}

// ------------------------------------------------------------------ on the TomTom

private fun yearOf(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).year }

@Composable
private fun OnDevice(state: AppState, wideHint: Boolean, onDelete: (PoiCategory) -> Unit) {
    val t = LocalTokens.current
    val d = state.device
    SectionTitle("On the TomTom")
    if (d == null) { NeedDevice(state); return }
    val load by produceLoad(d, state.gen) { state.deviceCategories() }
    val cats = when (val l = load) {
        Load.Loading -> { Panel(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) { SkeletonRows(4) }; return }
        is Load.Failed -> { LoadFailed(state, "the POI categories", l); return }
        is Load.Ready -> l.value
    }
    var open by remember(d) { mutableStateOf<String?>(null) }
    val now = java.time.Year.now().value
    val old = cats.count { c -> yearOf(c.modified)?.let { it <= now - 3 } == true }
    val dup = cats.count { builtInEquivalent(it.name) != null }
    Prose("${cats.size} categories in ${d.mapFolder ?: "no map folder"}." +
        (if (old > 0) " $old are 3 years old or more." else "") +
        (if (dup > 0) " $dup look like categories your map already has, so they can usually go." else ""))
    JobFeedback(state, Job.ON_DEVICE)
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(start = Space.lg, end = Space.xs, top = Space.xs, bottom = Space.xs)) {
        if (cats.isEmpty()) EmptyState(Icons.Filled.Place, "No POI categories yet",
            "Install some from the sections " + (if (wideHint) "on the left" else "above") + ". They show up here to check, rename and delete.")
        cats.forEachIndexed { i, c ->
            if (i > 0) Hairline()
            val year = yearOf(c.modified)
            val also = builtInEquivalent(c.name)
            Row(Modifier.fillMaxWidth().clickable(enabled = c.editable) { open = if (open == c.ov2) null else c.ov2 }.padding(vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val bits = listOfNotNull(
                        when {
                            c.fpf != null -> "fuel prices, ${c.count} stations"
                            c.isProtected -> "protected (TomTom service)"
                            else -> "${c.count} places" + if (c.editable) "" else ", read-only"
                        },
                        year?.toString(),
                        also?.let { "map has \"$it\"" },
                    )
                    Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall,
                        color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!c.editable) Tip(if (c.isProtected) "Protected by TomTom: it can be deleted, not edited" else "Can't be edited here") {
                    Icon(Icons.Filled.Lock, "Protected", tint = t.textMuted, modifier = Modifier.padding(horizontal = Space.xs).size(18.dp))
                }
                else Icon(if (open == c.ov2) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open == c.ov2) "Hide places" else "Show places", tint = t.textMuted)
                TipIconButton("Delete category", { onDelete(c) }, enabled = state.busy == null) {
                    Icon(Icons.Filled.DeleteOutline, "Delete ${c.name}", tint = if (state.busy == null) t.danger else t.textMuted)
                }
            }
            if (open == c.ov2) Places(state, c)
        }
    }
}

/** The places in one category, with a filter, rename and delete. */
@Composable
private fun Places(state: AppState, c: PoiCategory) {
    val t = LocalTokens.current
    val load by produceLoad(c.ov2, state.gen) { state.poiEntries(c) ?: error("the file is gone") }
    val list = when (val l = load) {
        Load.Loading -> { SkeletonRows(3, Modifier.padding(horizontal = Space.sm)); return }
        is Load.Failed -> { LoadFailed(state, "these places", l, Modifier.padding(Space.sm)); return }
        is Load.Ready -> l.value
    }
    var filter by remember(c.ov2) { mutableStateOf("") }
    var editing by remember(c.ov2) { mutableStateOf<Ov2Entry?>(null) }
    var confirm by remember(c.ov2) { mutableStateOf<Ov2Entry?>(null) }
    val shown = list.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
    Column(Modifier.padding(start = Space.sm, end = Space.sm, bottom = Space.sm)) {
        if (list.size > 8) SearchField(filter, { filter = it }, "Filter ${list.size} places", Modifier.fillMaxWidth())
        shown.take(100).forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (editing === e) {
                    var name by remember(e) { mutableStateOf(e.name) }
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { state.renamePoi(c, e, name); editing = null }))
                    TextButton(onClick = { state.renamePoi(c, e, name); editing = null }) { Text("Save") }
                } else {
                    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val hovered by hover.collectIsHoveredAsState()
                    Column(Modifier.weight(1f).hoverable(hover).clickable(onClickLabel = "Rename") { editing = e }.padding(vertical = Space.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.name.ifEmpty { "(no name)" }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            EditHint(hovered || state.platform.isAndroid)
                        }
                        Text("%.5f, %.5f".format(java.util.Locale.ROOT, e.lat, e.lon), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
                    }
                    TipIconButton("Delete place", { confirm = e }, enabled = state.busy == null) {
                        Icon(Icons.Filled.Close, "Delete ${e.name}", tint = if (state.busy == null) t.danger else t.textMuted)
                    }
                }
            }
        }
        if (shown.isEmpty() && filter.isNotBlank()) Text("No place matches \"$filter\".", Modifier.padding(vertical = Space.sm), style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        if (shown.size > 100) Text("Showing 100 of ${shown.size}. Type to narrow down.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        Text((if (state.platform.isAndroid) "Tap" else "Click") + " a name to rename it.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    }
    confirm?.let { e ->
        ConfirmDanger("Delete \"${e.name}\"?", "It is removed from ${c.name} on the TomTom. The first change keeps a copy of the file.", "Delete",
            onConfirm = { state.deletePoi(c, e); confirm = null }, onDismiss = { confirm = null })
    }
}

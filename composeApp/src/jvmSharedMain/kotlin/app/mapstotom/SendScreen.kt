package app.mapstotom

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mapstotom.core.ITN_MAX
import app.mapstotom.core.Point
import app.mapstotom.ui.*

@Composable
fun ColumnScope.SendScreen(state: AppState, wide: Boolean) {
    val t = LocalTokens.current
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }
    val idle = state.busy == null

    ScreenHeader(
        "Send to TomTom",
        if (state.platform.isAndroid) "In Google Maps, tap Share and pick MapsToTom. Or paste a link, coordinates or an address."
        else "Copy a link in Google Maps (Share, then Copy link), or paste coordinates or an address.",
    )

    // ---- input: the first thing to do on this screen
    Panel {
        OutlinedTextField(
            input, { input = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
            placeholder = { Text("https://maps.app.goo.gl/…   or   38.6916, -9.2160   or   Praça do Comércio, Lisboa") },
            shape = Radius.control,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = t.borderStrong, focusedBorderColor = t.action,
                unfocusedContainerColor = t.surface, focusedContainerColor = t.surface),
        )
        Spacer(Modifier.height(Space.md))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            PrimaryButton("Add", { state.addText(input) { ok -> if (ok) input = "" } }, enabled = input.isNotBlank() && idle, icon = Icons.Filled.Add)
            SecondaryButton("Paste and add", { state.platform.clipboardText()?.let { state.addText(it) } }, enabled = idle, icon = Icons.Filled.ContentPaste)
            TextButton(onClick = { state.platform.openUrl("https://www.google.com/maps") }) {
                Icon(Icons.Filled.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.xs)); Text("Open Google Maps")
            }
            TextButton(onClick = { state.openItn() }) { Text("Open .itn") }
        }
        if (!state.platform.isAndroid) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.xs)) {
            Checkbox(state.watchClipboard, { state.watchClipboard = it })
            Text("Add Google Maps links as soon as I copy them", style = MaterialTheme.typography.bodyMedium, color = t.text)
        }
    }

    if (state.warnings.isNotEmpty()) {
        Spacer(Modifier.height(Space.md))
        Panel(padding = PaddingValues(horizontal = Space.lg, vertical = Space.sm)) {
            state.warnings.forEach { w ->
                Row(Modifier.padding(vertical = Space.xs), verticalAlignment = Alignment.Top) {
                    Icon(statusIcon(Status.WARN), null, tint = t.warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.sm))
                    Prose(w, muted = false)
                }
            }
        }
    }

    // ---- the list
    Spacer(Modifier.height(Space.xxl))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Route", style = MaterialTheme.typography.headlineSmall, color = t.text)
        Spacer(Modifier.width(Space.md))
        Text(if (state.points.isEmpty()) "empty" else "${state.points.size} point" + if (state.points.size == 1) "" else "s",
            style = MaterialTheme.typography.bodyMedium, color = t.textMuted, modifier = Modifier.weight(1f))
        if (state.points.isNotEmpty()) DangerTextButton("Clear all", { state.points.clear(); state.warnings.clear() })
    }
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
        if (state.points.isEmpty()) {
            Text("Nothing here yet. Add a place or a route above; it shows up here to reorder and rename before saving.",
                Modifier.padding(vertical = Space.lg).widthIn(max = Space.measure), style = MaterialTheme.typography.bodyMedium, color = t.textMuted)
        }
        state.points.forEachIndexed { i, p ->
            if (i > 0) Hairline()
            PointRow(i, p, state, compact = !wide, onRename = { editing = i })
        }
    }

    // ---- save
    if (state.points.isNotEmpty()) {
        Spacer(Modifier.height(Space.xxl))
        Text("Save", style = MaterialTheme.typography.headlineSmall, color = t.text)
        Spacer(Modifier.height(Space.md))
        SavePanel(state, wide)
    }

    editing?.let { i ->
        if (i in state.points.indices) RenameDialog(state.points[i].name, onDone = { name -> name?.let { state.rename(i, it) }; editing = null })
        else editing = null
    }
}

@Composable
private fun PointRow(i: Int, p: Point, state: AppState, compact: Boolean, onRename: () -> Unit) {
    val t = LocalTokens.current
    val stop = p.kind == Point.Kind.STOP
    val controls: @Composable RowScope.() -> Unit = {
        FilterChip(
            selected = stop, onClick = { state.toggleKind(i) },
            label = { Text(if (stop) "Stop" else "Pass through") },
            shape = Radius.pill,
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = t.actionSoft, selectedLabelColor = t.onActionSoft, labelColor = t.textMuted),
        )
        Spacer(Modifier.width(Space.xs))
        IconButton(onClick = { state.move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
        IconButton(onClick = { state.move(i, 1) }, enabled = i < state.points.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
        IconButton(onClick = { state.points.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove", tint = t.danger) }
    }
    val label: @Composable (Modifier) -> Unit = { m ->
        Column(m.clickable(onClick = onRename).padding(vertical = Space.xs)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.label(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = t.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val number: @Composable () -> Unit = {
        Text("%2d".format(i + 1), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            color = t.textMuted, modifier = Modifier.width(32.dp))
    }
    if (compact) {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) { number(); label(Modifier.weight(1f)) }
            Row(Modifier.fillMaxWidth().padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f)); controls()
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            number(); label(Modifier.weight(1f)); controls()
        }
    }
}

@Composable
private fun SavePanel(state: AppState, wide: Boolean) {
    val t = LocalTokens.current
    val ready = state.device != null && state.busy == null
    Panel {
        Overline("As a route")
        Spacer(Modifier.height(Space.sm))
        OutlinedTextField(state.routeName, { state.routeName = it }, Modifier.widthIn(max = Space.measure).fillMaxWidth(),
            singleLine = true, label = { Text("Route name") }, shape = Radius.control)
        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.firstIsDeparture, { state.firstIsDeparture = it })
            Text("First point is where I start", style = MaterialTheme.typography.bodyMedium, color = t.text)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.followRoads, { state.followRoads = it })
            Text("Follow Google's roads, using up to ", style = MaterialTheme.typography.bodyMedium, color = t.text)
            OutlinedTextField(state.shapeTotal.toString(), { v -> v.toIntOrNull()?.let { state.shapeTotal = it.coerceIn(2, 255) } },
                Modifier.width(76.dp), singleLine = true, enabled = state.followRoads, shape = Radius.control)
            Text(" points", style = MaterialTheme.typography.bodyMedium, color = t.text)
        }
        if (state.followRoads && state.shapeTotal > ITN_MAX) Row(Modifier.padding(start = Space.md, top = Space.xs)) {
            Icon(statusIcon(Status.WARN), null, tint = t.warn, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Space.xs))
            Text("Older TomToms load at most $ITN_MAX points.", style = MaterialTheme.typography.bodySmall, color = t.text)
        }
        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PrimaryButton("Save route to TomTom", { state.saveRoute() }, enabled = ready && state.points.size >= 2, icon = Icons.Filled.Route)
            SecondaryButton("Export .itn", { state.exportItn() }, enabled = state.points.size >= 2)
        }

        Spacer(Modifier.height(Space.lg)); Hairline(); Spacer(Modifier.height(Space.lg))

        Overline("As places (POIs)")
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedTextField(state.poiCategory, { state.poiCategory = it }, Modifier.widthIn(max = 360.dp).weight(1f, fill = false),
                singleLine = true, label = { Text("Category") }, shape = Radius.control)
            SecondaryButton("Save as POIs", { state.savePois() }, enabled = ready, icon = Icons.Filled.Place)
        }
        if (state.device == null) {
            Spacer(Modifier.height(Space.md))
            Text("Connect a TomTom to save. Export works without one.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = LocalTokens.current.surface,
        shape = Radius.panel,
        title = { Text("Rename", style = MaterialTheme.typography.titleMedium) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, shape = Radius.control) },
        confirmButton = { PrimaryButton("Rename", { onDone(text.trim().ifEmpty { current }) }) },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

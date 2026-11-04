package app.mapstotom

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
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
import app.mapstotom.core.Avoid
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
        if (state.platform.isAndroid) "In Google Maps, tap Share and pick MapsToTom. Or paste a Google Maps or ViaMichelin link, coordinates or an address."
        else "Copy a link in Google Maps (Share, then Copy link) or a ViaMichelin route's address, or paste coordinates or an address.",
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
        }
        Spacer(Modifier.height(Space.sm))
        DropZone(state, "or drop an .itn or .gpx file here") {
            SecondaryButton("Open .itn or .gpx", { state.openRouteFile() }, enabled = idle, icon = Icons.Filled.FolderOpen)
        }
        if (!state.platform.isAndroid) CheckRow(state.watchClipboard, { state.watchClipboard = it },
            "Add Google Maps and ViaMichelin links as soon as I copy them", Modifier.padding(top = Space.xs).offset(x = -Space.md))
    }
    JobFeedback(state, Job.SEND_INPUT)

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
        if (state.points.isNotEmpty()) DangerTextButton("Clear all", { state.clearPoints() }, enabled = idle)
    }
    Spacer(Modifier.height(Space.md))
    Panel(padding = PaddingValues(horizontal = Space.lg, vertical = Space.xs)) {
        if (state.points.isEmpty()) EmptyState(Icons.Filled.Route, "No points yet",
            "Add a place or a route above. It shows up here to reorder and rename before saving.")
        state.points.forEachIndexed { i, p ->
            if (i > 0) Hairline()
            PointRow(i, p, state, compact = !wide, idle = idle, onRename = { editing = i })
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
private fun PointRow(i: Int, p: Point, state: AppState, compact: Boolean, idle: Boolean, onRename: () -> Unit) {
    val t = LocalTokens.current
    val stop = p.kind == Point.Kind.STOP
    val kind: @Composable () -> Unit = {
        Tip("Stop: the TomTom announces it as a waypoint. Pass through: only steers the route that way.") {
            Segmented(listOf("Stop", "Pass through"), if (stop) 0 else 1, { if ((it == 0) != stop) state.toggleKind(i) }, enabled = idle)
        }
    }
    val buttons: @Composable RowScope.() -> Unit = {
        TipIconButton("Move up", { state.move(i, -1) }, enabled = idle && i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
        TipIconButton("Move down", { state.move(i, 1) }, enabled = idle && i < state.points.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
        TipIconButton("Remove", { state.points.removeAt(i) }, enabled = idle) { Icon(Icons.Filled.Close, "Remove", tint = if (idle) t.danger else t.textMuted) }
    }
    val label: @Composable (Modifier) -> Unit = { m ->
        val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val hovered by hover.collectIsHoveredAsState()
        Column(m.hoverable(hover).clickable(enabled = idle, onClickLabel = "Rename", onClick = onRename).padding(vertical = Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                EditHint(hovered || state.platform.isAndroid)
            }
            Text(p.label(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = t.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val number: @Composable () -> Unit = {
        Text("%2d".format(i + 1), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            color = t.textMuted, modifier = Modifier.width(32.dp))
    }
    if (compact) {
        // Narrow: move and remove on the name's line, Stop / Pass through under it.
        Column(Modifier.fillMaxWidth().padding(vertical = Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) { number(); label(Modifier.weight(1f)); buttons() }
            Row(Modifier.padding(start = 32.dp, top = Space.xs)) { kind() }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            number(); label(Modifier.weight(1f)); kind(); Spacer(Modifier.width(Space.xs)); buttons()
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
        CheckRow(state.firstIsDeparture, { state.firstIsDeparture = it }, "First point is where I start", Modifier.offset(x = -Space.md))
        CheckRow(state.followRoads, { state.followRoads = it }, "Follow the same roads", Modifier.offset(x = -Space.md))
        Row(Modifier.padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            val c = if (state.followRoads) t.text else t.textMuted
            Text("using up to ", style = MaterialTheme.typography.bodyMedium, color = c)
            PointsField(state)
            Text(" points", style = MaterialTheme.typography.bodyMedium, color = c)
        }
        if (state.avoid.any) Row(Modifier.padding(start = Space.md, top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon(Status.OK), null, tint = t.ok, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Space.xs))
            Text("Avoiding ${state.avoid.label()} (from the ViaMichelin link)", style = MaterialTheme.typography.bodySmall, color = t.text)
            TextButton(onClick = { state.avoid = Avoid() }) { Text("Don't avoid") }
        }
        if (state.followRoads && state.shapeTotal > ITN_MAX) Row(Modifier.padding(start = Space.md, top = Space.xs)) {
            Icon(statusIcon(Status.WARN), null, tint = t.warn, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Space.xs))
            Text("Older TomToms load at most $ITN_MAX points.", style = MaterialTheme.typography.bodySmall, color = t.text)
        }
        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PrimaryButton("Save route to TomTom", { state.saveRoute() }, enabled = ready && state.points.size >= 2, icon = Icons.Filled.Route,
                busy = state.running(Job.ROUTE), busyText = "Saving…")
            SecondaryButton("Export .itn", { state.exportItn() }, enabled = state.busy == null && state.points.size >= 2)
        }
        JobFeedback(state, Job.ROUTE)

        Spacer(Modifier.height(Space.lg)); Hairline(); Spacer(Modifier.height(Space.lg))

        Overline("As places (POIs)")
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedTextField(state.poiCategory, { state.poiCategory = it }, Modifier.widthIn(max = 360.dp).weight(1f, fill = false),
                singleLine = true, label = { Text("Category") }, shape = Radius.control)
            SecondaryButton("Save as POIs", { state.savePois() }, enabled = ready, icon = Icons.Filled.Place,
                busy = state.running(Job.SEND_POIS), busyText = "Saving…")
        }
        JobFeedback(state, Job.SEND_POIS)
        if (state.device == null) {
            Spacer(Modifier.height(Space.md))
            Text("Connect a TomTom to save. Export works without one.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val confirm = { onDone(text.trim().ifEmpty { current }) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = LocalTokens.current.surface,
        shape = Radius.panel,
        title = { Text("Rename", style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, shape = Radius.control,
                // Enter renames, on a desktop keyboard and on the phone's (Done).
                modifier = Modifier.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { confirm(); true } else false
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { confirm() }))
        },
        confirmButton = { PrimaryButton("Rename", confirm) },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

/** The "up to N points" box: typing is free, the number is saved once it is 2–255 and tidied when the box is left. */
@Composable
private fun PointsField(state: AppState) {
    var text by remember { mutableStateOf(state.shapeTotal.toString()) }
    val n = text.toIntOrNull()
    val ok = n != null && n in 2..255
    OutlinedTextField(text, { v ->
            text = v.filter(Char::isDigit).take(3)
            text.toIntOrNull()?.takeIf { it in 2..255 }?.let { state.shapeTotal = it }
        },
        Modifier.width(76.dp).onFocusChanged { if (!it.isFocused) text = state.shapeTotal.toString() },
        singleLine = true, enabled = state.followRoads, shape = Radius.control, isError = state.followRoads && !ok,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}

/** A small pencil next to a name that opens rename: shown on hover (always on a touch screen). */
@Composable
fun EditHint(visible: Boolean) {
    Box(Modifier.padding(start = Space.xs).size(16.dp)) {
        if (visible) Icon(Icons.Filled.Edit, null, tint = LocalTokens.current.textMuted, modifier = Modifier.size(14.dp).align(Alignment.Center))
    }
}

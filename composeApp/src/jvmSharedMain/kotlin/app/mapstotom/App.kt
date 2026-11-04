package app.mapstotom

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mapstotom.ui.*

enum class Tab(val label: String, val icon: ImageVector) {
    SEND("Send", Icons.Filled.Send),
    GPS("GPS", Icons.Filled.SatelliteAlt),
    POIS("POIs", Icons.Filled.Place),
    DEVICE("Device", Icons.Filled.Tune),
    CAR("Car", Icons.Filled.DirectionsCar),
}

@Composable
fun App(state: AppState, dark: Boolean = false, startTab: Tab = Tab.SEND) {
    // [dark] is the system's setting; the user's Auto/Light/Dark choice decides.
    MapsToTomTheme(state.themeMode.isDark(dark), state.accent) {
        val t = LocalTokens.current
        var tab by remember { mutableStateOf(startTab) }
        val tabs = remember { Tab.entries.filter { it != Tab.CAR || state.platform.carLink != null } }
        LaunchedEffect(Unit) { if (state.device == null) state.detect() }
        BoxWithConstraints(Modifier.fillMaxSize().background(t.page)) {
            val wide = maxWidth >= 720.dp
            val gutter = if (wide) Space.xxl else Space.lg
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    NavigationRail(containerColor = t.page, modifier = Modifier.fillMaxHeight()) {
                        Spacer(Modifier.height(Space.lg))
                        tabs.forEach { item ->
                            NavigationRailItem(
                                selected = tab == item, onClick = { tab = item },
                                icon = { Icon(item.icon, null) }, label = { Text(item.label) },
                                colors = NavigationRailItemDefaults.colors(indicatorColor = t.actionSoft,
                                    selectedIconColor = t.onActionSoft, selectedTextColor = t.text,
                                    unselectedIconColor = t.textMuted, unselectedTextColor = t.textMuted),
                            )
                            Spacer(Modifier.height(Space.xs))
                        }
                    }
                    VerticalDivider(color = t.hairline)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    DeviceStrip(state, gutter)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val inner: @Composable ColumnScope.() -> Unit = {
                            when (tab) {
                                Tab.SEND -> SendScreen(state, wide)
                                Tab.GPS -> GpsScreen(state)
                                Tab.POIS -> PoiScreen(state, wide)
                                Tab.DEVICE -> DeviceScreen(state)
                                Tab.CAR -> CarScreen(state)
                            }
                        }
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(start = gutter, end = gutter, top = Space.xl, bottom = Space.xxxl),
                        ) {
                            Column(Modifier.widthIn(max = Space.column), content = inner)
                        }
                    }
                    StatusLine(state, gutter)
                    if (!wide) {
                        NavigationBar(containerColor = t.surface, tonalElevation = 0.dp) {
                            tabs.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item, onClick = { tab = item },
                                    icon = { Icon(item.icon, null) }, label = { Text(item.label) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = t.actionSoft,
                                        selectedIconColor = t.onActionSoft, selectedTextColor = t.text,
                                        unselectedIconColor = t.textMuted, unselectedTextColor = t.textMuted),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Which TomTom is in use, kept quiet: a connection chip, one line, one action. */
@Composable
private fun DeviceStrip(state: AppState, gutter: androidx.compose.ui.unit.Dp) {
    val t = LocalTokens.current
    val d = state.device
    val looking = state.busyKey == Job.DETECT
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 480.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            // On a phone the chip shrinks to its icon when connected, so the TomTom's name has room.
            ConnectionChip(if (looking) Connection.LOOKING else if (d != null) Connection.ON else Connection.OFF, iconOnly = narrow && d != null && !looking)
            Spacer(Modifier.width(Space.md))
            Text(
                if (d != null) d.model + "  ·  " + listOfNotNull(d.navcore?.let { "NavCore $it" }, d.mapFolder).joinToString("  ·  ")
                else "",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (d != null) t.text else t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (d != null && state.platform.canEject(d.fs)) TextButton(onClick = { state.eject() }, enabled = state.busy == null) { Text("Eject") }
            else {
                TextButton(onClick = { state.detect() }, enabled = state.busy == null) { Text("Detect") }
                TextButton(onClick = { state.chooseDevice() }, enabled = state.busy == null) { Text(if (d == null) "Choose drive" else "Change") }
            }
        }
        }
        Hairline()
        if (state.ejected && d == null) Banner(gutter) {
            StatusNote(Status.OK, "Safe to unplug the TomTom." +
                if (state.restartPending) " Then restart it to use the changes: hold the power button about 15 seconds." else "",
                icon = Icons.Filled.Eject)
        } else if (state.restartPending) Banner(gutter) {
            StatusNote(Status.WARN, "Restart the TomTom to use the changes: hold the power button about 15 seconds, until it starts again. " +
                "Waking it from standby is not enough.", icon = Icons.Filled.PowerSettingsNew) {
                TextButton(onClick = { state.restartDone() }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Done, it restarted") }
            }
        }
    }
}

@Composable
private fun Banner(gutter: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Space.sm)) { content() }
    Hairline()
}

private enum class Connection { ON, LOOKING, OFF }

/** "Connected" / "Looking…" / "Not connected" as a pill: icon plus words, never colour alone. */
@Composable
private fun ConnectionChip(c: Connection, iconOnly: Boolean = false) {
    val t = LocalTokens.current
    val (bg, fg) = when (c) { Connection.ON -> t.actionSoft to t.onActionSoft; else -> t.sunken to t.textMuted }
    Row(Modifier.background(bg, Radius.pill).padding(horizontal = if (iconOnly) Space.xs + Space.xxs else Space.md, vertical = Space.xs)
        .semantics(mergeDescendants = true) { if (iconOnly) contentDescription = "Connected" },
        verticalAlignment = Alignment.CenterVertically) {
        when (c) {
            Connection.ON -> Icon(Icons.Filled.CheckCircle, null, tint = t.ok, modifier = Modifier.size(16.dp))
            Connection.LOOKING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = t.action)
            Connection.OFF -> Icon(Icons.Filled.UsbOff, null, tint = t.textMuted, modifier = Modifier.size(16.dp))
        }
        if (iconOnly) return@Row
        Spacer(Modifier.width(Space.xs + Space.xxs))
        Text(when (c) { Connection.ON -> "Connected"; Connection.LOOKING -> "Looking…"; Connection.OFF -> "Not connected" },
            style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Result of the last action, or what is running now, with numbers and the time left. */
@Composable
private fun StatusLine(state: AppState, gutter: androidx.compose.ui.unit.Dp) {
    val t = LocalTokens.current
    // Re-read the clock now and then, so "about 3 min left" counts down.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(state.progress != null) { while (state.progress != null) { kotlinx.coroutines.delay(2000); tick++ } }
    Column(Modifier.background(t.surface)) {
        Hairline()
        val p = state.progress
        if (p != null) LinearProgressIndicator(progress = { if (p.second == 0) 0f else p.first / p.second.toFloat() }, Modifier.fillMaxWidth(),
            color = t.action, trackColor = t.sunken, drawStopIndicator = {})
        else if (state.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.action, trackColor = t.sunken)
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            when {
                state.busy != null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.action)
                state.statusIsError -> Icon(statusIcon(Status.BAD), null, tint = t.danger, modifier = Modifier.size(16.dp))
                else -> Icon(Icons.Filled.Info, null, tint = t.textMuted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(Space.sm))
            Text(state.busy ?: state.status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (state.statusIsError && state.busy == null) t.danger else t.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val right = if (state.busy != null && tick >= 0) p?.let { (i, n) ->
                listOfNotNull("%,d of %,d %s".format(i, n, state.progressUnit).trim(), state.timeLeft()).joinToString("  ·  ")
            } else state.statusTime?.let { clock(it) }
            if (right != null) {
                Spacer(Modifier.width(Space.md))
                Text(right, style = MaterialTheme.typography.bodySmall, color = t.textMuted, maxLines = 1)
            }
        }
    }
}

/** The steps and the result of the job started in this section, shown under its button. */
@Composable
fun JobFeedback(state: AppState, key: String, modifier: Modifier = Modifier) {
    val steps = state.steps.takeIf { state.stepsKey == key && it.isNotEmpty() }
    val note = state.note?.takeIf { it.key == key && state.busyKey != key }
    if (steps == null && note == null) return
    Column(modifier.padding(top = Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (steps != null) StepList(steps)
        if (note != null) StatusNote(if (note.error) Status.BAD else Status.OK, note.text, time = note.time,
            onClose = { state.dismissNote(key) })
    }
}

/** True while the job of this section runs (for its button's spinner). */
fun AppState.running(key: String) = busyKey == key

/**
 * Desktop: a dashed area around a section's open button that takes files dropped from the file manager.
 * Android has no dragging from a file manager, so there it is just the button.
 */
@Composable
fun DropZone(state: AppState, hint: String, content: @Composable RowScope.() -> Unit) {
    val t = LocalTokens.current
    var over by remember { mutableStateOf(false) }
    val drop = state.platform.fileDrop({ state.openDropped(it) }, { over = it })
    if (drop == null) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm), content = content); return }
    val edge = if (over) t.action else t.borderStrong
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        drop.fillMaxWidth()
            .background(if (over) t.actionSoft else t.page, Radius.panel)
            .drawBehind {
                drawRoundRect(edge, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
            }
            .padding(horizontal = Space.lg, vertical = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm), content = content)
        Row(Modifier.align(Alignment.CenterVertically).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FileDownload, null, tint = t.textMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.xs))
            Text(if (over) "Drop to open" else hint, style = MaterialTheme.typography.bodySmall, color = if (over) t.onActionSoft else t.textMuted)
        }
    }
}

/** Shown on screens that need a TomTom when none is connected. */
@Composable
fun NeedDevice(state: AppState) {
    Panel {
        StatusRow(Status.NONE, "No TomTom connected",
            if (state.platform.isAndroid) "Plug the TomTom in with a USB OTG adapter and press Detect. Rooted tablets mount it directly; otherwise choose its drive."
            else "Plug the TomTom in by USB. It is found by itself, or choose its drive.") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PrimaryButton("Detect", { state.detect() }, enabled = state.busy == null)
                SecondaryButton("Choose drive", { state.chooseDevice() }, enabled = state.busy == null)
            }
        }
    }
}

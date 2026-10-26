package app.mapstotom

import androidx.compose.foundation.background
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
}

@Composable
fun App(state: AppState, dark: Boolean = false, startTab: Tab = Tab.SEND) {
    MapsToTomTheme(dark) {
        val t = LocalTokens.current
        var tab by remember { mutableStateOf(startTab) }
        LaunchedEffect(Unit) { if (state.device == null) state.detect() }
        BoxWithConstraints(Modifier.fillMaxSize().background(t.page)) {
            val wide = maxWidth >= 720.dp
            val gutter = if (wide) Space.xxl else Space.lg
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    NavigationRail(containerColor = t.page, modifier = Modifier.fillMaxHeight()) {
                        Spacer(Modifier.height(Space.lg))
                        Tab.entries.forEach { item ->
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
                            Tab.entries.forEach { item ->
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

/** Which TomTom is in use, kept quiet: one line, one action. */
@Composable
private fun DeviceStrip(state: AppState, gutter: androidx.compose.ui.unit.Dp) {
    val t = LocalTokens.current
    val d = state.device
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (d != null) Icons.Filled.CheckCircle else Icons.Filled.UsbOff, null,
                tint = if (d != null) t.ok else t.textMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.sm))
            Text(
                if (d != null) d.model + "  ·  " + listOfNotNull(d.navcore?.let { "NavCore $it" }, d.mapFolder).joinToString("  ·  ")
                else "No TomTom connected",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (d != null) t.text else t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (d != null && state.platform.canEject(d.fs)) TextButton(onClick = { state.eject() }, enabled = state.busy == null) { Text("Eject") }
            else {
                TextButton(onClick = { state.detect() }, enabled = state.busy == null) { Text("Detect") }
                TextButton(onClick = { state.chooseDevice() }, enabled = state.busy == null) { Text(if (d == null) "Choose drive" else "Change") }
            }
        }
        Hairline()
    }
}

/** Result of the last action, or what is running now. */
@Composable
private fun StatusLine(state: AppState, gutter: androidx.compose.ui.unit.Dp) {
    val t = LocalTokens.current
    Column(Modifier.background(t.surface)) {
        Hairline()
        state.progress?.let { (i, n) ->
            LinearProgressIndicator(progress = { if (n == 0) 0f else i / n.toFloat() }, Modifier.fillMaxWidth(),
                color = t.action, trackColor = t.sunken)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            when {
                state.busy != null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.action)
                state.statusIsError -> Icon(statusIcon(Status.BAD), null, tint = t.danger, modifier = Modifier.size(16.dp))
                else -> Icon(Icons.Filled.Info, null, tint = t.textMuted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(Space.sm))
            Text(state.busy ?: state.status, style = MaterialTheme.typography.bodySmall,
                color = if (state.statusIsError && state.busy == null) t.danger else t.textMuted)
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

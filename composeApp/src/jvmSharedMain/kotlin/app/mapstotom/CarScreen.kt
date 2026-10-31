package app.mapstotom

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mapstotom.link.CarLinkSettings
import app.mapstotom.link.CarLinkState
import app.mapstotom.link.CarLinkState.Phase
import app.mapstotom.ui.*

// ------------------------------------------------------------------ Car link

@Composable
fun ColumnScope.CarScreen(state: AppState) {
    val t = LocalTokens.current
    val link = state.platform.carLink ?: return
    val phase by CarLinkState.phase.collectAsState()
    val log by CarLinkState.log.collectAsState()
    val requests by CarLinkState.requests.collectAsState()
    var key by remember { mutableStateOf(state.platform.loadSetting(CarLinkSettings.TRAFFIC_KEY).orEmpty()) }

    ScreenHeader("Car link", "Live services in the car through this phone")
    Prose("The TomTom connects to this phone over Bluetooth as if it were its mobile phone, and the app answers its Live " +
        "services itself: traffic, weather, fuel prices. Nothing to host; the phone's mobile data is used, a few KB per update. " +
        "Fuel prices come for the set installed from this phone (POIs tab), refreshed from the same sources.")

    Spacer(Modifier.height(Space.xl))
    Panel(Modifier.widthIn(max = Space.measure)) {
        Fact("Status", phase.label, when (phase) { Phase.ONLINE -> Status.OK; Phase.OFF -> null; else -> Status.WARN })
        if (phase != Phase.OFF) Fact("Requests answered", "$requests")
        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (phase == Phase.OFF) PrimaryButton("Start", { link.start() }, icon = Icons.Filled.Bluetooth)
            else SecondaryButton("Stop", { link.stop() }, icon = Icons.Filled.BluetoothDisabled)
            SecondaryButton("Make phone visible", { link.makeVisible() })
        }
    }

    Spacer(Modifier.height(Space.xxl))
    Text("Traffic", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.sm))
    Prose("Traffic comes from TomTom's own traffic service with your free key from developer.tomtom.com " +
        "(2,500 requests a day; a drive uses about 15 an hour). Without a key the TomTom gets no incidents.")
    Spacer(Modifier.height(Space.md))
    OutlinedTextField(key, { key = it.trim(); state.platform.saveSetting(CarLinkSettings.TRAFFIC_KEY, key.ifEmpty { null }) },
        Modifier.widthIn(max = 420.dp).fillMaxWidth(), label = { Text("TomTom API key") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())

    Spacer(Modifier.height(Space.xxl))
    Text("First time", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.sm))
    Prose("1. The TomTom's card needs the patched NavCore for the link and its discovery files pointing at 192.168.77.1:8080 (set up once by cable).\n" +
        "2. Press Start, then Make phone visible.\n" +
        "3. On the TomTom: Phone menu, find phones, pick this phone and use PIN 0000 here when asked.\n" +
        "4. Keep Bluetooth on. Leave the link running; Android shows a notification while it is on.")

    if (log.isNotEmpty()) {
        Spacer(Modifier.height(Space.xxl))
        Text("Log", style = MaterialTheme.typography.headlineSmall, color = t.text)
        Spacer(Modifier.height(Space.sm))
        Panel(Modifier.fillMaxWidth()) {
            log.takeLast(40).asReversed().forEach {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = t.textMuted)
            }
        }
    }
}

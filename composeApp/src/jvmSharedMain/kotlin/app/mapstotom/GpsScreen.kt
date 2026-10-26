package app.mapstotom

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mapstotom.core.*
import app.mapstotom.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private class GpsView(
    val quickFix: QuickFix,
    val ttconv: TtconvState,
    val glgps: GlgpsState,
    val clockFix: Boolean,
    val glttDebug: Boolean,
    val clockLooksWrong: Boolean,
)

private class Check(val status: Status, val title: String, val detail: String)

@Composable
fun ColumnScope.GpsScreen(state: AppState) {
    val t = LocalTokens.current
    val d = state.device
    if (d == null) {
        ScreenHeader("GPS health", "Checks the satellite data, the 2019 date fix and the clock on the TomTom.")
        NeedDevice(state); return
    }
    val view by produceState<GpsView?>(null, d, state.gen) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                GpsView(quickFixOnDevice(d.fs), ttconvState(d.fs), glgpsState(d.fs), clockFixInstalled(d.fs),
                    d.fs.exists("gl/debug-gltt"), clockLooksWrong(d.fs))
            }.getOrNull()
        }
    }
    val v = view ?: return
    val chip = d.chip.chip
    val idle = state.busy == null

    // ---- the three checks
    val exp = v.quickFix.expiry
    val qfDamaged = v.quickFix.files["lto.dat"]?.let { !ltoValid(it) } == true
    val quick = when {
        chip.quickFixProvider == null -> Check(Status.NONE, "QuickGPSFix", "Not available for this GPS chip.")
        v.quickFix.files.isEmpty() -> Check(Status.WARN, "QuickGPSFix not installed", "The first fix takes longer without it.")
        qfDamaged -> Check(Status.BAD, "QuickGPSFix is damaged", "The lto.dat checksum fails. Update it.")
        exp == null -> Check(Status.WARN, "QuickGPSFix of unknown age", "Update it to be sure.")
        exp.isBefore(Instant.now()) -> Check(Status.WARN, "QuickGPSFix expired", "Expired on ${AppState.fmt(exp)}.")
        else -> Check(Status.OK, "QuickGPSFix up to date", "Valid until ${AppState.fmt(exp)}.")
    }
    val dateFix = when (chip) {
        Chip.GL1, Chip.AR1520 -> when (v.ttconv) {
            TtconvState.NOT_INSTALLED -> Check(Status.BAD, "Date fix missing", "TTconv is not installed, so the GPS reports a date 19.6 years old.")
            TtconvState.INSTALLED -> Check(Status.WARN, "Date fix installed, can stall", "TTconv is in place, but stops if its data pipe breaks and the GPS seems lost.")
            TtconvState.INSTALLED_IMPROVED -> Check(Status.OK, "Date fix installed", "TTconv restarts itself if it stops.")
        }
        Chip.GL2 -> if (v.glgps.fixed) Check(Status.OK, "Date fix installed", "NavCore 9.541 GPS driver (Broadcom GLL 2.16.201).")
            else Check(Status.BAD, "Date fix missing", if (v.glgps.present) "bin/glgps is ${v.glgps.version ?: "an unknown version"}." else "The built-in GPS driver predates the 2019 fix.")
        Chip.SIRF -> if (sirfNeedsUpdate(d.chip.firmware)) Check(Status.BAD, "GPS firmware too old", "${d.chip.firmware} fails after 2019. It needs 3.2.5 or 3.6.0.")
            else Check(Status.OK, "GPS firmware fine", d.chip.firmware.ifEmpty { "Version unknown." })
        Chip.UNKNOWN -> Check(Status.NONE, "Date fix unknown", "The GPS chip could not be identified.")
    }
    val clock = if (chip == Chip.GL2) {
        if (v.clockFix) Check(Status.OK, "Clock fix installed", "The GPS takes the time from the satellites first.")
        else Check(if (v.clockLooksWrong) Status.BAD else Status.WARN, "Clock fix not installed",
            if (v.clockLooksWrong) "This TomTom's clock has been reset before. With QuickGPSFix it may never find satellites."
            else "Old units lose their clock. Then QuickGPSFix points the GPS at the wrong part of the sky.")
    } else null
    val checks = listOfNotNull(quick, dateFix, clock)
    val problems = checks.count { it.status == Status.BAD || it.status == Status.WARN }

    ScreenHeader("GPS health", "${chip.label}" + (d.chip.firmware.takeIf { it.isNotEmpty() }?.let { ", firmware $it" } ?: "") + ".")

    // ---- summary leads
    Panel(padding = PaddingValues(horizontal = Space.lg, vertical = Space.sm)) {
        Row(Modifier.padding(vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
            val s = when { checks.any { it.status == Status.BAD } -> Status.BAD; problems > 0 -> Status.WARN; else -> Status.OK }
            Icon(statusIcon(s), null, tint = statusColor(s), modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(Space.md))
            Text(if (problems == 0) "Ready to navigate" else if (problems == 1) "1 thing to fix" else "$problems things to fix",
                style = MaterialTheme.typography.headlineSmall, color = t.text)
        }
        Hairline()
        StatusRow(quick.status, quick.title, quick.detail) {
            if (chip.quickFixProvider != null) {
                if (quick.status == Status.OK) SecondaryButton("Update", { state.updateQuickFix() }, enabled = idle)
                else PrimaryButton("Update", { state.updateQuickFix() }, enabled = idle)
            }
        }
        Hairline()
        StatusRow(dateFix.status, dateFix.title, dateFix.detail) {
            when (chip) {
                Chip.GL1, Chip.AR1520 -> if (v.ttconv != TtconvState.INSTALLED_IMPROVED)
                    PrimaryButton(if (v.ttconv == TtconvState.NOT_INSTALLED) "Install" else "Upgrade", { state.installTtconv() }, enabled = idle)
                Chip.GL2 -> if (!v.glgps.fixed) PrimaryButton("Install from file", { state.installGlgps() }, enabled = idle)
                else -> {}
            }
        }
        if (clock != null) {
            Hairline()
            StatusRow(clock.status, clock.title, clock.detail) {
                if (!v.clockFix) PrimaryButton("Install", { state.setClockFix(true) }, enabled = idle)
                else DangerTextButton("Remove", { state.setClockFix(false) }, enabled = idle)
            }
        }
    }

    // ---- details, quieter
    Spacer(Modifier.height(Space.xxl))
    Text("How it works", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.md))
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Detail("QuickGPSFix",
            "Satellite orbit predictions for the next 7 days, downloaded from TomTom's own server (the same file TomTom HOME used). It speeds up the first fix. Update it weekly.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(state.autoQuickFix, { state.changeAutoQuickFix(it) })
                Spacer(Modifier.width(Space.sm))
                Text("Update by itself when the TomTom is connected and it expires within a day" +
                    if (state.platform.isAndroid) ", and remind me the day before" else "",
                    style = MaterialTheme.typography.bodyMedium, color = t.text)
            }
            if (v.quickFix.files.isNotEmpty()) DangerTextButton("Remove QuickGPSFix", { state.removeQuickFix() }, enabled = idle, inline = true)
        }
        when (chip) {
            Chip.GL1, Chip.AR1520 -> Detail("TTconv",
                "Since the GPS week rollover of April 2019 this chip reports dates from 1999. TTconv, by treysis, corrects them before NavCore sees them. The version MapsToTom installs restarts the converter by itself and logs each restart to gl/log/ttconv.log.") {
                if (chip == Chip.GL1 && v.ttconv == TtconvState.INSTALLED_IMPROVED) Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(v.glttDebug, { state.setGlttDebug(it) }, enabled = idle)
                    Spacer(Modifier.width(Space.sm))
                    Text("Log the GPS engine to gl/log/gltt.txt (for troubleshooting)", style = MaterialTheme.typography.bodyMedium, color = t.text)
                }
            }
            Chip.GL2 -> Detail("GPS driver",
                "This chip is fixed by the GPS driver from NavCore 9.541, built in October 2018. MapsToTom can't include it, so choose bin/glgps from your own NavCore 9.541 files. It is checked before it is installed, and the old one is kept as bin/glgps.orig.") {}
            Chip.SIRF -> Detail("SiRF firmware",
                "The fix for GSW 3.2.4 is flashing GPS firmware 3.2.5 or 3.6.0 with the community FlashGPS tool. MapsToTom doesn't flash GPS chips: a failed flash can leave the GPS dead.") {}
            Chip.UNKNOWN -> {}
        }
        if (chip == Chip.GL2) Detail("Clock fix",
            "Old TomToms lose their internal clock. This GPS engine starts from that clock and, with QuickGPSFix, searches the wrong part of the sky. The fix writes gl/cfg/glconfig.xml, built from this TomTom's own system files, telling the engine to read the time from the satellites first.") {
            if (!v.clockFix) SecondaryButton("Install with GPS log", { state.setClockFix(true, log = true) }, enabled = idle)
        }
        Text("Chip detected from: ${d.chip.reason}.", style = MaterialTheme.typography.bodySmall, color = t.textMuted)
    }
}

@Composable
private fun Detail(title: String, body: String, extra: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = LocalTokens.current.text)
        Prose(body)
        extra()
    }
}

/** True if the TomTom wrote folders dated before 2005, a sign its clock was reset (desktop only). */
private fun clockLooksWrong(fs: DeviceFs): Boolean {
    if (fs !is LocalFs) return false
    val cutoff = ZonedDateTime.of(2005, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    return listOf("statdata", "logging", "contacts", "gl").any { n -> fs.file(n).let { it.exists() && it.lastModified() in 1..cutoff } }
}

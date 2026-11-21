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
    /** GL1 GPS program version, when it is one with the 2019 week bug. */
    val weekBugEngine: String?,
    /** The orbit table built into a supported GL1 GPS program. */
    val almanac: AlmanacInfo?,
)

@Composable
private fun GpsGroupTitle(title: String, detail: String) {
    Column(Modifier.padding(vertical = Space.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = LocalTokens.current.text)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalTokens.current.textMuted)
    }
}

/** What to try for the 2019 rollover, best first, with the fix for this chip. */
@Composable
private fun RolloverHelp(state: AppState, chip: Chip) {
    val fix = when (chip) {
        Chip.GL2 -> "Your GPS is a Broadcom BCM4750 (GL2), as in the GO x40, x50, 750, 950, XL IQ Routes and XXL Classic. " +
            "The fix is the glgps file from NavCore 9.541 in the bin folder: use Install from file above, or see the guide below."
        Chip.GL1 -> "Your GPS is a Global Locate Hammerhead (GL1), as in the ONE v4, ONE XL, ONE 140 and ONE 3rd Edition " +
            "(serial Y2, Y4 to Y8). The fix is the unofficial TTconv patch: use Install above."
        Chip.AR1520 -> "Your GPS is an Atheros AR1520 (Rider 2013). The fix is the unofficial TTconv patch: use Install above."
        Chip.SIRF -> "Your GPS is a SiRFstarIII, as in the GO x10 to x30, older ONEs and Rider 1 and 2. The chip itself isn't " +
            "affected by the rollover: older NavCores misread its date since then, and fixes can take very long or stop after a few " +
            "warm starts. NavCore 9.541 SE is reported to fix this. Some people flash the unofficial firmware GSW 3.2.5tt3 or 3.6.0 " +
            "instead. All of this is unofficial, so you'll have to find it yourself: MapsToTom doesn't include it or flash GPS chips, " +
            "because a failed flash can't be undone."
        Chip.UNKNOWN -> return
    }
    Column(Modifier.padding(vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("How to fix it", style = MaterialTheme.typography.titleSmall, color = LocalTokens.current.text)
        val version = state.device?.navcore
        val running = version?.let { "Your TomTom runs NavCore $it. " }.orEmpty()
        if (isNavCore95(version)) Prose("${running}That's already a 9.5xx, so a NavCore update won't add much. Apply the fix for your GPS chip. $fix")
        else Prose("${running}Try these in order:\n" +
            "1. Check TomTom HOME for a NavCore update. If your TomTom can get 9.501 or 9.541, that will most likely fix it.\n" +
            "2. If it doesn't, or you can't or don't want to update, you can look for a custom 9.5xx NavCore for your model on " +
            "GPS and navigation device forums. Those files are unofficial, so use them at your own risk.\n" +
            "3. Or apply the fix for your GPS chip. $fix")
        when (chip) {
            Chip.GL2 -> TextButton({ state.platform.openUrl(RIMA_LOCK_FIX) }, contentPadding = PaddingValues(0.dp)) { Text("glgps fix guide (rima-lock.com)") }
            Chip.GL1, Chip.AR1520 -> TextButton({ state.platform.openUrl(TTCONV_PAGE) }, contentPadding = PaddingValues(0.dp)) { Text("TTconv by treysis") }
            else -> {}
        }
    }
}

private const val TTCONV_PAGE = "https://github.com/treysis/TTconv"
private const val RIMA_LOCK_FIX = "https://www.rima-lock.com/tomtom-urban-rider-K3-WNRO-fix.htm#and_here_is_the_fix"

private class Check(val status: Status, val title: String, val detail: String)

@Composable
fun ColumnScope.GpsScreen(state: AppState) {
    val t = LocalTokens.current
    val d = state.device
    if (d == null) {
        ScreenHeader("GPS health", "Checks the satellite data, the 2019 date fix and the clock on the TomTom.")
        NeedDevice(state); return
    }
    val load by produceLoad(d, state.gen) {
        GpsView(quickFixOnDevice(d.fs), ttconvState(d.fs), glgpsState(d.fs), clockFixInstalled(d.fs),
            d.fs.exists("gl/debug-gltt"), clockLooksWrong(d.fs),
            if (d.chip.chip == Chip.GL1) gl1EngineVersion(d.fs)?.takeIf { it in LTO_WEEK_BUG_ENGINES } else null,
            if (d.chip.chip == Chip.GL1) almanacInfo(d.fs) else null)
    }
    val chip = d.chip.chip
    val subtitle = "${chip.label}" + (d.chip.firmware.takeIf { it.isNotEmpty() }?.let { ", firmware $it" } ?: "") + "."
    val v = when (val l = load) {
        Load.Loading -> {
            ScreenHeader("GPS health", subtitle)
            Panel(padding = PaddingValues(horizontal = Space.lg, vertical = Space.sm)) { SkeletonRows(if (chip == Chip.GL2) 4 else 3) }
            return
        }
        is Load.Failed -> { ScreenHeader("GPS health", subtitle); LoadFailed(state, "the GPS files", l); return }
        is Load.Ready -> l.value
    }
    val idle = state.busy == null

    // ---- the three checks
    val exp = v.quickFix.expiry
    val qfDamaged = v.quickFix.files["lto.dat"]?.let { !ltoValid(it) } == true
    // A GPS program with the week bug ignores TomTom's own file (offset 0); it needs the moved one.
    val qfIgnored = v.weekBugEngine != null && ltoWeekOffset(v.quickFix) == 0
    val qfAdjusted = v.weekBugEngine != null && (ltoWeekOffset(v.quickFix) ?: 0) != 0
    val quick = when {
        chip.quickFixProvider == null -> Check(Status.NONE, "QuickGPSFix", "Not available for this GPS chip.")
        v.quickFix.files.isEmpty() -> Check(Status.WARN, "QuickGPSFix not installed", "The first fix takes longer without it.")
        qfDamaged -> Check(Status.BAD, "QuickGPSFix is damaged", "The lto.dat checksum fails. Update it.")
        qfIgnored -> Check(Status.BAD, "QuickGPSFix not used by the GPS",
            "This GPS program has the 2019 week bug and ignores TomTom's file as it is. Update it: MapsToTom adjusts it.")
        exp == null -> Check(Status.WARN, "QuickGPSFix of unknown age", "Update it to be sure.")
        exp.isBefore(Instant.now()) -> Check(Status.WARN, "QuickGPSFix expired", "Expired on ${AppState.fmt(exp)}.")
        else -> Check(Status.OK, "QuickGPSFix up to date",
            "Valid until ${AppState.fmt(exp)}." + if (qfAdjusted) " Adjusted for this GPS program's 2019 week bug." else "")
    }
    val dateFix = when (chip) {
        Chip.GL1, Chip.AR1520 -> when (v.ttconv) {
            TtconvState.NOT_INSTALLED -> Check(Status.BAD, "Date fix missing", "TTconv is not installed, so the GPS reports a date 19.6 years old.")
            TtconvState.INSTALLED -> Check(Status.WARN, "Date fix installed, can stall", "TTconv is in place, but stops if its data pipe breaks and the GPS seems lost.")
            TtconvState.INSTALLED_IMPROVED -> Check(Status.OK, "Date fix installed", "TTconv restarts itself if it stops.")
        }
        Chip.GL2 -> if (v.glgps.fixed) Check(Status.OK, "Date fix installed", "NavCore 9.541 GPS driver (Broadcom GLL 2.16.201).")
            else Check(Status.BAD, "Date fix missing", if (v.glgps.present) "bin/glgps is ${v.glgps.version ?: "an unknown version"}." else "The built-in GPS driver predates the 2019 fix.")
        Chip.SIRF -> if (sirfNeedsUpdate(d.chip.firmware)) Check(Status.WARN, "May need a newer NavCore", "This chip isn't affected by the rollover, but older NavCores misread its date. NavCore 9.541 SE is reported to fix it. If you already run it, ignore this.")
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

    ScreenHeader("GPS health", subtitle)

    // ---- summary leads
    Panel(padding = PaddingValues(horizontal = Space.lg, vertical = Space.sm)) {
        Row(Modifier.padding(vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
            val s = when { checks.any { it.status == Status.BAD } -> Status.BAD; problems > 0 -> Status.WARN; else -> Status.OK }
            Icon(statusIcon(s), null, tint = statusColor(s), modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(Space.md))
            Text(if (problems == 0) "Ready to navigate" else if (problems == 1) "1 thing to fix" else "$problems things to fix",
                style = MaterialTheme.typography.headlineSmall, color = t.text)
        }
    }

    Spacer(Modifier.height(Space.lg))
    // Two separate jobs, side by side: finding satellites quickly, and the 2019 date problem.
    val fixPanel: @Composable (Modifier) -> Unit = { m ->
        Panel(m, padding = PaddingValues(horizontal = Space.lg, vertical = Space.sm)) {
            GpsGroupTitle("GPS fix", "How fast it finds satellites. Doesn't need a patched NavCore.")
            Hairline()
            StatusRow(quick.status, quick.title, quick.detail) {
                if (chip.quickFixProvider != null) {
                    val busy = state.busy == "Downloading QuickGPSFix…"
                    if (quick.status == Status.OK) SecondaryButton("Update", { state.updateQuickFix() }, enabled = idle, busy = busy, busyText = "Updating…")
                    else PrimaryButton("Update", { state.updateQuickFix() }, enabled = idle, busy = busy, busyText = "Updating…")
                }
            }
            v.almanac?.takeIf { ALMANAC_FEATURE || !it.builtIn }?.let { a ->
                Hairline()
                val old = a.date.isBefore(Instant.now().minusSeconds(90L * 86_400))
                StatusRow(if (a.builtIn || old) Status.WARN else Status.OK,
                    if (a.builtIn) "Built-in almanac is from 2007" else "Almanac from ${AppState.fmt(a.date)}",
                    if (a.builtIn) "The GPS program's own table of satellite orbits is out of date. Updating it can help the first fix."
                    else "Current satellite orbits are in the GPS program. Update every few months.") {
                    if (a.builtIn) PrimaryButton("Update", { state.askUpdateAlmanac() }, enabled = idle)
                    else {
                        SecondaryButton("Update", { state.askUpdateAlmanac() }, enabled = idle)
                        if (a.canRemove) DangerTextButton("Undo", { state.askRemoveAlmanac() }, enabled = idle)
                    }
                }
            }
        }
    }
    val rolloverPanel: @Composable (Modifier) -> Unit = { m ->
        Panel(m, padding = PaddingValues(horizontal = Space.lg, vertical = Space.sm)) {
            GpsGroupTitle("Week rollover (2019)", "Why old units show a wrong date or lose satellites.")
            Hairline()
            StatusRow(dateFix.status, dateFix.title, dateFix.detail) {
                when (chip) {
                    Chip.GL1, Chip.AR1520 -> if (v.ttconv != TtconvState.INSTALLED_IMPROVED)
                        PrimaryButton(if (v.ttconv == TtconvState.NOT_INSTALLED) "Install" else "Upgrade", { state.askInstallTtconv() }, enabled = idle)
                    Chip.GL2 -> if (!v.glgps.fixed) PrimaryButton("Install from file", { state.askInstallGlgps() }, enabled = idle)
                    else -> {}
                }
            }
            if (clock != null) {
                Hairline()
                StatusRow(clock.status, clock.title, clock.detail) {
                    if (!v.clockFix) PrimaryButton("Install", { state.askClockFix() }, enabled = idle)
                    else DangerTextButton("Remove", { state.setClockFix(false) }, enabled = idle)
                }
            }
            if (dateFix.status == Status.BAD || dateFix.status == Status.WARN) { Hairline(); RolloverHelp(state, chip) }
        }
    }
    BoxWithConstraints {
        if (maxWidth >= 760.dp) Row(horizontalArrangement = Arrangement.spacedBy(Space.lg), verticalAlignment = Alignment.Top) {
            fixPanel(Modifier.weight(1f)); rolloverPanel(Modifier.weight(1f))
        } else Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) { fixPanel(Modifier); rolloverPanel(Modifier) }
    }

    JobFeedback(state, Job.GPS)

    // ---- details, quieter
    Spacer(Modifier.height(Space.xxl))
    Text("How it works", style = MaterialTheme.typography.headlineSmall, color = t.text)
    Spacer(Modifier.height(Space.md))
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Detail("QuickGPSFix",
            "Satellite orbit predictions for the next 7 days, downloaded from TomTom's own server (the same file TomTom HOME used). It speeds up the first fix. Update it weekly." +
                if (v.weekBugEngine != null) " This TomTom's GPS program still thinks it is 19.6 years earlier (the 2019 week bug), so MapsToTom moves the file's dates to match, or the GPS would ignore it." else "") {
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
            Chip.SIRF -> Detail("SiRF and the rollover",
                "The SiRFstarIII chip isn't affected by the 2019 rollover. Older NavCores misread the date in its NMEA data, and NavCore 9.541 SE is reported to fix that. Some people flash the community firmware GSW 3.2.5 or 3.6.0 instead. MapsToTom doesn't flash GPS chips: a failed flash can't be undone.") {}
            Chip.UNKNOWN -> {}
        }
        if (chip == Chip.GL2) Detail("Clock fix",
            "Old TomToms lose their internal clock. This GPS engine starts from that clock and, with QuickGPSFix, searches the wrong part of the sky. The fix writes gl/cfg/glconfig.xml, built from this TomTom's own system files, telling the engine to read the time from the satellites first.") {
            if (!v.clockFix) SecondaryButton("Install with GPS log", { state.askClockFix(log = true) }, enabled = idle)
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

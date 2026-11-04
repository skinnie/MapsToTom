package app.mapstotom

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The real desktop app, driven by clicks off-screen: finds the plugged-in TomTom, ticks one OpenStreetMap
 * category, sets the countries and presses Install, as a person would. It WRITES TO THE TOMTOM. Not run by default:
 * -Pm2t.gui=<dir for screenshots> [-Pm2t.guiType="Swimming pools (public)"] [-Pm2t.guiCountries=PT]
 * [-Pm2t.osmKeyFile=<file>] (the key is typed into the key field when the app has none saved).
 */
@OptIn(ExperimentalTestApi::class)
class GuiInstallRun {
    @Test fun run() {
        val dir = System.getProperty("m2t.gui")?.let(::File) ?: return
        dir.mkdirs()
        val type = System.getProperty("m2t.guiType", "Swimming pools (public)")
        val countries = System.getProperty("m2t.guiCountries", "PT")
        val keyFile = System.getProperty("m2t.osmKeyFile")?.let(::File)
        runDesktopComposeUiTest(1100, 900, testTimeout = kotlin.time.Duration.parse("45m")) {
            val platform = DesktopPlatform()
            var state: AppState? = null
            setContent {
                val scope = rememberCoroutineScope()
                App(remember { AppState(platform, scope).also { state = it } })
            }
            fun shot(name: String) = waitForIdle().let { mainClock.advanceTimeBy(500) }.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png")) }
            fun exists(m: SemanticsMatcher) = onAllNodes(m, useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty()
            fun say(s: String) = println("GUI: $s")

            waitUntil(timeoutMillis = 120_000) { state?.device != null && state?.busy == null }
            say("connected: ${state!!.device!!.model}, language ${state!!.device!!.language}")
            shot("1-connected")

            onNodeWithText("POIs").performClick()
            waitForIdle()
            val row = { label: String -> onNode(hasText(label) and isToggleable()) }
            // Untick everything that is ticked, then tick the one type.
            for (n in onAllNodes(isToggleable() and isOn()).fetchSemanticsNodes()) {
                val label = n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() ?: continue
                if (label != type) onNode(hasText(label) and isToggleable()).performScrollTo().performClick()
            }
            row(type).performScrollTo().apply { if (!exists(hasText(type) and isToggleable() and isOn())) performClick() }
            row(type).assertIsOn()

            val field = onNode(hasSetTextAction() and hasText("Countries", substring = true))
            field.performScrollTo().performTextReplacement(countries)

            val keyField = hasSetTextAction() and hasText("TomTom API key", substring = true)
            if (state!!.tomtomKey == null && keyFile?.isFile == true && exists(keyField))
                onNode(keyField).performScrollTo().performTextInput(keyFile.readText().trim()) // masked on screen
            say("countries ${state!!.osmCountries}, skip known ${state!!.osmSkipKnown}, key ${if (state!!.tomtomKey != null) "set" else "none"}")

            onNode(hasText("Install 1") and hasClickAction()).performScrollTo().also { shot("2-before-install") }.performClick()
            say("install pressed")
            waitUntil(timeoutMillis = 10_000) { state!!.busy != null }
            var last = ""
            waitUntil(timeoutMillis = 30 * 60_000) {
                val now = state!!.steps.joinToString { "${it.label}: ${it.state} ${it.detail.orEmpty()}" }
                if (now != last) { say(now); last = now }
                state!!.busy == null
            }
            shot("3-done")
            say("result: ${state!!.note?.text}")
            say("skipped as already on the map: ${state!!.osmSkipped.size}")
            state!!.osmSkipped.take(15).forEach { say("  ${it.osmName}  =  ${it.tomtomName}  (${it.meters} m)") }
        }
    }
}

/**
 * What the app does on connect, for a card on the PC: names the app's categories in the TomTom's language and
 * removes the old start-up rename. WRITES TO THE CARD. -Pm2t.applyCard=<card root>.
 */
class ApplyNamesRun {
    @Test fun run() {
        val root = System.getProperty("m2t.applyCard")?.let(::File) ?: return
        val fs = app.mapstotom.core.LocalFs(root)
        val map = app.mapstotom.core.findMapFolder(fs) ?: error("no map folder")
        val lang = app.mapstotom.core.tomtomLanguage(app.mapstotom.core.readBif(fs))
        app.mapstotom.core.renameTranslatedCategories(fs, map, lang).forEach { println("APPLY: renamed $it") }
        println("APPLY: old start-up rename removed: ${app.mapstotom.core.removeBootRename(fs)}")
    }
}

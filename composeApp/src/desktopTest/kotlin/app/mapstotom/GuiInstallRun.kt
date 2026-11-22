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
        app.mapstotom.core.moveNamedIntoTomTomCategories(fs, map).forEach { println("APPLY: moved $it") }
    }
}

/**
 * The Foursquare or Overture install for a card on the PC, with the app's own code. WRITES TO THE CARD.
 * -Pm2t.fsqCard=<card root> [-Pm2t.fsqSource=free|latest|overture] [-Pm2t.fsqCountries=PT] [-Pm2t.fsqSince=2020]
 * [-Pm2t.fsqTypes=RESTAURANT,CAFE] [-Pm2t.fsqConfidence=0.6]; "latest" reads the Hugging Face token from -Pm2t.fsqTokenFile.
 */
class FoursquareInstallRun {
    @Test fun run() {
        val root = System.getProperty("m2t.fsqCard")?.let(::File) ?: return
        app.mapstotom.core.ParquetCodecs.zstd = { b, n -> com.github.luben.zstd.Zstd.decompress(b, n) }
        val fs = app.mapstotom.core.LocalFs(root)
        val map = app.mapstotom.core.findMapFolder(fs) ?: error("no map folder")
        val lang = app.mapstotom.core.tomtomLanguage(app.mapstotom.core.readBif(fs))
        val res = File("src/commonMain/composeResources/files")
        val boxes = app.mapstotom.core.CountryBoxes.parse(File(res, "country-boxes.json").readText())
        val countries = (System.getProperty("m2t.fsqCountries") ?: "PT").split(',').map { it.trim() }
        val since = System.getProperty("m2t.fsqSince")?.toInt() ?: app.mapstotom.core.FSQ_DEFAULT_SINCE
        val source = System.getProperty("m2t.fsqSource") ?: "free"
        val kinds = (System.getProperty("m2t.fsqTypes")?.split(',')?.map { app.mapstotom.core.PlaceKind.valueOf(it.trim()) }
            ?: app.mapstotom.core.PlaceKind.entries).filter { source != "overture" || it.overture.isNotEmpty() }
        val net = app.mapstotom.core.RealNet()
        val cache = File("build/parquet-footers")
        val t0 = System.currentTimeMillis()
        val log: (Int, Int) -> Unit = { i, n -> if (i % 10 == 0) println("PLACES: step $i of $n") }
        app.mapstotom.core.moveNamedIntoTomTomCategories(fs, map).forEach { println("PLACES: moved $it") }
        val got = when (source) {
            "overture" -> {
                val rel = app.mapstotom.core.latestOvertureRelease(net); println("PLACES: Overture ${rel.name}, ${rel.files.size} files")
                app.mapstotom.core.fetchOverture(rel, countries, boxes, kinds.toSet(),
                    System.getProperty("m2t.fsqConfidence")?.toDouble() ?: app.mapstotom.core.OVERTURE_DEFAULT_CONFIDENCE, net, cache, log)
            }
            "latest" -> {
                val token = File(System.getProperty("m2t.fsqTokenFile") ?: error("-Pm2t.fsqTokenFile needed")).readText().trim()
                val rel = app.mapstotom.core.latestFsqRelease(net); println("PLACES: Foursquare ${rel.date}, ${rel.files.size} files")
                app.mapstotom.core.fetchFoursquareLatest(rel, token, countries, boxes, kinds.toSet(), since, net, cache, log)
            }
            else -> app.mapstotom.core.fetchFoursquare(app.mapstotom.core.FsqIndex.parse(File(res, "fsq/index.json").readText()),
                countries, kinds.toSet(), since, net, log)
        }
        println("PLACES: downloaded in ${(System.currentTimeMillis() - t0) / 1000} s")
        for (k in kinds) {
            val src = if (source == "overture") k.overtureSource else k.fsqSource
            val r = app.mapstotom.core.installPlaceKind(fs, map, k, src, got[k].orEmpty(), lang)
            println("PLACES: ${k.label}: ${got[k].orEmpty().size} found, ${r.added} added${app.mapstotom.core.categoryNearNote(r)}, ${r.yours} of yours kept")
        }
    }
}

/**
 * Installs today's fuel prices on a TomTom card and records the set for the car link. It WRITES TO THE CARD. Not run by default:
 * -Pm2t.fuelCard=<card root> -Pm2t.fuelOut=<dir for fuelset-<id>.txt, to copy to the phone's files/carlink>
 */
class FuelInstallRun {
    @Test fun run() {
        val root = System.getProperty("m2t.fuelCard")?.let(::File) ?: return
        val out = File(System.getProperty("m2t.fuelOut") ?: error("-Pm2t.fuelOut needed"))
        val fs = app.mapstotom.core.LocalFs(root)
        val map = app.mapstotom.core.findMapFolder(fs) ?: error("no map folder")
        val sources = app.mapstotom.core.mapCountries(fs, map).mapNotNull(app.mapstotom.core.FuelSource::of)
        check(sources.isNotEmpty()) { "no fuel source for this map" }
        val type = app.mapstotom.core.findFuelSets(fs, map).firstOrNull()?.type ?: app.mapstotom.core.FuelType.DIESEL
        val net = app.mapstotom.core.RealNet()
        val time = java.time.Instant.now()
        val stations = sources.flatMap { s ->
            app.mapstotom.core.fetchFuel(s, net, type).also { println("FUEL: ${s.country} ${it.size} stations") }
        }
        check(stations.size > 1000) { "only ${stations.size} stations, not replacing the set" }
        app.mapstotom.core.installFuelPrices(fs, map, stations, type, time)
        val id = app.mapstotom.core.fuelSetId(map)
        app.mapstotom.link.StoredFuelSets.record(out, id, sources, app.mapstotom.core.fuelSetOrder(stations))
        println("FUEL: installed ${stations.size} stations, ${type.label}, set $id")
    }
}

/** Writes today's almanac into the card's GPS program. It WRITES TO THE CARD. Not run by default: -Pm2t.almCard=<card root> */
class AlmanacInstallRun {
    @Test fun run() {
        val root = System.getProperty("m2t.almCard")?.let(::File) ?: return
        val fs = app.mapstotom.core.LocalFs(root)
        val r = app.mapstotom.core.RealNet().get(app.mapstotom.core.ALMANAC_URL, mapOf("User-Agent" to app.mapstotom.core.USER_AGENT), timeoutMs = 30_000)
        check(r.code == 200) { "HTTP ${r.code}" }
        val sats = app.mapstotom.core.parseYuma(r.text)
        app.mapstotom.core.installAlmanac(fs, sats)
        val info = app.mapstotom.core.almanacInfo(fs) ?: error("not readable after install")
        println("ALMANAC: ${sats.size} satellites, ${sats.count { it.healthy }} healthy, week ${sats[0].week}, date ${info.date}, builtIn=${info.builtIn}, canRemove=${info.canRemove}")
    }
}

/** A real full backup, read-only for the source: -Pm2t.backupFrom=<card root> -Pm2t.backupTo=<folder>. Not run by default. */
class CardBackupRun {
    @Test fun run() {
        val from = System.getProperty("m2t.backupFrom")?.let(::File) ?: return
        val to = File(System.getProperty("m2t.backupTo") ?: error("-Pm2t.backupTo needed")).apply { mkdirs() }
        val t0 = System.currentTimeMillis()
        val n = app.mapstotom.core.backupCard(app.mapstotom.core.LocalFs(from), app.mapstotom.core.LocalFs(to), "card", "Full card backup")
        val check = app.mapstotom.core.verifyBackup(app.mapstotom.core.LocalFs(File(to, "card")))
        println("BACKUP: $n files in ${(System.currentTimeMillis() - t0) / 1000} s, verified ${check.files}, missing ${check.missing.size}, damaged ${check.damaged.size}")
        check(check.ok)
    }
}

/** Draws the kitchen from a real card copy: -Pm2t.backupFrom=<card> -Pm2t.backupTo=<folder>. Not run by default. */
class KitchenRenderRun {
    @Test fun run() {
        val from = System.getProperty("m2t.backupFrom")?.let(::File) ?: return
        val out = File(System.getProperty("m2t.backupTo") ?: error("-Pm2t.backupTo needed"))
        val fs = app.mapstotom.core.LocalFs(from); val bif = app.mapstotom.core.readBif(fs)
        val k = app.mapstotom.core.readKitchen(fs, bif); val s = app.mapstotom.core.readDeviceSpecs(fs, bif)
        val f = bif["Features"]!!.split(',').toSet()
        val scene = androidx.compose.ui.ImageComposeScene(760, 1900, androidx.compose.ui.unit.Density(1f)) {
            app.mapstotom.ui.MapsToTomTheme(dark = true) {
                androidx.compose.material3.Surface(color = app.mapstotom.ui.LocalTokens.current.page) {
                    androidx.compose.foundation.layout.Column {
                        app.mapstotom.KitchenContent(k, s, f, true, "x40light3") {}
                    }
                }
            }
        }
        scene.render(); val img = scene.render(1_000_000_000L)
        File(out, "kitchen.png").writeBytes(img.encodeToData()!!.bytes); scene.close()
    }
}

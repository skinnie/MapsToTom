package app.mapstotom

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.mapstotom.core.DeviceFs
import app.mapstotom.core.LocalFs
import app.mapstotom.core.parseInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders every screen off-screen to PNG, so the UI can be looked at without a display.
 * Runs only with -Pm2t.shots=<output dir> (and optionally -Pm2t.device=<TomTom copy>).
 */
class ScreenshotTest {
    private val out = System.getProperty("m2t.shots")?.let(::File)
    private val deviceDir = System.getProperty("m2t.device")?.let(::File)

    private object FakePlatform : Platform {
        override val isAndroid = false
        override fun detectDevices(): List<DeviceFs> = emptyList()
        override suspend fun pickFolder(purpose: FolderPurpose): DeviceFs? = null
        override suspend fun pickFile(title: String): Pair<String, ByteArray>? = null
        override suspend fun saveFile(suggestedName: String, data: ByteArray): String? = null
        override fun clipboardText(): String? = null
        override fun openUrl(url: String) {}
        @androidx.compose.runtime.Composable
        override fun fileDrop(onFiles: (List<File>) -> Unit, onHover: (Boolean) -> Unit) = androidx.compose.ui.Modifier // drop zones shown, as on desktop
    }

    private val route = "https://www.google.com/maps/dir/Lisbon/Sintra/Cascais/@38.76,-9.45,11z/data=!4m25!4m24" +
        "!1m10!1m1!1s0xd19331a61e4f33b:0x400ebbde49036d0!2m2!1d-9.1393366!2d38.7222524" +
        "!3m4!1m2!1d-9.2362374!2d38.6706256!3s0xd1ecba9a2b6d26f:0x5ad67c75ad299d60" +
        "!1m5!1m1!1s0xd1ed09be5d23323:0x400ebbde4903720!2m2!1d-9.3229213!2d38.8233416" +
        "!1m5!1m1!1s0xd1ec47379b79a97:0x870d3b7e1807bbc2!2m2!1d-9.4303646!2d38.6960614!3e0"

    /** With -Pm2t.gpi too: a tall POI screen with that file opened. */
    @Test fun renderGpi() {
        val dir = out ?: return
        val gpi = System.getProperty("m2t.gpi")?.let(::File)?.takeIf { it.isFile } ?: return
        dir.mkdirs()
        val p = object : Platform by FakePlatform { override suspend fun pickFile(title: String) = gpi.name to gpi.readBytes() }
        val state = AppState(p, CoroutineScope(Dispatchers.Unconfined))
        deviceDir?.takeIf { it.isDirectory }?.let { state.connect(LocalFs(it)) }
        state.osmCountries = "PT, ES, AD, GI"
        state.openGpi()
        repeat(100) { if (state.gpiFile == null) Thread.sleep(100) }
        val scene = ImageComposeScene(1100, 2600, Density(1f)) { App(state, dark = false, startTab = Tab.POIS) }
        var t = 0L
        repeat(12) { scene.render(t); t += 100_000_000; Thread.sleep(60) }
        File(dir, "gpi-poi.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    /** The whole Device tab (add-ons, power, backup) at desktop and phone width; needs -Pm2t.device. */
    @Test fun renderDeviceTall() {
        val dir = out ?: return
        val dev = deviceDir?.takeIf { it.isDirectory } ?: return
        dir.mkdirs()
        for ((name, wh) in mapOf("desktop" to (1100 to 1700), "phone" to (412 to 2300))) {
            val state = AppState(FakePlatform, CoroutineScope(Dispatchers.Unconfined))
            state.connect(LocalFs(dev))
            val scene = ImageComposeScene(wh.first, wh.second, Density(1f)) { App(state, dark = false, startTab = Tab.DEVICE) }
            var t = 0L
            repeat(12) { scene.render(t); t += 100_000_000; Thread.sleep(60) }
            File(dir, "$name-device-tall.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }

    /** Job feedback: steps while running, the result note, the restart reminder and "safe to unplug". Nothing runs or writes. */
    @Test fun renderJobStates() {
        val dir = out ?: return
        val dev = deviceDir?.takeIf { it.isDirectory } ?: return
        dir.mkdirs()
        fun AppState.call(name: String, vararg args: Any?) = AppState::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(this, *args)
        fun AppState.set(name: String, v: Any?) = AppState::class.java.declaredMethods.first { it.name == "set" + name.replaceFirstChar(Char::uppercase) && it.parameterCount == 1 }
            .apply { isAccessible = true }.invoke(this, v)
        fun shot(state: AppState, tab: Tab, name: String, w: Int = 1100, h: Int = 1500) {
            val scene = ImageComposeScene(w, h, Density(1f)) { App(state, dark = false, startTab = tab) }
            var t = 0L
            repeat(12) { scene.render(t); t += 100_000_000; Thread.sleep(60) }
            File(dir, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        val running = AppState(FakePlatform, CoroutineScope(Dispatchers.Unconfined)).apply {
            connect(LocalFs(dev))
            set("busy", "Downloading fuel prices…"); set("busyKey", Job.FUEL)
            call("plan", listOf("Portugal: download prices", "Spain: download prices", "Write to the TomTom"), true)
            call("step", 1, "40%")
            call("stepDetail", 0, "3,112 stations")
            call("progress", 420, 1050, "areas")
        }
        shot(running, Tab.POIS, "job-running")
        val done = AppState(FakePlatform, CoroutineScope(Dispatchers.Unconfined)).apply {
            connect(LocalFs(dev))
            set("busy", "x"); set("busyKey", Job.FUEL)
            call("plan", listOf("Portugal: download prices", "Write to the TomTom"), true)
            call("step", 1, null)
            say("Fuel prices installed: 3,112 stations, 3,050 with Diesel. Restart the TomTom so it finds them.")
            call("needsRestart")
            (steps.forEach { if (it.state != Step.State.AFTER) it.state = Step.State.DONE })
            set("busy", null); set("busyKey", null)
        }
        shot(done, Tab.POIS, "job-done")
        val ejected = AppState(FakePlatform, CoroutineScope(Dispatchers.Unconfined)).apply { set("ejected", true); call("needsRestart") }
        shot(ejected, Tab.SEND, "ejected", 412, 900)
        val route = AppState(FakePlatform, CoroutineScope(Dispatchers.Unconfined)).apply {
            connect(LocalFs(dev)); points.addAll(parseInput(this@ScreenshotTest.route, null).points); followRoads = true
        }
        shot(route, Tab.SEND, "phone-send-tall", 412, 2000)
        shot(AppState(FakePlatform, CoroutineScope(Dispatchers.Unconfined)), Tab.SEND, "send-empty", 1100, 1100)
    }

    @Test fun renderAllScreens() {
        val dir = out ?: return
        dir.mkdirs()
        val sizes = mapOf("desktop" to (1100 to 820), "phone" to (412 to 900))
        for ((sizeName, wh) in sizes) for (dark in listOf(false, true)) for (tab in Tab.entries) {
            val state = AppState(FakePlatform, CoroutineScope(Dispatchers.Unconfined))
            deviceDir?.takeIf { it.isDirectory }?.let { state.connect(LocalFs(it)) }
            state.points.addAll(parseInput(route, null).points)
            state.routeName = "Lisbon day trip"
            val scene = ImageComposeScene(wh.first, wh.second, Density(1f)) { App(state, dark = dark, startTab = tab) }
            var t = 0L
            repeat(12) { scene.render(t); t += 100_000_000; Thread.sleep(60) } // let produceState load from disk
            val img = scene.render(t)
            File(dir, "${sizeName}-${if (dark) "dark" else "light"}-${tab.name.lowercase()}.png")
                .writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
}

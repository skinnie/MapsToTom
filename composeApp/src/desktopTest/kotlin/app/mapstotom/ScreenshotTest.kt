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
    }

    private val route = "https://www.google.com/maps/dir/Lisbon/Sintra/Cascais/@38.76,-9.45,11z/data=!4m25!4m24" +
        "!1m10!1m1!1s0xd19331a61e4f33b:0x400ebbde49036d0!2m2!1d-9.1393366!2d38.7222524" +
        "!3m4!1m2!1d-9.2362374!2d38.6706256!3s0xd1ecba9a2b6d26f:0x5ad67c75ad299d60" +
        "!1m5!1m1!1s0xd1ed09be5d23323:0x400ebbde4903720!2m2!1d-9.3229213!2d38.8233416" +
        "!1m5!1m1!1s0xd1ec47379b79a97:0x870d3b7e1807bbc2!2m2!1d-9.4303646!2d38.6960614!3e0"

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

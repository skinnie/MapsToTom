package app.mapstotom

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.mapstotom.core.DeviceFs
import app.mapstotom.core.LocalFs
import app.mapstotom.core.findTomTomRoots
import app.mapstotom.core.isGoogleMapsUrl
import app.mapstotom.core.isShortLink
import app.mapstotom.core.isViaMichelinUrl
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager

class DesktopPlatform : Platform {
    override val isAndroid = false
    private var lastDir: File? = null

    /** Mounted TomToms, plus a folder named in MAPSTOTOM_DEVICE (handy for testing on a copy). */
    override fun detectDevices(): List<DeviceFs> =
        (listOfNotNull(System.getenv("MAPSTOTOM_DEVICE")?.let(::File)?.takeIf { it.isDirectory }) + findTomTomRoots()).map { LocalFs(it) }

    private fun chooser(title: String, dirs: Boolean) = JFileChooser(lastDir ?: File(System.getProperty("user.home"))).apply {
        dialogTitle = title
        fileSelectionMode = if (dirs) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
    @androidx.compose.runtime.Composable
    override fun fileDrop(onFiles: (List<File>) -> Unit, onHover: (Boolean) -> Unit): androidx.compose.ui.Modifier {
        val files = androidx.compose.runtime.rememberUpdatedState(onFiles)
        val hover = androidx.compose.runtime.rememberUpdatedState(onHover)
        val target = remember {
            object : androidx.compose.ui.draganddrop.DragAndDropTarget {
                override fun onEntered(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = hover.value(true)
                override fun onExited(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = hover.value(false)
                override fun onEnded(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = hover.value(false)
                override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                    hover.value(false)
                    val list = (event.dragData() as? androidx.compose.ui.draganddrop.DragData.FilesList)?.readFiles()
                        ?.mapNotNull { runCatching { File(URI(it)) }.getOrNull() }.orEmpty()
                    if (list.isEmpty()) return false
                    files.value(list); return true
                }
            }
        }
        return androidx.compose.ui.Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { it.dragData() is androidx.compose.ui.draganddrop.DragData.FilesList }, target = target)
    }

    override suspend fun pickFolder(purpose: FolderPurpose): DeviceFs? {
        val c = chooser(purpose.title, dirs = true)
        if (purpose == FolderPurpose.DEVICE) findTomTomRoots().firstOrNull()?.let { c.currentDirectory = it.parentFile; c.selectedFile = it }
        if (c.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        lastDir = c.selectedFile.parentFile
        return LocalFs(c.selectedFile)
    }

    override suspend fun pickFile(title: String): Pair<String, ByteArray>? {
        val c = chooser(title, dirs = false)
        if (c.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        lastDir = c.selectedFile.parentFile
        return c.selectedFile.name to c.selectedFile.readBytes()
    }

    override suspend fun saveFile(suggestedName: String, data: ByteArray): String? {
        val c = chooser("Save $suggestedName", dirs = false)
        c.selectedFile = File(c.currentDirectory, suggestedName)
        if (c.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        c.selectedFile.writeBytes(data)
        lastDir = c.selectedFile.parentFile
        return c.selectedFile.path
    }

    override fun clipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun openUrl(url: String) { runCatching { Desktop.getDesktop().browse(URI(url)) } }

    private val prefs = java.util.prefs.Preferences.userRoot().node("app/mapstotom")
    override fun loadSetting(key: String): String? = prefs.get(key, null)
    override fun saveSetting(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs.put(key, value); runCatching { prefs.flush() } }

    /** The usual per-user app data folder: %APPDATA%, ~/Library/Application Support, or $XDG_DATA_HOME / ~/.local/share. */
    override val dataDir: File by lazy {
        val home = System.getProperty("user.home"); val os = System.getProperty("os.name").lowercase()
        when {
            "win" in os -> File(System.getenv("APPDATA") ?: home, "MapsToTom")
            "mac" in os -> File(home, "Library/Application Support/MapsToTom")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "mapstotom")
        }
    }
}

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    application {
        val platform = remember { DesktopPlatform() }
        val scope = rememberCoroutineScope()
        val state = remember { AppState(platform, scope) }
        // Watch the clipboard: a Google Maps link copied anywhere is added once.
        LaunchedEffect(Unit) {
            var last = platform.clipboardText()
            while (true) {
                delay(1000)
                val now = platform.clipboardText()
                if (now != null && now != last && state.watchClipboard) {
                    val link = now.trim()
                    if (link.startsWith("http") && (isGoogleMapsUrl(link) || isShortLink(link) ||
                            (isViaMichelinUrl(link) && "itinerary=" in link))) state.addText(link)
                }
                last = now
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "MapsToTom",
            icon = remember { BitmapPainter(DesktopPlatform::class.java.getResourceAsStream("/icon.png")!!.use { loadImageBitmap(it) }) },
            state = rememberWindowState(size = DpSize(960.dp, 680.dp)),
        ) {
            App(state, dark = androidx.compose.foundation.isSystemInDarkTheme())
        }
    }
}

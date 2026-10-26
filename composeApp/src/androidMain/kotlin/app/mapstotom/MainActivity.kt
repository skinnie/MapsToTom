package app.mapstotom

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import app.mapstotom.core.DeviceFs
import app.mapstotom.core.LocalFs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Text shared from Google Maps (Share → MapsToTom). */
    private val shared = MutableStateFlow<String?>(null)

    private var pendingTree: CompletableDeferred<Uri?>? = null
    private var pendingOpen: CompletableDeferred<Uri?>? = null
    private var pendingCreate: CompletableDeferred<Uri?>? = null

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { pendingTree?.complete(it) }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { pendingOpen?.complete(it) }
    private val fileCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { pendingCreate?.complete(it) }

    private val prefs by lazy { getSharedPreferences("mapstotom", Context.MODE_PRIVATE) }
    private val rootUsb by lazy { RootUsb(this) }

    private val platform = object : Platform {
        override val isAndroid = true

        override fun detectDevices(): List<DeviceFs> {
            // Rooted: mount the TomTom directly (Android rejects its partition-less disk).
            rootUsb.mount()?.let { return listOf(LocalFs(it)) }
            val uri = prefs.getString("device", null)?.let(Uri::parse) ?: return emptyList()
            val granted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            return if (granted) listOfNotNull(runCatching { SafFs(this@MainActivity, uri) }.getOrNull()) else emptyList()
        }

        override suspend fun pickFolder(purpose: FolderPurpose): DeviceFs? {
            val d = CompletableDeferred<Uri?>().also { pendingTree = it }
            treePicker.launch(null)
            val uri = d.await() ?: return null
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (purpose == FolderPurpose.DEVICE) prefs.edit().putString("device", uri.toString()).apply()
            return SafFs(this@MainActivity, uri)
        }

        override suspend fun pickFile(title: String): Pair<String, ByteArray>? {
            val d = CompletableDeferred<Uri?>().also { pendingOpen = it }
            filePicker.launch(arrayOf("*/*"))
            val uri = d.await() ?: return null
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            return displayName(uri) to bytes
        }

        override suspend fun saveFile(suggestedName: String, data: ByteArray): String? {
            val d = CompletableDeferred<Uri?>().also { pendingCreate = it }
            fileCreator.launch(suggestedName)
            val uri = d.await() ?: return null
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
            return displayName(uri)
        }

        override fun clipboardText(): String? =
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()

        override fun loadSetting(key: String): String? = prefs.getString(key, null)

        override fun saveSetting(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }

        override fun scheduleQuickFixReminder(at: java.time.Instant?) = ReminderReceiver.schedule(this@MainActivity, at)

        override fun detectNote() = rootUsb.note

        override fun canEject(fs: DeviceFs) = fs is LocalFs && fs.root == rootUsb.dir

        override fun eject(fs: DeviceFs) = canEject(fs) && rootUsb.eject()

        override fun openUrl(url: String) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            val scope = rememberCoroutineScope()
            val state = remember { AppState(platform, scope) }
            LaunchedEffect(Unit) {
                shared.collect { text -> if (text != null) { state.addText(text); shared.value = null } }
            }
            val dark = isSystemInDarkTheme()
            // Paint behind the status and navigation bars with the app's own background.
            Box(Modifier.fillMaxSize().background(if (dark) app.mapstotom.ui.DarkTokens.page else app.mapstotom.ui.LightTokens.page)) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { App(state, dark = dark) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared.value = it }
    }
}

package app.mapstotom

import app.mapstotom.core.DeviceFs

enum class FolderPurpose(val title: String) {
    DEVICE("Choose the TomTom drive"),
    POI_SOURCE("Choose a folder with POI files (.ov2, .bmp, .ogg)"),
    BACKUP_TARGET("Choose where to save the backup"),
    BACKUP_SOURCE("Choose a MapsToTom backup folder to restore"),
}

/** What the desktop and Android versions do differently. */
interface Platform {
    val isAndroid: Boolean
    /** TomToms found without asking (desktop: mounted drives; Android: the folder granted earlier). */
    fun detectDevices(): List<DeviceFs>
    suspend fun pickFolder(purpose: FolderPurpose): DeviceFs?
    /** Returns (file name, contents). */
    suspend fun pickFile(title: String): Pair<String, ByteArray>?
    /** Save a file where the user chooses; returns a description of where, or null if cancelled. */
    suspend fun saveFile(suggestedName: String, data: ByteArray): String?
    fun clipboardText(): String?
    fun openUrl(url: String)
    /** Why [detectDevices] found nothing, when the platform knows (Android root: no root, no disk). */
    fun detectNote(): String? = null
    /** True if this drive can be unmounted from the app (Android root mount). */
    fun canEject(fs: DeviceFs): Boolean = false
    /** Flush and unmount; true when the TomTom can be unplugged. */
    fun eject(fs: DeviceFs): Boolean = false
    /** Small persistent settings. */
    fun loadSetting(key: String): String? = null
    fun saveSetting(key: String, value: String?) {}
    /** Remind the user (notification) at [at] to update QuickGPSFix; null cancels. Android only. */
    fun scheduleQuickFixReminder(at: java.time.Instant?) {}
}

package app.mapstotom

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.mapstotom.core.DeviceFs
import app.mapstotom.core.nameOf
import app.mapstotom.core.parentOf

/**
 * A folder the user granted with Android's folder picker (the TomTom over USB OTG,
 * an SD card, or any folder). Lookups ignore case, like the TomTom's FAT drive.
 */
class SafFs(private val context: Context, val treeUri: Uri) : DeviceFs {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri) ?: error("Can't open $treeUri")
    private val cache = HashMap<String, DocumentFile?>()

    override val displayName: String get() = root.name ?: treeUri.lastPathSegment ?: "TomTom"

    private fun findChild(dir: DocumentFile, name: String): DocumentFile? {
        dir.findFile(name)?.let { return it }
        return dir.listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun doc(path: String): DocumentFile? {
        val p = path.trim('/')
        if (p.isEmpty()) return root
        cache[p]?.let { if (it.exists()) return it }
        val parent = doc(parentOf(p)) ?: return null
        return findChild(parent, nameOf(p)).also { cache[p] = it }
    }

    private fun dirFor(path: String): DocumentFile {
        val p = path.trim('/')
        if (p.isEmpty()) return root
        doc(p)?.let { if (it.isDirectory) return it }
        val parent = dirFor(parentOf(p))
        return (parent.createDirectory(nameOf(p)) ?: error("Can't create folder $p")).also { cache[p] = it }
    }

    override fun exists(path: String) = doc(path)?.exists() == true
    override fun isDir(path: String) = doc(path)?.isDirectory == true
    override fun list(path: String) = doc(path)?.takeIf { it.isDirectory }?.listFiles()?.mapNotNull { it.name }?.sorted() ?: emptyList()
    override fun size(path: String) = doc(path)?.length() ?: 0L
    override fun modified(path: String) = doc(path)?.lastModified()?.takeIf { it > 0 }

    override fun read(path: String): ByteArray? {
        val d = doc(path)?.takeIf { it.isFile } ?: return null
        return context.contentResolver.openInputStream(d.uri)?.use { it.readBytes() }
    }

    override fun write(path: String, data: ByteArray) {
        val p = path.trim('/')
        val existing = doc(p)?.takeIf { it.isFile }
        val target = existing ?: (dirFor(parentOf(p)).createFile("application/octet-stream", nameOf(p))
            ?: error("Can't create $p")).also { cache[p] = it }
        // Some providers append an extension to new files; fix the name if so.
        if (target.name != nameOf(p) && existing == null) target.renameTo(nameOf(p))
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(data); it.flush() }
            ?: error("Can't write $p")
    }

    override fun mkdirs(path: String) { dirFor(path) }

    override fun delete(path: String): Boolean {
        val ok = doc(path)?.delete() == true
        cache.keys.removeAll { it == path.trim('/') || it.startsWith(path.trim('/') + "/") }
        return ok
    }

    override fun rename(path: String, newName: String): Boolean {
        val ok = doc(path)?.renameTo(newName) == true
        cache.clear()
        return ok
    }

    override fun freeSpace(): Long? = runCatching {
        val pfd = context.contentResolver.openFileDescriptor(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)), "r")
        pfd?.use { android.system.Os.fstatvfs(it.fileDescriptor).let { s -> s.f_bavail * s.f_frsize } }
    }.getOrNull()
}

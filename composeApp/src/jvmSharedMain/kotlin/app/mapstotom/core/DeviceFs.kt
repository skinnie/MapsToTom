package app.mapstotom.core

import java.io.File
import java.io.FileOutputStream

/**
 * The TomTom's drive. Paths are relative to its root and use '/'.
 * Lookups ignore case, like the FAT file system on the TomTom.
 * Desktop uses [LocalFs]; Android uses a folder the user granted access to.
 */
interface DeviceFs {
    val displayName: String
    fun exists(path: String): Boolean
    fun isDir(path: String): Boolean
    /** Child names of a folder ("" = root). Empty if it doesn't exist. */
    fun list(path: String = ""): List<String>
    fun read(path: String): ByteArray?
    fun size(path: String): Long
    /** Write a file, creating parent folders, replacing any existing file. */
    fun write(path: String, data: ByteArray)
    fun mkdirs(path: String)
    /** Delete a file or a whole folder. */
    fun delete(path: String): Boolean
    fun rename(path: String, newName: String): Boolean
    /** Free bytes on the drive, or null if unknown. */
    fun freeSpace(): Long?
    /** Last change time (ms since 1970), or null if unknown. */
    fun modified(path: String): Long? = null
}

fun parentOf(path: String) = path.substringBeforeLast('/', "")
fun nameOf(path: String) = path.substringAfterLast('/')
fun join(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("/")

/** A folder on this computer, e.g. a mounted TomTom or a backup copy. */
class LocalFs(val root: File) : DeviceFs {
    override val displayName: String get() = root.path

    /** Resolve each path segment case-insensitively; missing segments keep their given spelling. */
    fun file(path: String): File {
        var f = root
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            val exact = File(f, seg)
            f = if (exact.exists()) exact
            else f.listFiles()?.firstOrNull { it.name.equals(seg, ignoreCase = true) } ?: exact
        }
        return f
    }

    override fun exists(path: String) = file(path).exists()
    override fun isDir(path: String) = file(path).isDirectory
    override fun list(path: String) = file(path).list()?.sorted() ?: emptyList()
    override fun read(path: String) = file(path).takeIf { it.isFile }?.readBytes()
    override fun size(path: String) = file(path).length()
    override fun write(path: String, data: ByteArray) {
        val f = file(path)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { it.write(data); it.flush(); it.fd.sync() }
    }
    override fun mkdirs(path: String) { file(path).mkdirs() }
    override fun delete(path: String) = file(path).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
    override fun rename(path: String, newName: String): Boolean {
        val f = file(path)
        return f.renameTo(File(f.parentFile, newName))
    }
    override fun freeSpace() = root.usableSpace.takeIf { it > 0 }
    override fun modified(path: String) = file(path).lastModified().takeIf { it > 0 }
}

// ------------------------------------------------------------------ TomTom layout

fun readBif(fs: DeviceFs): Map<String, String> {
    val raw = fs.read("ttgo.bif") ?: return emptyMap()
    val text = raw.toString(Charsets.UTF_8).removePrefix("﻿")
    return text.lines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}

fun isTomTomRoot(fs: DeviceFs) = fs.exists("ttgo.bif") || (fs.isDir("itn") && fs.exists("ttsystem"))

/** The active map folder (where POI .ov2 files go), or null. */
fun findMapFolder(fs: DeviceFs): String? {
    readBif(fs)["CurrentMap"]?.let { if (it.isNotEmpty() && fs.isDir(it)) return it }
    fs.read("currentmap.dat")?.let { raw ->
        val txt = raw.toString(Charsets.ISO_8859_1).trim('\u0000', ' ', '\r', '\n')
        val name = txt.replace('\\', '/').trimEnd('/').substringAfterLast('/')
        if (name.isNotEmpty() && fs.isDir(name)) return name
    }
    return fs.list().firstOrNull { d -> fs.isDir(d) && fs.list(d).any { it.lowercase().endsWith(".pna") } }
}

/** Write itn/<name>.itn and return its path. */
fun writeItn(fs: DeviceFs, name: String, points: List<Point>, firstIsDeparture: Boolean = true): String {
    val path = "itn/" + safeFilename(name) + ".itn"
    fs.write(path, buildItn(points, firstIsDeparture))
    return path
}

/** Add points to <mapDir>/<category>.ov2, skipping ones already there (same name, within ~1 m).
 *  Returns (path, number added). Adds a pin icon if the category has none. */
fun writeOv2(fs: DeviceFs, mapDir: String, category: String, points: List<Point>): Pair<String, Int> {
    val base = join(mapDir, safeFilename(category))
    val path = "$base.ov2"
    val existing = fs.read(path) ?: ByteArray(0)
    fun key(name: String, lat: Double, lon: Double) = Triple(name, Math.rint(lat * 1e5), Math.rint(lon * 1e5))
    val have = readOv2(existing).map { key(it.name, it.lat, it.lon) }.toMutableSet()
    val new = points.filter { have.add(key(safeName(it.name), it.lat, it.lon)) }
    if (new.isNotEmpty() || existing.isEmpty()) {
        val out = java.io.ByteArrayOutputStream()
        out.write(existing); new.forEach { out.write(ov2Record(it)) }
        fs.write(path, out.toByteArray())
    }
    if (!fs.exists("$base.bmp")) fs.write("$base.bmp", poiIconBmp())
    return path to new.size
}

/** Mounted TomToms on this computer (desktop only). */
fun findTomTomRoots(): List<File> {
    val os = System.getProperty("os.name").lowercase()
    val cands = when {
        os.startsWith("win") -> ('A'..'Z').map { File("$it:\\") }
        os.contains("mac") -> File("/Volumes").listFiles()?.toList() ?: emptyList()
        else -> {
            val user = System.getProperty("user.name") ?: ""
            listOf("/media/$user", "/run/media/$user", "/media", "/mnt").flatMap { File(it).listFiles()?.toList() ?: emptyList() }
        }
    }
    return cands.filter { runCatching { it.isDirectory && isTomTomRoot(LocalFs(it)) }.getOrDefault(false) }.distinct()
}

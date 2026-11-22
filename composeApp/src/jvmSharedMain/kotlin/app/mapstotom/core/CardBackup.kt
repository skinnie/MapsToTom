package app.mapstotom.core

/*
 * A full, checked copy of a TomTom's card. On these units the card holds the system (ttsystem), the navigation program,
 * the GPS programs, the maps and the settings, so this is the device's firmware backup too. Every file is read back
 * after writing, and a manifest of checksums lets a restore refuse a damaged backup before it changes anything.
 */

const val BACKUP_NOTE = "MapsToTom-backup.txt"
const val BACKUP_MANIFEST = "MapsToTom-manifest.txt"

/** Every file to copy, without the operating system's own folders or a backup's bookkeeping. */
fun listCardFiles(fs: DeviceFs): List<String> = walkCard(fs).first

/** Every folder, so that empty ones come back too: NavCore and the system expect some of them. */
fun listCardFolders(fs: DeviceFs): List<String> = walkCard(fs).second

private fun walkCard(fs: DeviceFs): Pair<List<String>, List<String>> {
    val files = mutableListOf<String>(); val folders = mutableListOf<String>()
    fun walk(p: String) {
        for (n in fs.list(p)) {
            if (n == "System Volume Information" || n.startsWith(".Trash") || n == "\$RECYCLE.BIN") continue
            if (p.isEmpty() && (n == BACKUP_NOTE || n == BACKUP_MANIFEST)) continue
            val c = join(p, n)
            if (fs.isDir(c)) { folders += c; walk(c) } else files += c
        }
    }
    walk("")
    return files to folders
}

private const val FOLDER_ROW = "folder"

/** Copies one file, then reads the copy back. Keeps only one copy in memory at a time: map files reach hundreds of MB. */
private fun copyChecked(from: DeviceFs, path: String, to: DeviceFs, target: String): Pair<String, Int> {
    val (sum, size) = (from.read(path) ?: error("Couldn't read $path")).let { to.write(target, it); md5(it) to it.size }
    val back = to.read(target)?.let(::md5)
    check(back == sum) { "The copy of $path doesn't match the original" }
    return sum to size
}

/**
 * Backs [from] up into [folder] on [to]. The note that marks a finished backup is written last, so an interrupted one
 * is never taken for a good one. Returns the number of files.
 */
fun backupCard(from: DeviceFs, to: DeviceFs, folder: String, note: String, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
    val files = listCardFiles(from)
    val need = files.sumOf { from.size(it) }
    to.freeSpace()?.let { free -> require(free > need) { "The backup needs %,d MB, and there are %,d MB free there".format(need / 1_000_000, free / 1_000_000) } }
    val manifest = StringBuilder()
    for (d in listCardFolders(from)) {
        to.mkdirs(join(folder, d))
        manifest.append(FOLDER_ROW).append("  0  ").append(d).append('\n')
    }
    files.forEachIndexed { i, f ->
        progress(i, files.size)
        val (sum, size) = copyChecked(from, f, to, join(folder, f))
        manifest.append(sum).append("  ").append(size).append("  ").append(f).append('\n')
    }
    to.write(join(folder, BACKUP_MANIFEST), manifest.toString().toByteArray())
    to.write(join(folder, BACKUP_NOTE), "$note\n${files.size} files, ${need / 1_000_000} MB, each checked after copying\n".toByteArray())
    progress(files.size, files.size)
    return files.size
}

/** [checked] is false for an older backup without a manifest. */
class BackupCheck(val files: Int, val missing: List<String>, val damaged: List<String>, val checked: Boolean) {
    val ok get() = missing.isEmpty() && damaged.isEmpty()
}

fun verifyBackup(src: DeviceFs, progress: (Int, Int) -> Unit = { _, _ -> }): BackupCheck {
    val text = src.read(BACKUP_MANIFEST)?.toString(Charsets.UTF_8) ?: return BackupCheck(listCardFiles(src).size, emptyList(), emptyList(), false)
    val rows = text.lines().filter { it.isNotBlank() }.map { it.split("  ", limit = 3) }
    val missing = mutableListOf<String>(); val damaged = mutableListOf<String>()
    rows.forEachIndexed { i, r ->
        progress(i, rows.size)
        val (sum, size, path) = r
        when {
            sum == FOLDER_ROW -> if (!src.isDir(path)) missing += path
            !src.exists(path) -> missing += path
            src.size(path) != size.toLong() || src.read(path)?.let(::md5) != sum -> damaged += path
        }
    }
    return BackupCheck(rows.count { it[0] != FOLDER_ROW }, missing, damaged, true)
}

/**
 * Copies a backup onto the card, after checking it. A damaged backup changes nothing. Files that exist only on the card
 * are kept. Returns the number of files.
 */
fun restoreCard(src: DeviceFs, to: DeviceFs, progress: (Int, Int, Boolean) -> Unit = { _, _, _ -> }): Int {
    require(isTomTomRoot(src) || src.exists(BACKUP_NOTE)) { "That folder isn't a TomTom backup" }
    if (src.exists(BACKUP_MANIFEST)) require(src.exists(BACKUP_NOTE)) { "That backup was never finished" }
    val check = verifyBackup(src) { i, n -> progress(i, n, false) }
    require(check.ok) {
        "This backup is damaged (${check.missing.size} missing, ${check.damaged.size} changed, for example " +
            "${(check.missing + check.damaged).first()}), so nothing was restored"
    }
    val files = listCardFiles(src)
    val extra = files.sumOf { maxOf(0L, src.size(it) - (if (to.exists(it)) to.size(it) else 0L)) }
    to.freeSpace()?.let { free -> require(free > extra) { "The TomTom needs %,d MB more free space".format((extra - free) / 1_000_000) } }
    listCardFolders(src).forEach(to::mkdirs)
    files.forEachIndexed { i, f -> progress(i, files.size, true); copyChecked(src, f, to, f) }
    return files.size
}

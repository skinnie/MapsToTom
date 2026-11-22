package app.mapstotom.link

import java.io.File
import java.util.zip.CRC32

/**
 * Files waiting to go to the TomTom over the car link. The helper on its card (tools/carsync/m2tsync.c) asks for them
 * one at a time and confirms each one, so a file stays queued until it has really been saved.
 */
class CarOutbox(private val dir: File) {
    init { CarLinkState.setQueued(ids().size) }

    /** REPLACE the file, ADD to its end, or write it only IF_MISSING. */
    enum class Mode(val code: String) { REPLACE(""), ADD("a"), IF_MISSING("n") }

    class Item(val id: Long, val path: String, val data: ByteArray, val mode: Mode = Mode.REPLACE)

    /**
     * Queues [data] for [path] on the card ("@map/..." = inside the active map folder). A newer whole file for the same
     * path replaces one still waiting; additions all stay, in order.
     */
    @Synchronized fun put(path: String, data: ByteArray, mode: Mode = Mode.REPLACE): Long {
        require(validPath(path)) { "Can't send $path to the TomTom" }
        require(data.size <= MAX_BYTES) { "$path is too big to send over Bluetooth" }
        require(mode == Mode.REPLACE || path !in GPS_PATHS) { "$path can only be replaced whole" }
        dir.mkdirs()
        if (mode == Mode.REPLACE) ids().filter { File(dir, "$it.path").readText() == path && modeOf(it) == Mode.REPLACE }.forEach(::remove)
        val id = (ids().maxOrNull() ?: 0L) + 1
        File(dir, "$id.bin").writeBytes(data)
        if (mode != Mode.REPLACE) File(dir, "$id.mode").writeText(mode.code)
        File(dir, "$id.path").writeText(path) // last: an entry without it isn't in the queue yet
        CarLinkState.setQueued(ids().size)
        return id
    }

    /** Queues whole files to arrive in this order. Waiting copies of any of them go first, so an old set can't mix with this one. */
    @Synchronized fun putInOrder(files: List<Pair<String, ByteArray>>) {
        files.forEach { (path, data) -> require(validPath(path) && data.size <= MAX_BYTES) { "Can't send $path to the TomTom" } }
        val paths = files.map { it.first }.toSet()
        ids().filter { File(dir, "$it.path").readText() in paths && modeOf(it) == Mode.REPLACE }.forEach(::remove)
        files.forEach { (path, data) -> put(path, data) }
    }

    /** The next file for the helper. An older helper can't save GPS files ([gps] false): they wait without holding up the rest. */
    @Synchronized fun next(gps: Boolean = false): Item? =
        ids().sorted().firstOrNull { gps || File(dir, "$it.path").readText() !in GPS_PATHS }
            ?.let { Item(it, File(dir, "$it.path").readText(), File(dir, "$it.bin").readBytes(), modeOf(it)) }

    @Synchronized fun done(id: Long) { remove(id); CarLinkState.setQueued(ids().size) }

    @Synchronized fun size() = ids().size

    private fun ids() = dir.listFiles { f -> f.name.endsWith(".path") }.orEmpty().mapNotNull { it.name.removeSuffix(".path").toLongOrNull() }

    private fun modeOf(id: Long): Mode {
        val code = File(dir, "$id.mode").takeIf { it.isFile }?.readText() ?: return Mode.REPLACE
        return Mode.entries.first { it.code == code }
    }

    private fun remove(id: Long) { File(dir, "$id.path").delete(); File(dir, "$id.mode").delete(); File(dir, "$id.bin").delete() }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024

        private val open = HashMap<String, CarOutbox>()

        /** The one queue for [dir], shared by the screens and the car link. */
        @Synchronized fun at(dir: File) = open.getOrPut(dir.absolutePath) { CarOutbox(dir) }

        /** The only GPS files the helper may replace. */
        val GPS_PATHS = setOf("ephem/lto.dat", "ephem/packedephemeris.ee", "ephem/ee_meta.txt")

        /** The same rule as the helper: inside the card, and only POI, icon, route and QuickGPSFix files. */
        fun validPath(p: String) = p in GPS_PATHS || p.length in 5..200 && !p.startsWith("/") && ".." !in p &&
            p.none { it.code < 32 || it.code == 127 || it == '\\' || it == ':' } &&
            p.substringAfterLast('.', "").lowercase() in setOf("ov2", "bmp", "itn")

        /** "M2T1 <id> <crc32> <size> <path>\n" or "M2T2 <id> <crc32> <size> <a|n> <path>\n" + the data; "M2T0\n" = nothing waiting. */
        fun frame(item: Item?): ByteArray {
            if (item == null) return "M2T0\n".toByteArray()
            val crc = CRC32().apply { update(item.data) }.value
            val head = if (item.mode == Mode.REPLACE) "M2T1 ${item.id} ${"%08x".format(crc)} ${item.data.size} ${item.path}\n"
                else "M2T2 ${item.id} ${"%08x".format(crc)} ${item.data.size} ${item.mode.code} ${item.path}\n"
            return head.toByteArray(Charsets.UTF_8) + item.data
        }
    }
}

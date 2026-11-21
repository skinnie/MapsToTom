package app.mapstotom.link

import java.io.File
import java.util.zip.CRC32

/**
 * Files waiting to go to the TomTom over the car link. The helper on its card (tools/carsync/m2tsync.c) asks for them
 * one at a time and confirms each one, so a file stays queued until it has really been saved.
 */
class CarOutbox(private val dir: File) {
    class Item(val id: Long, val path: String, val data: ByteArray)

    /** Queues [data] for [path] on the card. A newer file for the same path replaces one still waiting. */
    @Synchronized fun put(path: String, data: ByteArray): Long {
        require(validPath(path)) { "Can't send $path to the TomTom" }
        require(data.size <= MAX_BYTES) { "$path is too big to send over Bluetooth" }
        dir.mkdirs()
        ids().filter { File(dir, "$it.path").readText() == path }.forEach(::remove)
        val id = (ids().maxOrNull() ?: 0L) + 1
        File(dir, "$id.bin").writeBytes(data)
        File(dir, "$id.path").writeText(path) // last: an entry without it isn't in the queue yet
        return id
    }

    @Synchronized fun next(): Item? = ids().minOrNull()?.let { Item(it, File(dir, "$it.path").readText(), File(dir, "$it.bin").readBytes()) }

    @Synchronized fun done(id: Long) = remove(id)

    @Synchronized fun size() = ids().size

    private fun ids() = dir.listFiles { f -> f.name.endsWith(".path") }.orEmpty().mapNotNull { it.name.removeSuffix(".path").toLongOrNull() }

    private fun remove(id: Long) { File(dir, "$id.path").delete(); File(dir, "$id.bin").delete() }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024

        /** The same rule as the helper: inside the card, and only POI, icon and route files. */
        fun validPath(p: String) = p.length in 5..200 && !p.startsWith("/") && ".." !in p &&
            p.none { it.code < 32 || it.code == 127 || it == '\\' || it == ':' } &&
            p.substringAfterLast('.', "").lowercase() in setOf("ov2", "bmp", "itn")

        /** "M2T1 <id> <crc32> <size> <path>\n" + the file, or "M2T0\n" when nothing is waiting. */
        fun frame(item: Item?): ByteArray {
            if (item == null) return "M2T0\n".toByteArray()
            val crc = CRC32().apply { update(item.data) }.value
            return "M2T1 ${item.id} ${"%08x".format(crc)} ${item.data.size} ${item.path}\n".toByteArray(Charsets.UTF_8) + item.data
        }
    }
}

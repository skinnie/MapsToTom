package app.mapstotom.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/** Reader for Microsoft CAB files, as served by TomTom's QuickGPSFix server.
 *  Supports uncompressed and MSZIP folders (what TomTom uses). Returns name -> bytes. */
object Cab {
    fun extract(cab: ByteArray): Map<String, ByteArray> {
        val b = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN)
        require(cab.size > 36 && b.getInt(0) == 0x4643534D) { "not a CAB file" } // "MSCF"
        val coffFiles = b.getInt(16)
        val cFolders = b.getShort(26).toInt() and 0xffff
        val cFiles = b.getShort(28).toInt() and 0xffff
        val flags = b.getShort(30).toInt()
        var pos = 36
        var cbCFFolder = 0
        var cbCFData = 0
        if (flags and 4 != 0) { // reserve fields present
            val cbCFHeader = b.getShort(36).toInt() and 0xffff
            cbCFFolder = cab[38].toInt() and 0xff
            cbCFData = cab[39].toInt() and 0xff
            pos = 40 + cbCFHeader
        }
        if (flags and 1 != 0) { pos = skipZ(cab, pos); pos = skipZ(cab, pos) } // prev cabinet
        if (flags and 2 != 0) { pos = skipZ(cab, pos); pos = skipZ(cab, pos) } // next cabinet
        // Folders: decompress each into one stream.
        val folders = (0 until cFolders).map { fi ->
            val o = pos + fi * (8 + cbCFFolder)
            val coffCabStart = b.getInt(o)
            val cCFData = b.getShort(o + 4).toInt() and 0xffff
            val typeCompress = b.getShort(o + 6).toInt() and 0x0f
            val out = ByteArrayOutputStream()
            var d = coffCabStart
            var dict: ByteArray? = null
            repeat(cCFData) {
                val cbData = b.getShort(d + 4).toInt() and 0xffff
                val cbUncomp = b.getShort(d + 6).toInt() and 0xffff
                val start = d + 8 + cbCFData
                when (typeCompress) {
                    0 -> out.write(cab, start, cbData)
                    1 -> { // MSZIP: "CK" + raw deflate; each block may use the previous 32 KB as dictionary
                        require(cab[start] == 'C'.code.toByte() && cab[start + 1] == 'K'.code.toByte()) { "bad MSZIP block" }
                        val inf = Inflater(true)
                        if (dict != null) inf.setDictionary(dict)
                        inf.setInput(cab, start + 2, cbData - 2)
                        val buf = ByteArray(cbUncomp)
                        var n = 0
                        while (n < cbUncomp) {
                            val r = inf.inflate(buf, n, cbUncomp - n)
                            if (r == 0) {
                                if (inf.needsDictionary() && dict != null) inf.setDictionary(dict) else break
                            }
                            n += r
                        }
                        inf.end()
                        out.write(buf, 0, n)
                        dict = buf.copyOf(n)
                    }
                    else -> error("unsupported CAB compression $typeCompress")
                }
                d = start + cbData
            }
            out.toByteArray()
        }
        // Files
        val files = LinkedHashMap<String, ByteArray>()
        var f = coffFiles
        repeat(cFiles) {
            val cbFile = b.getInt(f)
            val uoffFolderStart = b.getInt(f + 4)
            val iFolder = b.getShort(f + 8).toInt() and 0xffff
            val nameEnd = (f + 16 until cab.size).first { cab[it] == 0.toByte() }
            val name = String(cab, f + 16, nameEnd - f - 16, Charsets.ISO_8859_1).replace('\\', '/')
            val data = folders[iFolder.coerceAtMost(folders.size - 1)]
            files[name] = data.copyOfRange(uoffFolderStart, uoffFolderStart + cbFile)
            f = nameEnd + 1
        }
        return files
    }

    private fun skipZ(a: ByteArray, p: Int): Int { var i = p; while (a[i] != 0.toByte()) i++; return i + 1 }
}

/** Reads files out of the TomTom's `ttsystem` (kernel + gzip'd cpio ramdisk). */
object TtSystem {
    /** Returns the ramdisk files whose path matches [want] (e.g. "etc/glconfig.xml"). */
    fun extract(ttsystem: ByteArray, want: (String) -> Boolean): Map<String, ByteArray> {
        var i = 0
        while (true) {
            i = indexOf(ttsystem, byteArrayOf(0x1f, 0x8b.toByte(), 0x08), i)
            if (i < 0) return emptyMap()
            val data = runCatching { GZIPInputStream(ttsystem.inputStream(i, ttsystem.size - i)).readBytes() }.getOrNull()
            if (data != null && data.size > 6 && String(data, 0, 6, Charsets.US_ASCII) == "070701") return cpio(data, want)
            i++
        }
    }

    private fun cpio(d: ByteArray, want: (String) -> Boolean): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var p = 0
        fun hex(off: Int) = String(d, p + off, 8, Charsets.US_ASCII).toLong(16).toInt()
        while (p + 110 <= d.size && String(d, p, 6, Charsets.US_ASCII) == "070701") {
            val fileSize = hex(54)
            val nameSize = hex(94)
            val name = String(d, p + 110, nameSize - 1, Charsets.US_ASCII).removePrefix("./").removePrefix("/")
            if (name == "TRAILER!!!") break
            val dataStart = (p + 110 + nameSize + 3) and 3.inv()
            if (want(name)) out[name] = d.copyOfRange(dataStart, dataStart + fileSize)
            p = (dataStart + fileSize + 3) and 3.inv()
        }
        return out
    }

    private fun indexOf(a: ByteArray, pat: ByteArray, from: Int): Int {
        outer@ for (i in from..a.size - pat.size) {
            for (j in pat.indices) if (a[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }
}

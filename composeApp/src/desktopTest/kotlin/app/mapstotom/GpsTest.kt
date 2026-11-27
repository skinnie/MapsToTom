package app.mapstotom

import app.mapstotom.core.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GpsTest {
    private fun tmp(): LocalFs = LocalFs(Files.createTempDirectory("m2t").toFile().also { it.deleteOnExit() })

    @Test fun chipFromFirmwareString() {
        val fs = tmp()
        fun chipFor(fw: String): Chip { fs.write("ttgo.bif", "GPSFirmwareVersion=$fw\n".toByteArray()); return detectChip(fs).chip }
        assertEquals(Chip.GL1, chipFor("185.5001.041"))
        assertEquals(Chip.GL2, chipFor("2.16.201 354431"))
        assertEquals(Chip.SIRF, chipFor("GSW3.2.4TT3"))
        assertTrue(sirfNeedsUpdate("GSW3.2.4TT3_3.1.00.07-C23P1.00"))
        assertFalse(sirfNeedsUpdate("GSW3.2.5TT3-CRC32"))
    }

    @Test fun ltoChecksum() {
        val body = ByteArray(200) { it.toByte() }
        val lto = ByteArray(0x1c) + body
        lto[0] = 0xff.toByte(); lto[1] = 0xca.toByte(); lto[2] = 0xde.toByte()
        java.security.MessageDigest.getInstance("MD5").digest(body).copyInto(lto, 0x0c)
        assertTrue(ltoValid(lto))
        lto[100] = (lto[100] + 1).toByte()
        assertFalse(ltoValid(lto))
    }

    /** A small lto.dat in the Global Locate layout: header, one other chunk, then the date table. */
    private fun fakeLto(epochs: List<Long>): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        fun u32(v: Long) { for (i in 0..3) b.write(((v shr (8 * i)) and 0xff).toInt()) }
        u32(0x00decaff); u32(0); u32(1); repeat(16) { b.write(0) }          // magic, size (unchecked), version, MD5
        u32(0x1234); u32(4); u32(0xabcdef)                                   // a chunk the reader must skip
        u32(0xbbcc00); u32(24L + 8 * epochs.size + 32); u32(2); u32(1); u32(1); u32(7); u32(9)  // tag, len, kind, mode, 1 pair
        u32(epochs.size.toLong()); epochs.forEachIndexed { i, t -> u32(t); u32(0x100L + i) }
        repeat(32) { b.write(it) }                                           // "block data"
        val d = b.toByteArray()
        java.security.MessageDigest.getInstance("MD5").digest(d.copyOfRange(0x1c, d.size)).copyInto(d, 0x0c)
        return d
    }

    @Test fun ltoWeekShift() {
        val week = 604_800L
        val lto = fakeLto(listOf(2442 * week + 410416, 2443 * week + 399616))
        assertEquals(listOf(2442 * week + 410416, 2443 * week + 399616), ltoEpochs(lto).map { it.second })
        val moved = shiftLto(lto, -1024)
        assertEquals(listOf(1418 * week + 410416, 1419 * week + 399616), ltoEpochs(moved).map { it.second })
        assertTrue(ltoValid(moved.also { it[0] = 0xff.toByte(); it[1] = 0xca.toByte(); it[2] = 0xde.toByte(); it[3] = 0 }.let { fixMd5(it) }))
        assertEquals(lto.size, moved.size)

        assertEquals(1418, engineWeekFrom1999(2442))
        assertEquals(2047, engineWeekFrom1999(2047))
        assertEquals(1024, engineWeekFrom1999(3072))   // the next rollover, Nov 2038
        assertEquals(-1024, ltoWeekShift(lto, "Global Locate GLL 185.5001.041"))
        assertEquals(-1024, ltoWeekShift(lto, "Global Locate GLL 185.5.039"))  // NavCore 7.x engine
        assertEquals(0, ltoWeekShift(lto, "Global Locate GLL 185.4.000"))   // untested version: unchanged
        assertEquals(0, ltoWeekShift(lto, null))
    }

    private fun fixMd5(d: ByteArray): ByteArray {
        java.security.MessageDigest.getInstance("MD5").digest(d.copyOfRange(0x1c, d.size)).copyInto(d, 0x0c); return d
    }

    @Test fun carLinkQuickFixPutsTheExpiryLastAndMovesDatesLikeUsb() {
        val week = 604_800L
        val lto = fakeLto(listOf(2442 * week))
        val q = QuickFix(mapOf("lto.dat" to lto, "ee_meta.txt" to "Expiry=1".toByteArray()), null)
        val (files, note) = quickFixCarFiles(q, Chip.GL1, "Global Locate GLL 185.5001.041")
        assertEquals(listOf("ephem/lto.dat", "ephem/ee_meta.txt"), files.map { it.first })
        assertNotNull(note); assertTrue(ltoValid(files[0].second))
        assertEquals(1418, (ltoEpochs(files[0].second).first().second / week).toInt())
        val (gl2, trimmed) = quickFixCarFiles(q, Chip.GL2, null)
        assertNotNull(trimmed); assertContentEquals(trimLtoForGl2(lto), gl2[0].second)
        val sirf = QuickFix(mapOf("packedephemeris.ee" to byteArrayOf(1), "ee_meta.txt" to byteArrayOf(2)), null)
        assertEquals("ephem/packedephemeris.ee", quickFixCarFiles(sirf, Chip.SIRF, null).first[0].first)
        assertFails { quickFixCarFiles(q, Chip.AR1520, null) }
        assertFails { quickFixCarFiles(q, Chip.SIRF, null) }
    }

    @Test fun removedQuickFixStaysOffUntilSentAgain() {
        val fs = tmp()
        val q = QuickFix(mapOf("lto.dat" to fakeLto(listOf(2442 * 604_800L)), "ee_meta.txt" to "Expiry=1796159528".toByteArray()), null)
        assertFalse(quickFixTurnedOff(fs), "never removed")
        installQuickFix(fs, q, Chip.GL2)
        removeQuickFix(fs)
        assertTrue(quickFixTurnedOff(fs))
        assertNull(fs.read("ephem/lto.dat")); assertNull(fs.read("ephem/ee_meta.txt"))
        installQuickFix(fs, q, Chip.GL2)
        assertFalse(quickFixTurnedOff(fs)); assertFalse(fs.exists(QUICKFIX_OFF), "sending it on purpose deletes the note")
        // The car link writes the files but can't delete the note: a QuickGPSFix on the card still means it's on.
        removeQuickFix(fs); fs.write("ephem/lto.dat", q.files.getValue("lto.dat"))
        assertFalse(quickFixTurnedOff(fs))
    }

    @Test fun installQuickFixAdjustsOnlyTestedGl1() {
        val week = 604_800L
        val gpsNow = 2442 * week + 500_000
        val lto = fakeLto(listOf(gpsNow - 100_000, gpsNow + 400_000))
        val expiryUnix = gpsNow + 7 * 86_400 + 315_964_800L - 18
        val q = QuickFix(mapOf("lto.dat" to lto, "ee_meta.txt" to "Expiry=$expiryUnix".toByteArray()), java.time.Instant.ofEpochSecond(expiryUnix))
        assertEquals(0, ltoWeekOffset(q))

        fun deviceWith(gltt: String?) = tmp().also { fs -> gltt?.let { fs.write("bin/gltt", "\u0000junk@(#)Global Locate GLL ver. $it built Mar 24 2008\u0000".toByteArray()) } }

        val fs = deviceWith("185.5001.041")
        assertEquals("Global Locate GLL 185.5001.041", gl1EngineVersion(fs))
        assertNotNull(installQuickFix(fs, q, Chip.GL1))
        val onDevice = quickFixOnDevice(fs)
        assertEquals(-1024, ltoWeekOffset(onDevice))
        assertEquals(1418, (ltoEpochs(onDevice.files["lto.dat"]!!).first().second / week).toInt())

        for ((gltt, chip) in listOf("185.4.000" to Chip.GL1, null to Chip.GL1, null to Chip.SIRF)) {
            val other = deviceWith(gltt)
            assertNull(installQuickFix(other, q, chip), "$gltt $chip")
            assertContentEquals(lto, other.read("ephem/lto.dat"), "$gltt $chip")
        }
    }

    /** A GL2 gets the file without the chunk types added since 2019 and with the SBAS list cut to the 2019 PRNs. */
    @Test fun gl2GetsTrimmedLto() {
        val b = java.io.ByteArrayOutputStream()
        fun u32(v: Long) { for (i in 0..3) b.write(((v shr (8 * i)) and 0xff).toInt()) }
        fun u16(v: Int) { b.write(v and 0xff); b.write((v shr 8) and 0xff) }
        u32(0x00decaff); u32(0); u32(1); repeat(16) { b.write(0) }
        u32(0xbbcc00); u32(16); u32(2); u32(1); u32(0); u32(0)                 // no pairs, no blocks
        u32(0x55bbaa55); u32(4 + 4 * 5); u32(1L or (5L shl 16))
        for ((prn, lon) in listOf(121 to 50, 129 to 1400, 131 to -100, 135 to -1330, 138 to -1070)) { u16(prn); u16(lon) }
        u32(0x43477539); u32(3); b.write(1); b.write(2); b.write(3)          // a chunk type from after 2019
        val d = b.toByteArray()
        java.security.MessageDigest.getInstance("MD5").digest(d.copyOfRange(0x1c, d.size)).copyInto(d, 0x0c)
        val q = QuickFix(mapOf("lto.dat" to d, "ee_meta.txt" to "Expiry=1".toByteArray()), null)

        val fs = tmp()
        assertNotNull(installQuickFix(fs, q, Chip.GL2))
        val t = fs.read("ephem/lto.dat")!!
        assertTrue(ltoValid(t))
        assertEquals(t.size - 8, (t[4].toInt() and 0xff) or ((t[5].toInt() and 0xff) shl 8))
        assertEquals(d.size - 11 - 8, t.size)                                 // 2 SBAS entries and the new chunk gone
        assertContentEquals(t, trimLtoForGl2(t))
        val sbas = t.copyOfRange(0x1c + 24 + 8, t.size)
        assertEquals(listOf(129, 135, 138), (0 until 3).map { (sbas[4 + 4 * it].toInt() and 0xff) })
        assertEquals(3, sbas[2].toInt())
    }

    @Test fun powerOffToggle() {
        val fs = tmp()
        setPowerOff(fs, true); assertTrue(powerOffEnabled(fs))
        setPowerOff(fs, false); assertFalse(powerOffEnabled(fs))
        setStayOn(fs, true); assertTrue(stayOnEnabled(fs)); assertEquals(0, fs.read("noautosuspend.dat")!!.size)
        setStayOn(fs, false); assertFalse(stayOnEnabled(fs))
    }
}

/** Uses real files from this machine when they exist; skipped elsewhere. */
class GpsIntegrationTest {
    private val oneBackup = File("/media/skinnie/05a64eef-3fdf-450f-aaf7-09748f80297c/tomtom-backup-2026-10-25/files")
    private val goTtsystem = File(System.getProperty("m2t.goTtsystem") ?: "/nonexistent")

    @Test fun realOneV4() {
        if (!oneBackup.isDirectory) return
        val fs = LocalFs(oneBackup)
        val chip = detectChip(fs)
        assertEquals(Chip.GL1, chip.chip, chip.reason)
        assertEquals(TtconvState.INSTALLED, ttconvState(fs))
        assertTrue(glgpsState(fs).fixed)
        assertEquals("Iberia_1175_13260", findMapFolder(fs))
    }

    /** The app's shift must give exactly the file that worked on the real ONE v4 on 30 Oct 2026. */
    @Test fun ltoShiftMatchesDeviceTest() {
        val t = File("/media/skinnie/05a64eef-3fdf-450f-aaf7-09748f80297c/tomtom-fixes/one-v4")
        val orig = File(t, "lto-archive/lto-exp20261103-2217.dat")
        val tested = File(t, "gps-test/lto-shifted/lto.dat")
        val gltt = File(oneBackup, "bin/gltt")
        if (!orig.isFile || !tested.isFile || !gltt.isFile) return
        val engine = glgpsVersion(gltt.readBytes())
        assertTrue(engine in LTO_WEEK_BUG_ENGINES, "$engine")
        val shift = ltoWeekShift(orig.readBytes(), engine)
        assertEquals(-1024, shift)
        assertContentEquals(tested.readBytes(), shiftLto(orig.readBytes(), shift))
        assertTrue(ltoValid(tested.readBytes()))
    }

    /** Without bin/gltt on the card the version comes from inside ttsystem (here NavCore 7.900's). */
    /** The app's trim must give exactly the file that worked on the real GO 950 Live on 27 Nov 2026 (test B). */
    @Test fun gl2TrimMatchesDeviceTest() {
        val orig = File("/media/skinnie/05a64eef-3fdf-450f-aaf7-09748f80297c/go950-device-backup-2026-11-26/gps-log-test/drive2-qgf/lto.dat")
        val tested = File("/media/skinnie/05a64eef-3fdf-450f-aaf7-09748f80297c/tomtom-fixes/go950-gps/variants/B-drop-new-sbas4.dat")
        if (!orig.isFile || !tested.isFile) return
        assertContentEquals(tested.readBytes(), trimLtoForGl2(orig.readBytes()))
    }

    @Test fun gl1EngineFromRealTtsystem() {
        val ttsystem = File("/media/skinnie/05a64eef-3fdf-450f-aaf7-09748f80297c/tomtom-fixes/old-navcores/x/navcore_7.900.9180.one-onev3/ttsystem")
        if (!ttsystem.isFile) return
        val dir = Files.createTempDirectory("m2t").toFile()
        ttsystem.copyTo(File(dir, "ttsystem"))
        assertEquals("Global Locate GLL 185.5.039", gl1EngineVersion(LocalFs(dir)))
    }

    @Test fun clockFixFromRealGoTtsystem() {
        if (!goTtsystem.isFile) return
        val dir = Files.createTempDirectory("m2t").toFile()
        goTtsystem.copyTo(File(dir, "ttsystem"))
        val fs = LocalFs(dir)
        installClockFix(fs, log = true)
        val xml = fs.read(CLOCK_FIX_PATH)!!.toString(Charsets.UTF_8)
        assertTrue("ignore_time=\"true\"" in xml && "cLogEnabled=\"true\"" in xml)
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertTrue(clockFixInstalled(fs))
    }

    @Test fun liveQuickFixDownload() {
        if (System.getProperty("m2t.online") != "1") return
        for (chip in listOf(Chip.GL1, Chip.SIRF)) {
            val q = downloadQuickFix(chip, RealNet())
            assertNotNull(q.expiry, "expiry for $chip")
            assertTrue(q.expiry!!.isAfter(java.time.Instant.now()), "fresh for $chip")
        }
    }
}

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
        u32(0xbbcc00); u32(0); u32(2); u32(1); u32(1); u32(7); u32(9)        // tag, len, kind, mode, 1 pair
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

        for ((gltt, chip) in listOf("185.5001.041" to Chip.GL2, "185.4.000" to Chip.GL1, null to Chip.GL1)) {
            val other = deviceWith(gltt)
            assertNull(installQuickFix(other, q, chip), "$gltt $chip")
            assertContentEquals(lto, other.read("ephem/lto.dat"), "$gltt $chip")
        }
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

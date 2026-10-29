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

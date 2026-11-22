package app.mapstotom

import app.mapstotom.core.*
import java.io.File
import kotlin.test.*

class KitchenBackupTest {
    private fun fs(n: String) = LocalFs(File("build/tmp/m2t-kitchen/$n").absoluteFile.apply { deleteRecursively(); mkdirs() })

    private fun card(n: String) = fs(n).also {
        it.write("ttgo.bif", "[TomTomGo]\nApplicationVersionVersionNumber=9541\nApplicationVersion=3145439.2\nRamDiskVersion=20181211\n".toByteArray())
        it.write("ttsystem", ByteArray(5000) { i -> i.toByte() })
        it.write("Map/cline.dat", ByteArray(20000) { i -> (i * 7).toByte() })
        it.write("itn/a b.itn", "x".toByteArray())
        it.write("System Volume Information/skip", byteArrayOf(1))
    }

    @Test fun backupThenRestoreGivesTheSameCard() {
        val c = card("c1"); val out = fs("o1")
        val n = backupCard(c, out, "bk", "note")
        assertEquals(4, n); assertFalse(out.exists("bk/System Volume Information"))
        val src = LocalFs(File(out.root, "bk"))
        assertTrue(verifyBackup(src).run { ok && checked })
        val target = fs("t1"); target.write("only-here.txt", byteArrayOf(5))
        assertEquals(4, restoreCard(src, target))
        for (f in listCardFiles(c)) assertContentEquals(c.read(f), target.read(f), f)
        assertTrue(target.exists("only-here.txt"))
        assertFalse(target.exists(BACKUP_MANIFEST))
    }

    @Test fun emptyFoldersComeBackToo() {
        val c = card("c7"); c.mkdirs("contacts"); c.mkdirs("Map/empty")
        val out = fs("o7"); backupCard(c, out, "bk", "note")
        val src = LocalFs(File(out.root, "bk"))
        assertTrue(src.isDir("contacts") && src.isDir("Map/empty") && verifyBackup(src).ok)
        val target = fs("t7"); restoreCard(src, target)
        assertTrue(target.isDir("contacts") && target.isDir("Map/empty"))
        src.delete("contacts")
        assertEquals(listOf("contacts"), verifyBackup(src).missing)
    }

    @Test fun aDamagedBackupChangesNothing() {
        val c = card("c2"); val out = fs("o2"); backupCard(c, out, "bk", "note")
        val src = LocalFs(File(out.root, "bk"))
        val bad = src.read("Map/cline.dat")!!.also { it[100] = (it[100] + 1).toByte() }; src.write("Map/cline.dat", bad)
        assertEquals(listOf("Map/cline.dat"), verifyBackup(src).damaged)
        val target = fs("t2"); target.write("ttsystem", byteArrayOf(9))
        assertFailsWith<IllegalArgumentException> { restoreCard(src, target) }
        assertContentEquals(byteArrayOf(9), target.read("ttsystem")); assertFalse(target.exists("Map/cline.dat"))
    }

    @Test fun aMissingFileOrUnfinishedBackupIsRefused() {
        val c = card("c3"); val out = fs("o3"); backupCard(c, out, "bk", "note")
        val src = LocalFs(File(out.root, "bk"))
        src.delete("itn/a b.itn")
        assertEquals(listOf("itn/a b.itn"), verifyBackup(src).missing)
        val out2 = fs("o4"); backupCard(c, out2, "bk", "note"); val s2 = LocalFs(File(out2.root, "bk")); s2.delete(BACKUP_NOTE); s2.delete("ttgo.bif")
        assertFailsWith<IllegalArgumentException> { restoreCard(s2, fs("t3")) }
    }

    @Test fun anOldBackupWithoutManifestStillRestores() {
        val old = card("c5")
        val target = fs("t5")
        assertEquals(4, restoreCard(old, target))
        assertFalse(verifyBackup(old).checked)
    }

    @Test fun refusesWhenThereIsNoRoom() {
        val c = card("c6")
        val tiny = object : DeviceFs by fs("o6") { override fun freeSpace() = 10L }
        assertFailsWith<IllegalArgumentException> { backupCard(c, tiny, "bk", "note") }
    }

    @Test fun choosingAModelKeepsEverythingElse() {
        val c = fs("m1"); c.write("Config/setconfig.txt", "model:x40light\r\nbton\r\nttsoff\r\ndelgns\r\n".toByteArray())
        setKitchenModel(c, "x50")
        assertEquals("model:x50\r\nbton\r\nttsoff\r\ndelgns\r\n", c.read("Config/setconfig.txt")!!.decodeToString())
        assertEquals("x50", readKitchen(c, emptyMap()).model)
    }

    @Test fun modelRulesAreSafe() {
        val c = fs("m2"); c.write("Config/setconfig.txt", "model:x50\r\nmodelid:24\r\n".toByteArray())
        assertFalse(readKitchen(c, emptyMap()).canChooseModel)
        assertFailsWith<IllegalArgumentException> { setKitchenModel(c, "x40") }
        val d = fs("m3"); d.write("Config/setconfig.txt", "model:x50\r\n".toByteArray())
        assertFailsWith<IllegalArgumentException> { setKitchenModel(d, "nonsense") }
        setKitchenModel(d, "x40"); setLive(d, false)
        assertEquals("x50", readSetConfig(d)!!.model) // the Live switch still goes back to the model from before
        assertFalse(readKitchen(fs("m4"), emptyMap()).canChooseModel)
    }

    private fun specs(vararg hw: Pair<String, String>) = DeviceSpecs("ONE", "ONE (v4)", 32, 264, "Small, 320 × 240", null, true, false, mapOf(*hw))

    @Test fun leavingLiveWarnsAboutTheCarLink() {
        val c = compareRecipe(setOf("LiveServices", "GPRSEngine", "Itinerary"), recipeFor("ori")!!, specs())
        assertTrue(c.warnings.any { "car link" in it }); assertTrue(c.losses.any { it.name == "Live services" })
    }

    @Test fun featuresWithoutHardwareAreNotCountedAsGains() {
        val c = compareRecipe(setOf("Itinerary"), recipeFor("x40")!!, specs("canrecordaudio" to "0"))
        assertTrue(c.noHardware.any { it.name == "Microphone options" }); assertFalse(c.gains.any { it.name == "Microphone options" })
        assertTrue(c.gains.any { it.name == "Live services" })
    }

    @Test fun losingAFeatureTheHardwareLacksIsNotARealLoss() {
        val c = compareRecipe(setOf("Microphone", "MusicPlayer", "LiveServices"), recipeFor("x40light3")!!, specs("canrecordaudio" to "0"))
        assertTrue(c.noRealLoss.any { it.name == "Microphone options" }); assertFalse(c.losses.any { it.name == "Microphone options" })
        assertTrue(c.losses.any { it.name == "Music player" })
    }

    @Test fun aBigScreenProfileWarnsOnASmallScreen() {
        assertTrue(compareRecipe(emptySet(), recipeFor("x40light3")!!, specs()).warnings.any { "bigger screen" in it })
    }

    @Test fun readsSpecsFromAStartupLog() {
        val c = card("s1")
        c.write("gl/log/sys-1101-1735.txt", "== cpufreq\ncpuinfo_max_freq: 264000\n== meminfo\nMemTotal:        29568 kB\n== hardware\nbluetooth=1\ncanrecordaudio=0\ngpstype=128\ntfttype=3\n== mounts\nx=y\n".toByteArray())
        val s = readDeviceSpecs(c, mapOf("DeviceName" to "TomTom ONE"))
        assertEquals(32, s.ramMb); assertEquals(264, s.cpuMhz); assertEquals("Small, 320 × 240", s.screen); assertEquals(false, s.microphone); assertFalse("x" in s.hardware)
        assertNull(readDeviceSpecs(card("s2"), emptyMap()).ramMb)
    }

    @Test fun readsWhatTheCardRuns() {
        val c = card("k1"); c.write("Config/setconfig.txt", "model:x40\r\n".toByteArray()); c.write("text/config.txt", "Current Emulation: x40\nBT: 1\n".toByteArray())
        val k = readKitchen(c, readBif(c))
        assertEquals("9.541", k.navcore); assertEquals("3145439.2", k.build); assertEquals("x40", k.usedAtLastStart); assertEquals("GO x40", kitchenModel(k.model)!!.label)
    }
}

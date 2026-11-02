package app.mapstotom

import app.mapstotom.core.*
import java.nio.file.Files
import kotlin.test.*

class AddonsTest {
    /** setconfig.txt as the modified ONE v4 system leaves it (DOS line ends, blank and space-only lines). */
    private val sample = "model:x50\r\nbtoff\r\nttsoff\r\nfmoff\r\n \r\ndelgns\r\ndelmap\r\n\r\narm\r\n\r\n\r\n"

    private fun card(): LocalFs {
        val dir = Files.createTempDirectory("addons").toFile()
        val fs = LocalFs(dir)
        fs.write("ttgo.bif", "[TomTomGO]\r\n".toByteArray())
        fs.write("Config/setconfig.txt", sample.toByteArray())
        return fs
    }

    @Test fun readsWhatTheStartupScriptReads() {
        val sc = SetConfig("Config/setconfig.txt", sample)
        assertEquals("x50", sc.model)
        assertFalse(sc.live)
        assertTrue(sc.bluetoothOff)
        assertFalse(sc.hasModelId)
        assertTrue(SetConfig("x", "model:x40\r\n").live)
        assertFalse(SetConfig("x", "model:x40\r\nmodelid:15\r\n").live)  // modelid wins over model
        assertNull(SetConfig("x", "model:x40\r\nmodel:x50\r\n").model)    // two lines: the script gets both
    }

    @Test fun wordsAndModelKeepTheOtherLines() {
        val on = setWord(sample, "bton", "btoff")
        assertEquals(sample.replace("btoff", "bton"), on)                 // replaced in place, nothing else touched
        assertEquals(sample, setWord(on, "btoff", "bton"))
        assertEquals(sample.replace("model:x50", "model:x40"), setModel(sample, "x40"))
        // no line yet: the word goes after the last line with text, the model line first
        assertEquals("ttsoff\r\nbtoff\r\n\r\n", setWord("ttsoff\r\n\r\n", "btoff", "bton"))
        assertEquals("model:x40\r\nttsoff\r\n", setModel("ttsoff\r\n", "x40"))
        assertEquals("model:x40\nbton\n", setModel("model:x50\nbton\nmodel:ori\n", "x40"))  // Unix line ends kept
        assertEquals("bton\r\n", setWord("", "bton", "btoff"))
    }

    @Test fun liveOnAndOffPutsTheModelBack() {
        val fs = card()
        fs.write("Config/setconfig.txt", sample.replace("model:x50", "model:x30").toByteArray())
        setLive(fs, true)
        val on = readSetConfig(fs)!!
        assertEquals("x40", on.model); assertTrue(on.live); assertFalse(on.bluetoothOff); assertTrue(on.has("bton"))
        assertEquals("x30", fs.read("Config/$MODEL_BEFORE_LIVE")!!.toString(Charsets.US_ASCII).trim())
        setLive(fs, false)
        val off = readSetConfig(fs)!!
        assertEquals("x30", off.model)
        assertTrue(off.has("bton"))                                        // Bluetooth is its own switch
        assertFalse(fs.exists("Config/$MODEL_BEFORE_LIVE"))
        setBluetooth(fs, false)
        assertEquals(sample.replace("model:x50", "model:x30"), fs.read("Config/setconfig.txt")!!.toString(Charsets.ISO_8859_1))
        // Live turned on elsewhere (car-link setup by hand): off goes to x50
        fs.write("Config/setconfig.txt", "model:x40\r\nbton\r\n".toByteArray())
        setLive(fs, false)
        assertEquals("x50", readSetConfig(fs)!!.model)
    }

    @Test fun modelIdBlocksTheLiveSwitch() {
        val fs = card()
        fs.write("Config/setconfig.txt", "modelid:15\r\nbtoff\r\n".toByteArray())
        assertFailsWith<IllegalStateException> { setLive(fs, true) }
        assertEquals("modelid:15\r\nbtoff\r\n", fs.read("Config/setconfig.txt")!!.decodeToString())
    }

    @Test fun lastStartAndWaitingForRestart() {
        val fs = card()
        fs.write("text/config.txt", "Current Emulation: x40\nBT: 0\nTTS: off\n".toByteArray())
        val last = readLastStart(fs)!!
        assertEquals("x40", last.emulation); assertEquals(false, last.bluetooth)
        val sc = readSetConfig(fs)!!
        fs.file("text/config.txt").setLastModified(1_000_000_000_000)
        fs.file(sc.path).setLastModified(1_000_000_010_000)
        assertTrue(waitsForRestart(fs, sc, readLastStart(fs)))
        fs.file("text/config.txt").setLastModified(1_000_000_020_000)  // the TomTom started again
        assertFalse(waitsForRestart(fs, sc, readLastStart(fs)))
    }

    @Test fun fuelPricesOffAndOn() {
        val fs = card()
        val st = listOf(FuelStation("PT:1", 38.7, -9.1, "Galp Lisboa", mapOf(1 to 180)), FuelStation("PT:2", 38.6, -9.0, "BP Almada", mapOf(1 to 175)))
        installFuelPrices(fs, "Map", st, FuelType.DIESEL)
        assertEquals(1, readAddons(fs, "Map", Chip.UNKNOWN).fuelOn.size)
        assertEquals(1, setFuelPrices(fs, "Map", false))
        assertTrue(findFuelSets(fs, "Map").isEmpty())
        assertEquals(setOf("$FUEL_SET_NAME.ov2$FUEL_OFF_SUFFIX", "$FUEL_SET_NAME.fpf$FUEL_OFF_SUFFIX"), fs.list("Map").toSet())
        val a = readAddons(fs, "Map", Chip.UNKNOWN)
        assertEquals(FUEL_SET_NAME, a.fuelOff.single().name)
        assertEquals(2, a.fuelOff.single().stations)
        assertEquals(1, setFuelPrices(fs, "Map", true))
        val back = findFuelSets(fs, "Map").single()
        assertNotNull(back.fpf); assertEquals(2, back.stations)
        assertTrue(offFuelSets(fs, "Map").isEmpty())
    }

    @Test fun whatTheDeviceTabOffers() {
        val fs = card()
        var a = readAddons(fs, null, Chip.GL1)
        assertNotNull(a.setConfig); assertFalse(a.carLinkReady); assertNull(a.gpsLog); assertNull(a.swapBytes); assertTrue(a.any)
        fs.write(CAR_LINK_MARKER, "192.168.77.1:8080\r\n/services/directory.php\r\n".toByteArray())
        fs.write("swap", ByteArray(4096))
        fs.write("gl/debug-gltt", ByteArray(0))
        a = readAddons(fs, null, Chip.GL1)
        assertTrue(a.carLinkReady); assertEquals(true, a.gpsLog); assertEquals(4096L, a.swapBytes)
        // a TomTom with its own system: no setconfig, so nothing to switch here
        val plain = LocalFs(Files.createTempDirectory("plain").toFile()).also { it.write("ttgo.bif", ByteArray(0)) }
        assertFalse(readAddons(plain, null, Chip.GL2).any)
    }
}

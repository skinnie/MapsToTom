package app.mapstotom

import app.mapstotom.core.*
import java.io.File
import kotlin.test.*

class NavCorePatchTest {
    private val original = ByteArray(64) { it.toByte() }
    private val patch = run {
        val to = original.copyOf().also { it[8] = 0x55; it[9] = 0x66 }
        NavCorePatch("Test build", md5(original), md5(to), listOf(NavCorePatch.Spot(8, byteArrayOf(8, 9), byteArrayOf(0x55, 0x66))))
    }

    private fun card(name: String): LocalFs {
        val dir = File("build/tmp/m2t-navcore/$name").apply { deleteRecursively(); mkdirs() }
        return LocalFs(dir).also {
            it.write(NAVCORE_PATH, original)
            it.write("Config/setconfig.txt", "model:x50\r\nbtoff\r\n".toByteArray())
        }
    }

    @Test fun installsAndRemovesLeavingTheCardAsItWas() {
        val fs = card("roundtrip")
        fs.write("servicediscovery.txt", "old".toByteArray())
        installCarLinkPatch(fs, patch)
        assertEquals(patch.to, md5(fs.read(NAVCORE_PATH)!!))
        assertContentEquals(original, fs.read(NAVCORE_BACKUP))
        assertEquals(CAR_LINK_DISCOVERY["herculesdiscovery.txt"], fs.read("herculesdiscovery.txt")!!.decodeToString())
        assertTrue(readSetConfig(fs)!!.live)
        assertFalse(readSetConfig(fs)!!.bluetoothOff)
        removeCarLinkPatch(fs, patch)
        assertContentEquals(original, fs.read(NAVCORE_PATH))
        assertFalse(fs.exists(NAVCORE_BACKUP))
        assertEquals("old", fs.read("servicediscovery.txt")!!.decodeToString())
        assertFalse(fs.exists("herculesdiscovery.txt"))
        assertFalse(readSetConfig(fs)!!.live)
    }

    @Test fun refusesAnyOtherFileAndChangesNothing() {
        val fs = card("other")
        val other = original.copyOf().also { it[8] = 7 }
        fs.write(NAVCORE_PATH, other)
        assertFailsWith<IllegalArgumentException> { installCarLinkPatch(fs, patch) }
        assertContentEquals(other, fs.read(NAVCORE_PATH))
        assertFalse(fs.exists(NAVCORE_BACKUP))
        assertFalse(fs.exists("servicediscovery.txt"))
    }

    @Test fun anotherBuildWithTheSameCodeAtEverySiteIsAllowed() {
        val fs = card("compatible")
        val other = original.copyOf(80).also { it[40] = 99 }
        fs.write(NAVCORE_PATH, other)
        assertEquals(NavCoreState.COMPATIBLE, navCoreState(fs, patch))
        installCarLinkPatch(fs, patch)
        assertEquals(NavCoreState.PATCHED, navCoreState(fs, patch))
        assertEquals(0x55, fs.read(NAVCORE_PATH)!![8])
        assertEquals(99, fs.read(NAVCORE_PATH)!![40])
        removeCarLinkPatch(fs, patch)
        assertContentEquals(other, fs.read(NAVCORE_PATH))
    }

    @Test fun differentCodeIsNotOffered() {
        val fs = card("different")
        fs.write(NAVCORE_PATH, original.copyOf().also { it[9] = 1 })
        assertEquals(NavCoreState.OTHER, navCoreState(fs, patch))
    }

    @Test fun otherRealBuilds() {
        val p = NavCorePatch.parse(File("src/commonMain/composeResources/files/navcore/9541-carlink.json").readText())
        val line = System.getProperty("m2t.navcoreOther") ?: return
        val verdict = line.split(',').associate { val (f, want) = it.split('='); f to want.toBoolean() }
        for ((f, want) in verdict) assertEquals(want, p.fits(File(f).readBytes()), f)
    }

    @Test fun advicePointsEachVersionTheRightWay() {
        assertNull(carLinkAdvice("9.541", NavCoreState.ORIGINAL, true))
        assertNull(carLinkAdvice("9.510", NavCoreState.COMPATIBLE, true))
        assertNull(carLinkAdvice("9.541", NavCoreState.PATCHED, false))
        assertTrue("setconfig" in carLinkAdvice("9.541", NavCoreState.ORIGINAL, false)!!.detail)
        for (v in listOf("8.414", "9.001", "9.702")) {
            val a = carLinkAdvice(v, NavCoreState.OTHER, true, true)!!
            assertTrue(a.canPort && v in a.title && "port" in a.detail, v)
        }
        assertTrue(carLinkAdvice("9.001", NavCoreState.MISSING, false, null)!!.canPort) // program inside the system image
        for (v in listOf("7.900", "6.52")) {
            val a = carLinkAdvice(v, NavCoreState.OTHER, false, false)!!
            assertFalse(a.canPort); assertEquals("Car link isn't available on NavCore $v", a.title)
            assertTrue("TomTom HOME" in a.detail && "9.5xx" in a.detail && "forums" in a.detail && "Come back" in a.detail)
        }
        assertFalse(carLinkAdvice("8.010", NavCoreState.OTHER, false, false)!!.canPort) // an 8.x without the Live code
        assertEquals("Car link isn't available on this NavCore", carLinkAdvice(null, NavCoreState.MISSING, false)!!.title)
    }

    @Test fun findsTheLiveCode() {
        assertTrue(hasLiveCode("xxHerculesyy".toByteArray())); assertFalse(hasLiveCode("Hermes".toByteArray()))
    }

    @Test fun realPatchGivesTheExpectedFile() {
        val p = NavCorePatch.parse(File("src/commonMain/composeResources/files/navcore/9541-carlink.json").readText())
        val orig = System.getProperty("m2t.navcoreOrig")?.let(::File) ?: return
        assertEquals(p.to, md5(p.apply(orig.readBytes())))
    }
}

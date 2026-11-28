package app.mapstotom

import app.mapstotom.core.*
import app.mapstotom.link.StoredFuelSets
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.*

class StoredFuelSetsTest {
    private val offline = object : Net {
        override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int) = error("offline")
    }
    private val stations = listOf(
        FuelStation("a", 38.7, -9.1, "A", mapOf(1 to 180, 11 to 195)),
        FuelStation("b", 38.8, -9.2, "B", mapOf(1 to 175)),
    )

    private fun dir(): File = Files.createTempDirectory("fuelsets").toFile()

    /** The TomTom must get the installed prices at once, even with no network (downloads take minutes on a tablet). */
    @Test fun installedPricesAnswerAtOnce() {
        val d = dir()
        StoredFuelSets.record(d, 42, listOf(FuelSource.ESP), fuelSetOrder(stations))
        val ran = mutableListOf<Runnable>()
        val set = assertNotNull(StoredFuelSets(d, offline, waitS = 0, background = Executor { ran += it }).find("42"))
        assertEquals(listOf(180, 175), set.prices[1])
        assertEquals(listOf(195, 0), set.prices[11])
        assertNull(set.prices[8])          // LPG: nothing saved, nothing downloadable -> not available
        assertTrue(ran.size <= 1)          // fresh lists start no refresh; only the unsaved LPG tried one
    }

    /** Old prices are still answered; the refresh runs in the background and a failed country keeps its last prices. */
    @Test fun oldPricesRefreshInBackground() {
        val d = dir()
        StoredFuelSets.record(d, 7, listOf(FuelSource.ESP), fuelSetOrder(stations), Instant.now().minusSeconds(7200))
        val ran = mutableListOf<Runnable>()
        val log = mutableListOf<String>()
        val sets = StoredFuelSets(d, offline, log::add, background = Executor { ran += it })
        assertEquals(listOf(180, 175), sets.find("7")!!.prices[1])
        assertEquals(1, ran.size)
        sets.find("7")!!.prices[1]
        assertEquals(1, ran.size)          // one refresh per set and type at a time
        ran.single().run()
        assertTrue(log.any { "Spain failed" in it }, log.toString())
        assertEquals(listOf(180, 175), sets.find("7")!!.prices[1])
    }

    @Test fun warmRefreshesOnlyOldLists() {
        val d = dir()
        StoredFuelSets.record(d, 1, listOf(FuelSource.ESP), fuelSetOrder(stations))
        StoredFuelSets.record(d, 2, listOf(FuelSource.ESP), fuelSetOrder(stations), Instant.now().minusSeconds(7200))
        val ran = mutableListOf<Runnable>()
        StoredFuelSets(d, offline, background = Executor { ran += it }).warm()
        assertEquals(2, ran.size)          // set 2, types 1 and 11
    }
}

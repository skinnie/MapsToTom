package app.mapstotom

import app.mapstotom.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class AlmanacTest {
    private fun yuma(prn: Int, sqrtA: String = "5153.674805", health: String = "000") = """
******** Week 398 almanac for PRN-%02d ********
ID:                         %02d
Health:                     $health
Eccentricity:               0.2264022827E-002
Time of Applicability(s):  147456.0000
Orbital Inclination(rad):   0.9555645697
Rate of Right Ascen(r/s):  -0.8446066098E-008
SQRT(A)  (m 1/2):           $sqrtA
Right Ascen at Week(rad):  -0.1627014326E+001
Argument of Perigee(rad):   0.142555726
Mean Anom(rad):            -0.2391811607E+001
Af0(s):                     0.1211166382E-003
Af1(s/s):                  -0.7275957614E-011
week:                        398
""".format(prn, prn)

    @Test fun readsYuma() {
        val s = parseYuma((1..24).joinToString("") { yuma(it, health = if (it == 3) "063" else "000") })
        assertEquals(24, s.size)
        assertFalse(s[2].healthy)
        assertEquals(398, s[0].week)
        assertEquals(5153.674805, s[0].values[4])
        assertEquals(-1.627014326, s[0].values[5])
    }

    @Test fun refusesWhatIsNotAnOrbit() {
        assertFailsWith<IllegalArgumentException> { parseYuma((1..24).joinToString("") { yuma(it, sqrtA = if (it == 5) "9.0" else "5153.67") }) }
        assertFailsWith<IllegalArgumentException> { parseYuma(yuma(1)) }
    }

    @Test fun refusesAnotherGpsProgram() {
        assertFailsWith<IllegalArgumentException> { patchGlttAlmanac(ByteArray(897_732), parseYuma((1..24).joinToString("") { yuma(it) })) }
    }

    @Test fun realProgramChangesOnlyInsideTheTable() {
        val orig = System.getProperty("m2t.glttOrig")?.let(::File)?.readBytes() ?: return
        val sats = parseYuma(File(System.getProperty("m2t.yuma")).readText())
        val out = patchGlttAlmanac(orig, sats)
        val changed = orig.indices.filter { orig[it] != out[it] }
        assertTrue(changed.first() >= 0xd38c0 && changed.last() < 0xd38c0 + 30 * 88)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val byPrn = sats.associateBy { it.prn }
        for (i in 0 until 30) {
            val at = 0xd38c0 + i * 88
            val s = byPrn.getValue(b.getInt(at))
            assertEquals(s.week, b.getInt(at + 84))
            s.values.forEachIndexed { k, v -> assertEquals(v, b.getDouble(at + 4 + k * 8)) }
        }
        System.getProperty("m2t.almOut")?.let { File(it).writeBytes(out) }
    }
}

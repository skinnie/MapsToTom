package app.mapstotom

import app.mapstotom.core.*
import app.mapstotom.link.CarDeviceReport
import kotlin.test.*

class KnownTomTomsTest {
    @Test fun helperReportBecomesADevice() {
        val r = assertNotNull(CarDeviceReport.fromQuery("caps=ephem&gpstype=128&gps=185.5001.041&model=ONE+(v4)"))
        val t = assertNotNull(knownFromReport(r.model, r.gpsType, r.gpsVersion, 5))
        assertEquals("ONE (v4)", t.name); assertEquals(Chip.GL1, t.chip)
        assertTrue(t.engine in LTO_WEEK_BUG_ENGINES, "the week fix must recognise the reported GPS program")
    }
    @Test fun aDroppedLinkForgetsTheLiveDeviceButNotTheHistory() {
        val s = app.mapstotom.link.CarLinkState
        s.set(app.mapstotom.link.CarLinkState.Phase.ONLINE); s.report(CarDeviceReport("ONE (v4)", 128, "185.5001.041"))
        assertNotNull(s.device.value)
        s.set(app.mapstotom.link.CarLinkState.Phase.WAITING)
        assertNull(s.device.value)
        s.set(app.mapstotom.link.CarLinkState.Phase.OFF)
    }
    @Test fun transferShowsSizeAndTimeLeft() {
        val t = app.mapstotom.link.CarTransfer("ephem/lto.dat", 30 * 1024, 60 * 1024, 0)
        assertEquals("Sending QuickGPSFix  ·  30 of 60 KB  ·  about 1 min left", t.text(60_000))
        assertEquals(60, t.secondsLeft(60_000))
        assertNull(t.copy(sent = 1000).secondsLeft(60_000), "too little sent to estimate")
        assertEquals("Saving route on the TomTom…", t.copy(path = "itn/a.itn", sent = t.total).text(70_000))
    }
    @Test fun oldHelperReportsNothing() = assertNull(CarDeviceReport.fromQuery("caps=ephem"))
    @Test fun unsafeTextIsDropped() {
        val r = assertNotNull(CarDeviceReport.fromQuery("gpstype=130&model=a|b%0a&gps=x/y"))
        assertNull(r.model); assertNull(r.gpsVersion)
        assertNull(assertNotNull(knownFromReport(r.model, r.gpsType, "185", 1)).engine, "only GL1 uses an engine version")
    }
    @Test fun historyKeepsNewestFirstWithoutDuplicates() {
        val a = KnownTomTom("ONE (v4)", Chip.GL1, "Global Locate GLL 185.5001.041", 1)
        val b = KnownTomTom("GO 950", Chip.GL2, null, 2)
        val list = rememberTomTom(rememberTomTom(rememberTomTom(emptyList(), a), b), a.copy(seen = 3))
        assertEquals(listOf("ONE (v4)", "GO 950"), list.map { it.name }); assertEquals(3, list[0].seen)
        assertEquals(list, parseKnownTomToms(formatKnownTomToms(list)))
    }
}

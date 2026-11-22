package app.mapstotom

import app.mapstotom.link.CarOutbox
import app.mapstotom.link.live.IncidentSource
import app.mapstotom.link.live.LiveServer
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class CarOutboxTest {
    private fun dir(n: String) = File("build/tmp/m2t-outbox/$n").absoluteFile.apply { deleteRecursively(); mkdirs() }

    @Test fun keepsAFileUntilItIsConfirmed() {
        val box = CarOutbox(dir("queue"))
        val a = box.put("Map/A.ov2", byteArrayOf(1)); box.put("itn/r.itn", byteArrayOf(2))
        assertEquals(a, box.next()!!.id); assertEquals(a, box.next()!!.id)
        box.done(a)
        assertEquals("itn/r.itn", box.next()!!.path)
    }

    @Test fun aNewerFileReplacesOneStillWaiting() {
        val box = CarOutbox(dir("newer"))
        box.put("Map/A.ov2", byteArrayOf(1)); box.put("Map/A.ov2", byteArrayOf(2))
        assertEquals(1, box.size()); assertContentEquals(byteArrayOf(2), box.next()!!.data)
    }

    @Test fun refusesPathsOutsideWhatItMaySend() {
        for (p in listOf("/etc/rc.ov2", "../x.ov2", "a/../../b.itn", "ttsystem", "PNDNavigator", "bin/gltt", "a\\b.ov2", "c:d.ov2", "x.sh"))
            assertFalse(CarOutbox.validPath(p), p)
        assertTrue(CarOutbox.validPath("Iberia_1175_13260/Cafes e pastelarias.ov2"))
    }

    /** The real helper, as an ARM program under qemu in a sandbox, against the real server. */
    @Test fun realHelperSavesFilesOverTheLink() {
        val helper = System.getProperty("m2t.helperBin") ?: return
        val root = dir("helper"); val card = File(root, "card/Map").apply { mkdirs() }
        File(card, "A.ov2").writeBytes(byteArrayOf(9))
        val box = CarOutbox(File(root, "box"))
        val data = ByteArray(300_000) { (it * 31 xor (it shr 7)).toByte() }
        box.put("Map/A.ov2", data); box.put("Map/Café e bar.bmp", byteArrayOf(1, 2, 3))
        val live = LiveServer(traffic = IncidentSource { emptyList() }, weather = { _, _ -> emptyMap<String, Any>() }, outbox = box)
        val server = ServerSocket(0)
        thread(isDaemon = true) { runCatching { while (true) { val s = server.accept(); thread(isDaemon = true) { s.use { live.serve(it.getInputStream(), it.getOutputStream()) } } } } }
        val p = ProcessBuilder("bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp", "--bind", root.path, root.path,
            "--die-with-parent", "qemu-arm", helper, "127.0.0.1", "${server.localPort}", File(root, "card").path, "once").redirectErrorStream(true).start()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "helper didn't finish"); server.close()
        assertEquals(0, p.exitValue(), p.inputStream.readBytes().decodeToString())
        assertContentEquals(data, File(card, "A.ov2").readBytes())
        assertContentEquals(byteArrayOf(1, 2, 3), File(card, "Café e bar.bmp").readBytes())
        assertEquals(0, box.size()); assertFalse(File(card, "A.ov2.m2tnew").exists())
    }

    @Test fun realHelperKeepsTheOldFileIfTheTransferIsDamaged() {
        val helper = System.getProperty("m2t.helperBin") ?: return
        val root = dir("damaged"); val card = File(root, "card/Map").apply { mkdirs() }
        File(card, "A.ov2").writeBytes(byteArrayOf(9))
        val server = ServerSocket(0)
        thread(isDaemon = true) { runCatching { server.accept().use { s ->
            val i = s.getInputStream(); val b = ByteArray(200); i.read(b)
            s.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\nM2T1 1 00000000 3 Map/A.ov2\n".toByteArray() + byteArrayOf(1, 2, 3))
        } } }
        val p = ProcessBuilder("bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp", "--bind", root.path, root.path,
            "--die-with-parent", "qemu-arm", helper, "127.0.0.1", "${server.localPort}", File(root, "card").path, "once").redirectErrorStream(true).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS)); server.close()
        assertContentEquals(byteArrayOf(9), File(card, "A.ov2").readBytes())
        assertFalse(File(card, "A.ov2.m2tnew").exists())
    }

    @Test fun gpsFilesArriveDataFirstAndNeverHoldUpAnOlderHelper() {
        val box = CarOutbox(dir("gps"))
        box.putInOrder(listOf("ephem/lto.dat" to byteArrayOf(1), "ephem/ee_meta.txt" to byteArrayOf(2)))
        box.put("itn/r.itn", byteArrayOf(3))
        assertEquals("itn/r.itn", box.next()!!.path, "an older helper skips GPS files")
        assertEquals("ephem/lto.dat", box.next(gps = true)!!.path)
        box.putInOrder(listOf("ephem/lto.dat" to byteArrayOf(4), "ephem/ee_meta.txt" to byteArrayOf(5)))
        assertEquals(3, box.size())
        assertEquals("itn/r.itn", box.next(gps = true)!!.path)
        box.done(box.next(gps = true)!!.id)
        val data = box.next(gps = true)!!; assertEquals("ephem/lto.dat", data.path); assertContentEquals(byteArrayOf(4), data.data)
        box.done(data.id)
        assertEquals("ephem/ee_meta.txt", box.next(gps = true)!!.path)
    }

    @Test fun onlyTheExactGpsFilesAreAllowed() {
        for (p in CarOutbox.GPS_PATHS) assertTrue(CarOutbox.validPath(p), p)
        for (p in listOf("ephem/other.dat", "lto.dat", "EPHEM/lto.dat", "@map/ephem/lto.dat", "ephem/../lto.dat", "ephem/lto.dat.sh"))
            assertFalse(CarOutbox.validPath(p), p)
        assertFailsWith<IllegalArgumentException> { CarOutbox(dir("gps-mode")).put("ephem/lto.dat", byteArrayOf(1), CarOutbox.Mode.ADD) }
    }

    @Test fun realHelperSavesQuickFixAndRefusesOtherEphemFiles() {
        val helper = System.getProperty("m2t.helperBin") ?: return
        val root = dir("helper-gps"); val card = File(root, "card").apply { mkdirs() }   // no ephem folder yet
        val box = CarOutbox(File(root, "box"))
        val lto = ByteArray(60_000) { (it * 7).toByte() }
        box.putInOrder(listOf("ephem/lto.dat" to lto, "ephem/ee_meta.txt" to "Expiry=1".toByteArray()))
        val live = LiveServer(traffic = IncidentSource { emptyList() }, weather = { _, _ -> emptyMap<String, Any>() }, outbox = box)
        val server = ServerSocket(0)
        thread(isDaemon = true) { runCatching { while (true) { val s = server.accept(); thread(isDaemon = true) { s.use { live.serve(it.getInputStream(), it.getOutputStream()) } } } } }
        fun run(port: Int) = ProcessBuilder("bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp", "--bind", root.path, root.path,
            "--die-with-parent", "qemu-arm", helper, "127.0.0.1", "$port", card.path, "once").redirectErrorStream(true).start()
        val p = run(server.localPort)
        assertTrue(p.waitFor(60, TimeUnit.SECONDS)); server.close(); assertEquals(0, p.exitValue())
        assertContentEquals(lto, File(card, "ephem/lto.dat").readBytes())
        assertEquals("Expiry=1", File(card, "ephem/ee_meta.txt").readText())
        assertEquals(0, box.size())
        for (bad in listOf("M2T1 1 00000000 0 ephem/other.dat", "M2T2 1 00000000 0 a ephem/lto.dat", "M2T1 1 00000000 0 @map/ephem/lto.dat")) {
            val s2 = ServerSocket(0)
            thread(isDaemon = true) { runCatching { s2.accept().use { s -> s.getInputStream().read(ByteArray(200)); s.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\n$bad\n".toByteArray()) } } }
            val q = run(s2.localPort); assertTrue(q.waitFor(30, TimeUnit.SECONDS)); s2.close()
            assertEquals(1, q.exitValue(), bad)
        }
        assertFalse(File(card, "ephem/other.dat").exists())
        assertContentEquals(lto, File(card, "ephem/lto.dat").readBytes())
    }

    @Test fun additionsAllStayQueuedInOrder() {
        val box = CarOutbox(dir("modes"))
        box.put("@map/A.ov2", byteArrayOf(1), CarOutbox.Mode.ADD); box.put("@map/A.ov2", byteArrayOf(2), CarOutbox.Mode.ADD)
        assertEquals(2, box.size())
        val first = box.next()!!; assertEquals(CarOutbox.Mode.ADD, first.mode); assertContentEquals(byteArrayOf(1), first.data)
        assertTrue(CarOutbox.frame(first).decodeToString().startsWith("M2T2 ${first.id} "))
    }

    @Test fun realHelperAddsToTheMapFolderAndKeepsWhatIsThere() {
        val helper = System.getProperty("m2t.helperBin") ?: return
        val root = dir("helper-map"); val card = File(root, "card").apply { mkdirs() }; val map = File(card, "Iberia_1175").apply { mkdirs() }
        val name = "/mnt/sdcard/Iberia_1175/".toByteArray() + 0
        File(card, "CurrentMap.dat").writeBytes(byteArrayOf(name.size.toByte(), 0, 0, 0) + name)
        File(map, "Mine.ov2").writeBytes(byteArrayOf(1, 2, 3)); File(map, "Mine.bmp").writeBytes(byteArrayOf(7))
        val box = CarOutbox(File(root, "box"))
        box.put("@map/Mine.ov2", byteArrayOf(4, 5), CarOutbox.Mode.ADD)
        box.put("@map/Mine.bmp", byteArrayOf(9), CarOutbox.Mode.IF_MISSING)
        box.put("@map/New.ov2", byteArrayOf(6), CarOutbox.Mode.ADD)
        box.put("@map/New.bmp", byteArrayOf(8), CarOutbox.Mode.IF_MISSING)
        val live = LiveServer(traffic = IncidentSource { emptyList() }, weather = { _, _ -> emptyMap<String, Any>() }, outbox = box)
        val server = ServerSocket(0)
        thread(isDaemon = true) { runCatching { while (true) { val s = server.accept(); thread(isDaemon = true) { s.use { live.serve(it.getInputStream(), it.getOutputStream()) } } } } }
        val p = ProcessBuilder("bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp", "--bind", root.path, root.path,
            "--die-with-parent", "qemu-arm", helper, "127.0.0.1", "${server.localPort}", card.path, "once").redirectErrorStream(true).start()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS)); server.close()
        assertEquals(0, p.exitValue())
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), File(map, "Mine.ov2").readBytes())
        assertContentEquals(byteArrayOf(7), File(map, "Mine.bmp").readBytes())
        assertContentEquals(byteArrayOf(6), File(map, "New.ov2").readBytes())
        assertContentEquals(byteArrayOf(8), File(map, "New.bmp").readBytes())
        assertEquals(0, box.size())
    }
}

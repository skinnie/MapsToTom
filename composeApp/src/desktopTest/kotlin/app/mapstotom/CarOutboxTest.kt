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
}

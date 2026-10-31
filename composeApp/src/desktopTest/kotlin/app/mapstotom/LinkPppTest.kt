package app.mapstotom

import app.mapstotom.link.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

private class Seg(val seq: Long, val ack: Long, val flags: Int, val data: ByteArray)

/** Ports of server/test_fakedun.py and server/test_userppp.py, plus an AT-dial-to-HTTP end-to-end run. */
class LinkPppTest {
    // ---- one direction of the Bluetooth stream ----
    private class Pipe {
        val input = QueueInputStream()
        val output = object : OutputStream() {
            override fun write(b: Int) = input.push(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) = input.push(b.copyOfRange(off, off + len))
            override fun close() = input.end()
        }
    }

    private val body10k = ByteArray(256 * 40) { (it % 256).toByte() }
    private val log = LinkedBlockingQueue<String>()

    private inner class Host(val upstreamPort: Int = 0) : LinkHost {
        override fun local(port: Int): LocalService? = if (port != LinkAddr.LIVE_PORT) null else LocalService { i, o ->
            readHeaders(i)
            o.write("HTTP/1.0 200 OK\r\nContent-Length: ${body10k.size}\r\n\r\n".toByteArray() + body10k)
        }
        override fun connect(ip: ByteArray, port: Int): Socket? = runCatching { Socket("127.0.0.1", upstreamPort) }.getOrNull()
        override fun resolve(name: String) = if (name == "localhost") listOf(byteArrayOf(127, 0, 0, 1)) else emptyList()
        override fun log(line: String) { log += line }
    }

    private fun readHeaders(i: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) { val c = i.read(); if (c < 0) break; sb.append(c.toChar()) }
        return sb.toString()
    }

    // ---- AT engine (test_fakedun.py) ----
    @Test fun atReplies() {
        assertTrue("SonyEricsson" in AtModem.reply("AT+CGMI").first)
        assertTrue("AAB-1022041-BV" in AtModem.reply("at+cgmm").first)
        assertTrue("\"26801\"" in AtModem.reply("AT+COPS?").first)
        assertTrue("Vodafone P" in AtModem.reply("AT+COPS=?").first)       // longest prefix wins over AT+COPS?
        assertEquals("\r\nOK\r\n" to false, AtModem.reply("ATD0123456;"))
        assertEquals("\r\nCONNECT\r\n" to true, AtModem.reply("ATD*99***1#"))
        assertEquals("\r\nOK\r\n" to false, AtModem.reply("AT+XYZ"))
        assertEquals("\r\nOK\r\n" to false, AtModem.reply("AT+CLCC"))
    }

    @Test fun atLoopEchoAndDial() {
        val toPhone = Pipe(); val toTomTom = Pipe()
        var pppStarted = false
        val t = thread { AtModem.run(toPhone.input, toTomTom.output, Host()) { _, o, _ -> pppStarted = true; o.write("PPP".toByteArray()) } }
        fun ask(s: String, want: String): String {
            toPhone.output.write(s.toByteArray())
            val sb = StringBuilder(); val end = System.currentTimeMillis() + 3000
            while (want !in sb && System.currentTimeMillis() < end) {
                val c = toTomTom.input.read(); if (c < 0) break; sb.append(c.toChar())
            }
            return sb.toString()
        }
        assertTrue(ask("ATZ\r", "OK").startsWith("ATZ\r"), "echo on")
        assertTrue("SonyEricsson" in ask("AT+CGMI\r", "OK"))
        assertFalse(ask("ATE0\r", "OK").startsWith("ATE0"), "ATE0 reply is not echoed")
        val r = ask("AT+CGMM\r", "OK")
        assertTrue("AAB-1022041-BV" in r && "AT+CGMM" !in r, r)                 // not echoed after ATE0
        assertTrue("CONNECT" in ask("ATD*99***1#\r", "CONNECT"))
        assertEquals("PPP", ask("", "PPP").trim())
        t.join(2000)
        assertTrue(pppStarted)
    }

    // ---- framing ----
    @Test fun hdlcRoundTripAndFcs() {
        // RFC 1662 check value over the frame including its FCS
        val f = Hdlc.encode(Hdlc.LCP, byteArrayOf(1, 1, 0, 4))
        assertEquals(0x7e, f.first().toInt() and 0xff); assertEquals(0x7e, f.last().toInt() and 0xff)
        // control characters are escaped: address ff, control 03 -> 7d 23
        assertEquals(listOf(0xff, 0x7d, 0x23), f.slice(1..3).map { it.toInt() and 0xff })
        val got = Hdlc.Deframer().feed(f)
        assertEquals(1, got.size); assertEquals(Hdlc.LCP, got[0].first)
        assertContentEquals(byteArrayOf(1, 1, 0, 4), got[0].second)
        // compressed address/control and 1-byte protocol (IPv4 = 0x21)
        val body = byteArrayOf(0x21, 0x45, 0x00)
        val fcs = Hdlc.fcs16(body) xor 0xffff
        val raw = byteArrayOf(0x7e) + body + byteArrayOf(fcs.toByte(), (fcs ushr 8).toByte()) + byteArrayOf(0x7e)
        val g2 = Hdlc.Deframer().feed(raw)
        assertEquals(Hdlc.IPV4, g2.single().first); assertContentEquals(byteArrayOf(0x45, 0x00), g2.single().second)
        // a corrupted byte is dropped
        val bad = f.copyOf(); bad[6] = (bad[6] + 1).toByte()
        assertTrue(Hdlc.Deframer().feed(bad).isEmpty())
    }

    @Test fun ipChecksum() {
        // RFC 1071 example words 0001 f203 f4f5 f6f7 -> sum ddf2, checksum 220d
        assertEquals(0x220d, inetCsum(byteArrayOf(0x00, 0x01, 0xf2.toByte(), 0x03, 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte())))
        val p = ipPacket(LinkAddr.TOMTOM, LinkAddr.PHONE, 17, ByteArray(8))
        assertEquals(0, inetCsum(p, 0, 20))
    }

    // ---- a minimal TomTom-side PPP client (as in test_userppp.py) ----
    private inner class Client(val toPhone: Pipe, val toTomTom: Pipe) {
        val de = Hdlc.Deframer()
        val pending = ArrayDeque<Pair<Int, ByteArray>>()
        val buf = ByteArray(65536)
        val reader = LinkedBlockingQueue<ByteArray>()
        init { thread(isDaemon = true) { while (true) { val n = toTomTom.input.read(buf); if (n < 0) break; reader += buf.copyOf(n) } } }

        fun recv(timeoutMs: Long = 3000): Pair<Int, ByteArray>? {
            val end = System.currentTimeMillis() + timeoutMs
            while (pending.isEmpty()) {
                val left = end - System.currentTimeMillis(); if (left <= 0) return null
                val d = reader.poll(left, TimeUnit.MILLISECONDS) ?: return null
                pending.addAll(de.feed(d))
            }
            return pending.removeFirst()
        }
        fun send(proto: Int, payload: ByteArray) = toPhone.output.write(Hdlc.encode(proto, payload))
        fun ctl(proto: Int, code: Int, id: Int, data: ByteArray) =
            send(proto, byteArrayOf(code.toByte(), id.toByte(), 0, (4 + data.size).toByte()) + data)

        /** LCP like Linux pppd (ACCM, magic, PFC, ACFC), IPCP asking 0.0.0.0 and taking the NAK'd values. */
        fun negotiate(): Pair<ByteArray, ByteArray> {
            ctl(Hdlc.LCP, 1, 1, byteArrayOf(2, 6, 0, 0, 0, 0, 5, 6, 'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 'D'.code.toByte(), 7, 2, 8, 2))
            var myIp = ByteArray(4); var dns = ByteArray(4)
            ctl(Hdlc.IPCP, 1, 2, byteArrayOf(3, 6) + myIp + byteArrayOf(129.toByte(), 6) + dns)
            var lcpAcked = false; var up = false
            while (!up) {
                val (proto, p) = assertNotNull(recv(), "no PPP reply")
                val code = p[0].toInt(); val id = p[1].toInt()
                val data = p.copyOfRange(4, p.size)
                when {
                    proto == Hdlc.LCP && code == 1 -> ctl(Hdlc.LCP, 2, id, data)
                    proto == Hdlc.LCP && code == 2 -> lcpAcked = true
                    proto == Hdlc.IPCP && code == 3 -> {
                        val o = PppLink.options(data).toMap()
                        myIp = o.getValue(3); dns = o[129] ?: dns
                        ctl(Hdlc.IPCP, 1, 3, byteArrayOf(3, 6) + myIp + byteArrayOf(129.toByte(), 6) + dns)
                    }
                    proto == Hdlc.IPCP && code == 1 -> ctl(Hdlc.IPCP, 2, id, data)
                    proto == Hdlc.IPCP && code == 2 -> up = true
                }
            }
            assertTrue(lcpAcked)
            return myIp to dns
        }

        fun dnsQuery(name: String): ByteArray? {
            val q = java.io.ByteArrayOutputStream()
            q.write(byteArrayOf(0, 7, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (l in name.split('.')) { q.write(l.length); q.write(l.toByteArray()) }
            q.write(byteArrayOf(0, 0, 1, 0, 1))
            val qb = q.toByteArray()
            val udp = ByteArray(8 + qb.size)
            udp[0] = (5353 ushr 8).toByte(); udp[1] = 5353.toByte(); udp[3] = 53
            udp[4] = (udp.size ushr 8).toByte(); udp[5] = udp.size.toByte(); qb.copyInto(udp, 8)
            send(Hdlc.IPV4, ipPacket(LinkAddr.TOMTOM, LinkAddr.PHONE, 17, udp))
            while (true) {
                val (proto, p) = recv() ?: return null
                if (proto == Hdlc.IPV4 && p[9].toInt() == 17) {
                    val ans = p.copyOfRange(28, p.size)
                    assertEquals(0, ans[0].toInt()); assertEquals(7, ans[1].toInt())
                    return ans
                }
            }
        }

        fun tcp(dst: ByteArray, dport: Int, flags: Int, seq: Long, ack: Long, data: ByteArray = ByteArray(0), sport: Int = 40000) {
            val seg = ByteArray(20 + data.size)
            fun p16(i: Int, v: Int) { seg[i] = (v ushr 8).toByte(); seg[i + 1] = v.toByte() }
            fun p32(i: Int, v: Long) { p16(i, (v ushr 16).toInt()); p16(i + 2, v.toInt()) }
            p16(0, sport); p16(2, dport); p32(4, seq); p32(8, ack); seg[12] = 0x50; seg[13] = flags.toByte(); p16(14, 2048)
            data.copyInto(seg, 20)
            val c = l4Csum(LinkAddr.TOMTOM, dst, 6, seg); p16(16, c)
            send(Hdlc.IPV4, ipPacket(LinkAddr.TOMTOM, dst, 6, seg))
        }

        fun tcpIn(src: ByteArray): Seg {
            while (true) {
                val (proto, p) = assertNotNull(recv(5000), "tcp timeout")
                if (proto == Hdlc.IPV4 && p[9].toInt() == 6) {
                    val seg = p.copyOfRange(20, p.size)
                    assertEquals(0, l4Csum(src, LinkAddr.TOMTOM, 6, seg), "bad tcp checksum")
                    fun g16(i: Int) = ((seg[i].toInt() and 0xff) shl 8) or (seg[i + 1].toInt() and 0xff)
                    fun g32(i: Int) = (g16(i).toLong() shl 16) or g16(i + 2).toLong()
                    val off = ((seg[12].toInt() and 0xff) ushr 4) * 4
                    return Seg(g32(4), g32(8), seg[13].toInt() and 0xff, seg.copyOfRange(off, seg.size))
                }
            }
        }

        /** SYN, one request, read everything (dropping one segment once), FIN. Returns the bytes received. */
        fun httpGet(dst: ByteArray, port: Int, dropOne: Boolean, path: String = "/x"): ByteArray {
            val iss = 1000L
            tcp(dst, port, 0x02, iss, 0)
            val synAck = tcpIn(dst)
            assertTrue(synAck.flags and 0x12 == 0x12 && synAck.ack == iss + 1, "SYN-ACK")
            var rcv = (synAck.seq + 1) and 0xffffffffL
            val req = "GET $path HTTP/1.0\r\nHost: s\r\n\r\n".toByteArray()
            tcp(dst, port, 0x18, iss + 1, rcv, req)
            val got = java.io.ByteArrayOutputStream()
            var fin = false; var dropped = !dropOne
            while (!fin) {
                val s = tcpIn(dst)
                if (s.data.isNotEmpty() && !dropped && got.size() > 3000) { dropped = true; continue }   // lose one: must be resent
                if (s.seq == rcv) {
                    got.write(s.data)
                    rcv = (rcv + s.data.size + (if (s.flags and 0x01 != 0) 1 else 0)) and 0xffffffffL
                    fin = s.flags and 0x01 != 0
                }
                tcp(dst, port, 0x10, iss + 1 + req.size, rcv)
            }
            tcp(dst, port, 0x11, iss + 1 + req.size, rcv)
            return got.toByteArray()
        }
    }

    private fun bodyOf(resp: ByteArray): ByteArray {
        val s = String(resp, Charsets.ISO_8859_1); val i = s.indexOf("\r\n\r\n")
        assertTrue(i > 0, "no HTTP header")
        return resp.copyOfRange(i + 4, resp.size)
    }

    /** test_userppp.py: LCP/IPCP up, DNS answer, 10 KB HTTP relayed to an upstream socket with one lost segment. */
    @Test fun pppRelayToInternet() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { c ->
                readHeaders(c.getInputStream())
                c.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Length: ${body10k.size}\r\n\r\n".toByteArray() + body10k)
            }
        }
        val toPhone = Pipe(); val toTomTom = Pipe()
        val link = PppLink(toPhone.input, toTomTom.output, Host(server.localPort))
        thread(isDaemon = true) { link.run() }
        val c = Client(toPhone, toTomTom)
        val (ip, dns) = c.negotiate()
        assertContentEquals(LinkAddr.TOMTOM, ip); assertContentEquals(LinkAddr.PHONE, dns)
        val ans = assertNotNull(c.dnsQuery("localhost"))
        assertContentEquals(byteArrayOf(127, 0, 0, 1), ans.copyOfRange(ans.size - 4, ans.size))
        val nx = assertNotNull(c.dnsQuery("nowhere.invalid"))
        assertEquals(0, ans[3].toInt() and 0x0f); assertEquals(3, nx[3].toInt() and 0x0f)
        val internet = byteArrayOf(10, 0, 0, 5)
        assertContentEquals(body10k, bodyOf(c.httpGet(internet, 80, dropOne = true)))
        Thread.sleep(300)
        assertTrue(link.tcp.isEmpty(), "connection not closed")
        server.close()
    }

    /** End to end: AT dial -> CONNECT -> PPP -> HTTP to the in-app service on 192.168.77.1:8080; other ports refused. */
    @Test fun dialThenLocalService() {
        val toPhone = Pipe(); val toTomTom = Pipe()
        thread(isDaemon = true) { runModem(toPhone.input, toTomTom.output, Host()) }
        // dial script on the raw stream, as the TomTom's apnd does
        fun chat(cmd: String, want: String) {
            toPhone.output.write(cmd.toByteArray())
            val sb = StringBuilder(); val end = System.currentTimeMillis() + 3000
            while (want !in sb && System.currentTimeMillis() < end) { val ch = toTomTom.input.read(); if (ch < 0) break; sb.append(ch.toChar()) }
            assertTrue(want in sb, "$cmd -> $sb")
        }
        chat("ATZ\r", "OK"); chat("ATE0\r", "OK"); chat("AT+CGDCONT=1,\"IP\",\"internet\"\r", "OK"); chat("ATD*99***1#\r", "CONNECT\r\n")
        val c = Client(toPhone, toTomTom)
        c.negotiate()
        val resp = c.httpGet(LinkAddr.PHONE, LinkAddr.LIVE_PORT, dropOne = false)
        assertContentEquals(body10k, bodyOf(resp))
        // port 9000 (push channel) has no service: RST
        c.tcp(LinkAddr.PHONE, 9000, 0x02, 5000, 0, sport = 40001)
        var r = c.tcpIn(LinkAddr.PHONE)
        repeat(5) { if (r.flags and 0x04 == 0) r = c.tcpIn(LinkAddr.PHONE) }      // skip late ACKs of the first connection
        assertTrue(r.flags and 0x04 != 0, "RST expected, got flags ${r.flags}")
        assertEquals(5001L, r.ack)
        // LCP echo and terminate
        c.ctl(Hdlc.LCP, 9, 42, byteArrayOf(1, 2, 3, 4))
        var echo: Pair<Int, ByteArray>? = null
        while (echo == null) { val f = assertNotNull(c.recv()); if (f.first == Hdlc.LCP && f.second[0].toInt() == 10) echo = f }
        assertEquals(42, echo.second[1].toInt())
        c.ctl(Hdlc.LCP, 5, 43, ByteArray(0))
        var ack = false
        while (!ack) { val f = assertNotNull(c.recv()); ack = f.first == Hdlc.LCP && f.second[0].toInt() == 6 }
        assertTrue(log.any { it.startsWith("ppp: up") })
    }

    /** Whole chain: AT dial -> PPP -> TCP to 192.168.77.1:8080 -> the in-app LiveServer (golden /logon reply). */
    @Test fun dialThenLiveServer() {
        val offline = object : app.mapstotom.core.Net {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int) = error("offline")
        }
        val dir = java.nio.file.Files.createTempDirectory("carlink").toFile()
        val live = app.mapstotom.link.live.LiveServer(offline, { null }, app.mapstotom.link.StoredFuelSets(dir, offline),
            app.mapstotom.link.FileLiveStore(dir))
        val host = object : LinkHost {
            override fun local(port: Int) = if (port == LinkAddr.LIVE_PORT) live else null
            override fun connect(ip: ByteArray, port: Int): Socket? = null
            override fun resolve(name: String) = emptyList<ByteArray>()
            override fun log(line: String) { log += line }
        }
        val toPhone = Pipe(); val toTomTom = Pipe()
        thread(isDaemon = true) { app.mapstotom.link.LinkWiring.runSession(toPhone.input, toTomTom.output, host) }
        for ((cmd, want) in listOf("ATE0\r" to "OK", "ATD*99***1#\r" to "CONNECT\r\n")) {
            toPhone.output.write(cmd.toByteArray())
            val sb = StringBuilder(); val end = System.currentTimeMillis() + 3000
            while (want !in sb && System.currentTimeMillis() < end) { val ch = toTomTom.input.read(); if (ch < 0) break; sb.append(ch.toChar()) }
            assertTrue(want in sb, "$cmd -> $sb")
        }
        val c = Client(toPhone, toTomTom)
        c.negotiate()
        val resp = c.httpGet(LinkAddr.PHONE, LinkAddr.LIVE_PORT, dropOne = true, path = "/logon?uid=Z00000T00001&body=x")
        val golden = javaClass.getResourceAsStream("/goldens/04_logon.resp")!!.readBytes()
        assertContentEquals(golden, bodyOf(resp))
    }
}

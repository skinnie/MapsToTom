package app.mapstotom.link

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * User-space PPP endpoint with a TCP/UDP relay (port of server/userppp.py): the TomTom's dial-up link
 * without pppd, root or a network interface.
 *   LCP/IPCP: we are 192.168.77.1, the TomTom gets 192.168.77.2, DNS = 192.168.77.1
 *   ICMP echo -> answered; UDP 53 -> LinkHost.resolve
 *   TCP to 192.168.77.1:<port> -> LinkHost.local(port); TCP elsewhere -> LinkHost.connect (internet)
 * TCP is a small receiver/sender: in-order data only (the TomTom retransmits the rest), one 1.5 s
 * retransmit timer per connection, window from the peer. Enough for HTTP.
 */
class PppLink(private val input: InputStream, private val output: OutputStream, internal val host: LinkHost, mru: Int = 1500) {
    private val wlock = Any()
    internal val tcp = ConcurrentHashMap<TcpKey, TcpConn>()
    private val magic = (System.currentTimeMillis() / 1000).toInt().let { if (it == 0) 1 else it }
    private var lcpUp = false
    private var lcpAcked = false
    private var ipcpAcked = false
    private var ipcpPeerOk = false
    private val ourLcp = sortedMapOf(2 to be32(0), 5 to be32(magic))
    internal var peerMru = mru
    private var ident = 0
    @Volatile var running = true
        private set

    /** True once both sides agreed on IPCP (the TomTom has its address). */
    val isUp get() = ipcpPeerOk

    // ---- frames ----
    internal fun send(proto: Int, payload: ByteArray) {
        val f = Hdlc.encode(proto, payload)
        synchronized(wlock) {
            try { output.write(f); output.flush() } catch (e: IOException) { running = false }
        }
    }

    internal fun sendIp(pkt: ByteArray) = send(Hdlc.IPV4, pkt)

    private fun ctl(proto: Int, code: Int, id: Int, data: ByteArray) {
        val p = ByteArray(4 + data.size)
        p[0] = code.toByte(); p[1] = id.toByte(); put16(p, 2, 4 + data.size); data.copyInto(p, 4)
        send(proto, p)
    }

    private fun nextId(): Int { ident = (ident + 1) and 0xff; return ident }

    // ---- LCP / IPCP ----
    private fun sendLcpReq() = ctl(Hdlc.LCP, 1, nextId(), packOpts(ourLcp.map { it.key to it.value }))
    private fun sendIpcpReq() = ctl(Hdlc.IPCP, 1, nextId(), packOpts(listOf(3 to LinkAddr.PHONE)))

    private fun onLcp(code: Int, id: Int, data: ByteArray) {
        when (code) {
            1 -> {                                                // peer Configure-Request
                val opts = options(data)
                val rej = opts.filter { it.first !in setOf(1, 2, 5, 7, 8) }
                if (rej.isNotEmpty()) { ctl(Hdlc.LCP, 4, id, packOpts(rej)); return }
                for ((t, v) in opts) if (t == 1 && v.size == 2) peerMru = u16(v, 0)
                ctl(Hdlc.LCP, 2, id, data)
                lcpUp = true
                if (!lcpAcked) sendLcpReq()
            }
            2 -> lcpAcked = true
            3, 4 -> { for ((t, _) in options(data)) ourLcp.remove(t); sendLcpReq() }
            5 -> { ctl(Hdlc.LCP, 6, id, ByteArray(0)); host.log("ppp: terminated by the TomTom"); running = false }
            9 -> ctl(Hdlc.LCP, 10, id, be32(magic) + data.copyOfRange(minOf(4, data.size), data.size))
        }
        if (lcpUp && lcpAcked && (code == 1 || code == 2) && !ipcpAcked) sendIpcpReq()
    }

    private fun onIpcp(code: Int, id: Int, data: ByteArray) {
        when (code) {
            1 -> {
                val opts = options(data)
                val rej = opts.filter { it.first !in setOf(3, 129, 131) }
                if (rej.isNotEmpty()) { ctl(Hdlc.IPCP, 4, id, packOpts(rej)); return }
                val want = mapOf(3 to LinkAddr.TOMTOM, 129 to LinkAddr.PHONE, 131 to LinkAddr.PHONE)
                val nak = opts.filter { !it.second.contentEquals(want.getValue(it.first)) }.map { it.first to want.getValue(it.first) }
                if (nak.isNotEmpty()) { ctl(Hdlc.IPCP, 3, id, packOpts(nak)); return }
                ctl(Hdlc.IPCP, 2, id, data)
                if (!ipcpPeerOk) { ipcpPeerOk = true; host.log("ppp: up, TomTom = ${ipStr(LinkAddr.TOMTOM)}") }
            }
            2 -> ipcpAcked = true
            3, 4 -> sendIpcpReq()
            5 -> ctl(Hdlc.IPCP, 6, id, ByteArray(0))
        }
    }

    // ---- IP ----
    private fun onIp(pkt: ByteArray) {
        if (pkt.size < 20 || (pkt[0].toInt() and 0xff) ushr 4 != 4) return
        val ihl = (pkt[0].toInt() and 15) * 4
        val total = minOf(u16(pkt, 2), pkt.size)
        if (total < ihl) return
        val proto = pkt[9].toInt() and 0xff
        val src = pkt.copyOfRange(12, 16); val dst = pkt.copyOfRange(16, 20)
        val body = pkt.copyOfRange(ihl, total)
        when {
            proto == 1 && body.size >= 8 && body[0].toInt() == 8 -> {            // ICMP echo request
                val rep = body.copyOf(); rep[0] = 0; rep[1] = 0; rep[2] = 0; rep[3] = 0
                put16(rep, 2, inetCsum(rep))
                sendIp(ipPacket(dst, src, 1, rep))
            }
            proto == 17 && body.size >= 8 -> {
                val sport = u16(body, 0); val dport = u16(body, 2)
                if (dport == 53) {
                    val q = body.copyOfRange(8, body.size)
                    Thread({ dns(src, sport, dst, q) }, "ppp-dns").apply { isDaemon = true }.start()
                }
            }
            proto == 6 && body.size >= 20 -> onTcp(src, dst, body)
        }
    }

    /** A queries via LinkHost.resolve; other types get an empty answer. */
    internal fun dns(src: ByteArray, sport: Int, dst: ByteArray, q: ByteArray) {
        val rep: ByteArray
        try {
            val qid = u16(q, 0)
            var i = 12
            val labels = mutableListOf<String>()
            while (q[i].toInt() != 0) {
                val n = q[i].toInt() and 0xff
                labels += String(q, i + 1, n, Charsets.US_ASCII); i += 1 + n
            }
            val qtype = u16(q, i + 1)
            val question = q.copyOfRange(12, i + 5)
            val name = labels.joinToString(".")
            var ans = ByteArray(0)
            if (qtype == 1) {
                val addr = runCatching { host.resolve(name) }.getOrDefault(emptyList()).firstOrNull { it.size == 4 }
                if (addr != null) {
                    ans = ByteArray(16); ans[0] = 0xc0.toByte(); ans[1] = 0x0c
                    put16(ans, 2, 1); put16(ans, 4, 1); put32(ans, 6, 60); put16(ans, 10, 4); addr.copyInto(ans, 12)
                }
            }
            val rcode = if (ans.isNotEmpty() || qtype != 1) 0 else 3
            val h = ByteArray(12)
            put16(h, 0, qid); put16(h, 2, 0x8180 or rcode); put16(h, 4, 1); put16(h, 6, if (ans.isNotEmpty()) 1 else 0)
            rep = h + question + ans
            host.log("dns $name -> ${if (ans.isNotEmpty()) ipStr(ans.copyOfRange(12, 16)) else "none"}")
        } catch (e: IndexOutOfBoundsException) { return }
        val udp = ByteArray(8 + rep.size)
        put16(udp, 0, 53); put16(udp, 2, sport); put16(udp, 4, udp.size); rep.copyInto(udp, 8)
        put16(udp, 6, l4Csum(dst, src, 17, udp).let { if (it == 0) 0xffff else it })
        sendIp(ipPacket(dst, src, 17, udp))
    }

    private fun onTcp(src: ByteArray, dst: ByteArray, seg: ByteArray) {
        val sport = u16(seg, 0); val dport = u16(seg, 2)
        val seq = u32(seg, 4); val ack = u32(seg, 8)
        val off = ((seg[12].toInt() and 0xff) ushr 4) * 4
        val flags = seg[13].toInt() and 0xff
        val wnd = u16(seg, 14)
        val data = if (off <= seg.size) seg.copyOfRange(off, seg.size) else ByteArray(0)
        val key = TcpKey(src, sport, dst, dport)
        val conn = tcp[key]
        if (conn == null) {
            if (flags and SYN != 0 && flags and ACK == 0) {
                val mss = (peerMru - 40).coerceIn(256, 1460)
                val c = TcpConn(this, key, seq, mss)
                tcp[key] = c
                host.log("tcp ${ipStr(src)}:$sport -> ${ipStr(dst)}:$dport")
                c.start()
            } else if (flags and RST == 0) {                       // unknown connection: reset it
                val rst = ByteArray(20)
                put16(rst, 0, dport); put16(rst, 2, sport); put32(rst, 4, ack); put32(rst, 8, (seq + data.size) and M)
                rst[12] = 0x50; rst[13] = (RST or ACK).toByte()
                put16(rst, 16, l4Csum(dst, src, 6, rst))
                sendIp(ipPacket(dst, src, 6, rst))
            }
            return
        }
        if (flags and SYN != 0) return                             // retransmitted SYN: the timer resends SYN-ACK
        conn.onSegment(seq, ack, flags, wnd, data)
    }

    // ---- main loop ----
    /** Serve the link until the TomTom hangs up (or the stream ends). */
    fun run() {
        val timer = Thread({
            while (running) {
                try { Thread.sleep(500) } catch (e: InterruptedException) { break }
                for (c in tcp.values) c.retransmit()
            }
        }, "ppp-timer").apply { isDaemon = true; start() }
        sendLcpReq()
        val de = Hdlc.Deframer()
        val buf = ByteArray(4096)
        while (running) {
            val n = try { input.read(buf) } catch (e: IOException) { -1 }
            if (n < 0) break
            for ((proto, payload) in de.feed(buf, n)) {
                if ((proto == Hdlc.LCP || proto == Hdlc.IPCP) && payload.size >= 4) {
                    val code = payload[0].toInt() and 0xff; val id = payload[1].toInt() and 0xff
                    val len = u16(payload, 2).coerceIn(4, payload.size)
                    val d = payload.copyOfRange(4, len)
                    if (proto == Hdlc.LCP) onLcp(code, id, d) else onIpcp(code, id, d)
                } else if (proto == Hdlc.IPV4) onIp(payload)
                else if (lcpUp) {                                  // IPv6CP, CCP, ...: Protocol-Reject
                    val p = ByteArray(2 + payload.size); put16(p, 0, proto); payload.copyInto(p, 2)
                    ctl(Hdlc.LCP, 8, nextId(), p)
                }
            }
        }
        running = false
        timer.interrupt()
        for (c in tcp.values) c.close()
        host.log("ppp: link closed")
    }

    companion object {
        internal fun options(data: ByteArray): List<Pair<Int, ByteArray>> {
            val out = mutableListOf<Pair<Int, ByteArray>>()
            var i = 0
            while (i + 2 <= data.size) {
                val t = data[i].toInt() and 0xff; val n = data[i + 1].toInt() and 0xff
                if (n < 2 || i + n > data.size) break
                out += t to data.copyOfRange(i + 2, i + n)
                i += n
            }
            return out
        }

        internal fun packOpts(opts: List<Pair<Int, ByteArray>>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for ((t, v) in opts) { out.write(t); out.write(2 + v.size); out.write(v) }
            return out.toByteArray()
        }

        private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }
}

internal const val FIN = 0x01
internal const val SYN = 0x02
internal const val RST = 0x04
internal const val PSH = 0x08
internal const val ACK = 0x10
internal const val M = 0xffffffffL

/** (TomTom ip, TomTom port, our ip, our port). */
internal class TcpKey(val peerIp: ByteArray, val peerPort: Int, val ourIp: ByteArray, val ourPort: Int) {
    override fun equals(other: Any?) = other is TcpKey && peerPort == other.peerPort && ourPort == other.ourPort &&
        peerIp.contentEquals(other.peerIp) && ourIp.contentEquals(other.ourIp)
    override fun hashCode() = (peerIp.contentHashCode() * 31 + ourIp.contentHashCode()) * 31 + peerPort * 65537 + ourPort
}

/** Where a connection's bytes go: a LocalService (via queue streams) or an internet socket. */
private interface Upstream {
    fun write(data: ByteArray)
    fun shutdownWrite()
    fun close()
}

/** One TomTom-initiated TCP connection. All state is guarded by `this`. */
internal class TcpConn(private val link: PppLink, private val key: TcpKey, isnPeer: Long, private val mss: Int) {
    private var rcvNxt = (isnPeer + 1) and M
    private val iss = System.currentTimeMillis() and M
    private var sndUna = iss
    private var sndNxt = iss                     // SYN not yet sent
    private var outBytes = ByteArray(0)          // from upstream, not yet acked by the peer (from sndUna)
    private var peerWnd = 4096
    private var finIn = false
    private var finOutSent = false
    private var upstreamEof = false
    @Volatile var closed = false
        private set
    private var lastTx = 0L
    private var up: Upstream? = null

    fun start() = Thread({ connectUpstream() }, "ppp-tcp-${key.ourPort}").apply { isDaemon = true; start() }

    // ---- upstream side ----
    private fun connectUpstream() {
        val local = key.ourIp.contentEquals(LinkAddr.PHONE)
        if (local) {
            val svc = runCatching { link.host.local(key.ourPort) }.getOrNull()
            if (svc == null) { refuse("no service on port ${key.ourPort}"); return }
            val inQ = QueueInputStream()
            val toPeer = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (closed) throw IOException("connection closed")
                    fromUpstream(b.copyOfRange(off, off + len))
                }
            }
            up = object : Upstream {
                override fun write(data: ByteArray) = inQ.push(data)
                override fun shutdownWrite() = inQ.end()
                override fun close() = inQ.end()
            }
            synAck()
            try { svc.serve(inQ, toPeer) } catch (e: Exception) { link.host.log("tcp :${key.ourPort} service error: ${e.message}") }
            upstreamDone()
        } else {
            val s: Socket? = runCatching { link.host.connect(key.ourIp, key.ourPort) }.getOrNull()
            if (s == null) { refuse("connect failed"); return }
            val os = s.getOutputStream()
            up = object : Upstream {
                override fun write(data: ByteArray) { try { os.write(data); os.flush() } catch (_: IOException) {} }
                override fun shutdownWrite() { try { s.shutdownOutput() } catch (_: IOException) {} }
                override fun close() { try { s.close() } catch (_: IOException) {} }
            }
            synAck()
            val buf = ByteArray(16384)
            val ins = s.getInputStream()
            while (true) {
                val n = try { ins.read(buf) } catch (e: IOException) { -1 }
                if (n < 0) break
                fromUpstream(buf.copyOf(n))
            }
            upstreamDone()
        }
    }

    private fun refuse(why: String) {
        link.host.log("tcp ${ipStr(key.ourIp)}:${key.ourPort} refused: $why")
        synchronized(this) { send(RST or ACK, ByteArray(0), iss) }
        close()
    }

    private fun synAck() {
        synchronized(this) {
        send(SYN or ACK, ByteArray(0), iss, mssOpt())
        sndNxt = (iss + 1) and M
        lastTx = now()
    }
    }

    private fun fromUpstream(data: ByteArray) {
        synchronized(this) {
        if (closed) return
        outBytes += data
        push()
    }
    }

    private fun upstreamDone() { synchronized(this) { upstreamEof = true; push() } }

    // ---- peer side ----
    fun onSegment(seq: Long, ack: Long, flags: Int, wnd: Int, data: ByteArray) {
        synchronized(this) {
        if (flags and RST != 0) { close(); return }
        if (flags and ACK != 0) {
            var acked = (ack - sndUna) and M
            val inflight = (sndNxt - sndUna) and M
            if (acked in 1..inflight) {
                if (sndUna == iss) { acked -= 1; sndUna = (sndUna + 1) and M }     // our SYN
                val n = minOf(acked, outBytes.size.toLong()).toInt()
                outBytes = outBytes.copyOfRange(n, outBytes.size)
                sndUna = (sndUna + n) and M
                if (acked > n && finOutSent) {                                     // our FIN acked
                    sndUna = (sndUna + 1) and M
                    if (finIn) close()
                }
            }
            peerWnd = wnd
        }
        if (data.isNotEmpty() || flags and FIN != 0) {
            if (seq == rcvNxt) {
                if (data.isNotEmpty()) up?.write(data)
                rcvNxt = (rcvNxt + data.size) and M
                if (flags and FIN != 0 && !finIn) {
                    finIn = true
                    rcvNxt = (rcvNxt + 1) and M
                    up?.shutdownWrite()
                }
            }
            send(ACK, ByteArray(0), sndNxt)                                        // (re)acknowledge
            if (finIn && finOutSent && sndUna == sndNxt) close()
        }
        push()
    }
    }

    /** Send what the peer window allows; FIN after the last byte once upstream closed. Caller holds the lock. */
    private fun push() {
        if (closed || (sndUna == iss && sndNxt == iss)) return
        val base = (sndUna + if (sndUna == iss) 1 else 0) and M
        var sent = ((sndNxt - base) and M).toInt()
        if (finOutSent) return
        while (sent < outBytes.size && sent < peerWnd) {
            val len = minOf(mss, peerWnd - sent, outBytes.size - sent)
            send(ACK or PSH, outBytes.copyOfRange(sent, sent + len), (base + sent) and M)
            sent += len
            sndNxt = (base + sent) and M
            lastTx = now()
        }
        if (upstreamEof && sent == outBytes.size && !finOutSent) {
            send(FIN or ACK, ByteArray(0), sndNxt)
            sndNxt = (sndNxt + 1) and M
            finOutSent = true
            lastTx = now()
        }
    }

    /** Go back to sndUna if nothing was acked for 1.5 s. */
    fun retransmit() {
        synchronized(this) {
        if (closed || sndUna == sndNxt || now() - lastTx < 1500) return
        if (sndUna == iss) { send(SYN or ACK, ByteArray(0), iss, mssOpt()); lastTx = now(); return }   // SYN-ACK lost
        sndNxt = sndUna
        if (finOutSent && outBytes.isEmpty()) {
            send(FIN or ACK, ByteArray(0), sndUna)
            sndNxt = (sndUna + 1) and M
            lastTx = now()
            return
        }
        finOutSent = false
        push()
    }
    }

    private fun mssOpt() = byteArrayOf(2, 4, (mss ushr 8).toByte(), mss.toByte())

    private fun send(flags: Int, data: ByteArray, seq: Long, opts: ByteArray = ByteArray(0)) {
        val hlen = 20 + opts.size
        val seg = ByteArray(hlen + data.size)
        put16(seg, 0, key.ourPort); put16(seg, 2, key.peerPort)
        put32(seg, 4, seq); put32(seg, 8, if (flags and ACK != 0) rcvNxt else 0)
        seg[12] = ((hlen / 4) shl 4).toByte(); seg[13] = flags.toByte(); put16(seg, 14, 65535)
        opts.copyInto(seg, 20); data.copyInto(seg, hlen)
        put16(seg, 16, l4Csum(key.ourIp, key.peerIp, 6, seg))
        link.sendIp(ipPacket(key.ourIp, key.peerIp, 6, seg))
    }

    fun close() {
        val u: Upstream?
        synchronized(this) {
            if (closed) return
            closed = true
            u = up
        }
        u?.close()
        link.tcp.remove(key)
    }

    private fun now() = System.nanoTime() / 1_000_000
}

/** Blocking InputStream fed by push(); end() = EOF after the queued bytes. */
internal class QueueInputStream : InputStream() {
    private val q = ArrayDeque<ByteArray>()
    private var head = ByteArray(0)
    private var pos = 0
    private var eof = false
    private val lock = Object()

    fun push(b: ByteArray) = synchronized(lock) { if (!eof) { q.addLast(b); lock.notifyAll() } }
    fun end() = synchronized(lock) { eof = true; lock.notifyAll() }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
        if (len == 0) return 0
        while (pos >= head.size) {
            val next = q.removeFirstOrNull()
            if (next != null) { head = next; pos = 0; continue }
            if (eof) return -1
            lock.wait()
        }
        val n = minOf(len, head.size - pos)
        head.copyInto(b, off, pos, pos + n)
        pos += n
        n
    }

    override fun available(): Int = synchronized(lock) { head.size - pos + q.sumOf { it.size } }
}

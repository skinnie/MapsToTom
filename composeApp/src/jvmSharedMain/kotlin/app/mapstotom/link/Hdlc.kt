package app.mapstotom.link

import java.io.ByteArrayOutputStream

/** PPP in HDLC-like framing (RFC 1662) and the IPv4 checksums. Port of server/userppp.py. */
object Hdlc {
    const val LCP = 0xc021
    const val IPCP = 0x8021
    const val IPV4 = 0x0021

    private val TABLE = IntArray(256) { b ->
        var v = b
        repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor 0x8408 else v ushr 1 }
        v
    }

    fun fcs16(data: ByteArray, from: Int = 0, to: Int = data.size, start: Int = 0xffff): Int {
        var fcs = start
        for (i in from until to) fcs = (fcs ushr 8) xor TABLE[(fcs xor data[i].toInt()) and 0xff]
        return fcs
    }

    /** Full address/control and 2-byte protocol; escapes 0x7e, 0x7d and all control characters. */
    fun encode(proto: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(4 + payload.size + 2)
        body[0] = 0xff.toByte(); body[1] = 0x03
        body[2] = (proto ushr 8).toByte(); body[3] = proto.toByte()
        payload.copyInto(body, 4)
        val f = fcs16(body, 0, body.size - 2) xor 0xffff
        body[body.size - 2] = f.toByte(); body[body.size - 1] = (f ushr 8).toByte()
        val out = ByteArrayOutputStream(body.size * 2 + 2)
        out.write(0x7e)
        for (x in body) {
            val b = x.toInt() and 0xff
            if (b == 0x7e || b == 0x7d || b < 0x20) { out.write(0x7d); out.write(b xor 0x20) } else out.write(b)
        }
        out.write(0x7e)
        return out.toByteArray()
    }

    /** Bytes in, (protocol, payload) out. Drops bad-FCS frames; accepts compressed address/control and protocol. */
    class Deframer {
        private val buf = ByteArrayOutputStream()
        private var esc = false

        fun feed(data: ByteArray, n: Int = data.size): List<Pair<Int, ByteArray>> {
            val frames = mutableListOf<Pair<Int, ByteArray>>()
            for (i in 0 until n) {
                val b = data[i].toInt() and 0xff
                when {
                    b == 0x7e -> finish()?.let { frames += it }
                    b == 0x7d -> esc = true
                    else -> { buf.write(if (esc) b xor 0x20 else b); esc = false }
                }
            }
            return frames
        }

        private fun finish(): Pair<Int, ByteArray>? {
            val f = buf.toByteArray(); buf.reset(); esc = false
            if (f.size < 4 || fcs16(f) != 0xf0b8) return null
            var s = 0
            val e = f.size - 2
            if (f[0] == 0xff.toByte() && f[1] == 0x03.toByte()) s = 2
            if (s >= e) return null
            val p0 = f[s].toInt() and 0xff
            return if (p0 and 1 != 0) p0 to f.copyOfRange(s + 1, e)            // 1-byte protocol
            else if (s + 1 < e) ((p0 shl 8) or (f[s + 1].toInt() and 0xff)) to f.copyOfRange(s + 2, e)
            else null
        }
    }
}

/** Internet checksum. */
fun inetCsum(data: ByteArray, from: Int = 0, to: Int = data.size, start: Long = 0): Int {
    var s = start
    var i = from
    while (i + 1 < to) { s += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff); i += 2 }
    if (i < to) s += (data[i].toInt() and 0xff) shl 8
    while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
    return s.inv().toInt() and 0xffff
}

private var ipIdent = 0

/** IPv4 packet with DF, TTL 64 and a correct header checksum. */
fun ipPacket(src: ByteArray, dst: ByteArray, proto: Int, payload: ByteArray): ByteArray {
    val id = synchronized(Hdlc) { ipIdent = (ipIdent + 1) and 0xffff; ipIdent }
    val total = 20 + payload.size
    val p = ByteArray(total)
    p[0] = 0x45; p[2] = (total ushr 8).toByte(); p[3] = total.toByte()
    p[4] = (id ushr 8).toByte(); p[5] = id.toByte(); p[6] = 0x40; p[8] = 64; p[9] = proto.toByte()
    src.copyInto(p, 12); dst.copyInto(p, 16)
    val c = inetCsum(p, 0, 20)
    p[10] = (c ushr 8).toByte(); p[11] = c.toByte()
    payload.copyInto(p, 20)
    return p
}

/** TCP/UDP checksum over the pseudo header + segment. 0 when the segment's own checksum is right. */
fun l4Csum(src: ByteArray, dst: ByteArray, proto: Int, seg: ByteArray): Int {
    val ps = ByteArray(12 + seg.size)
    src.copyInto(ps, 0); dst.copyInto(ps, 4); ps[9] = proto.toByte()
    ps[10] = (seg.size ushr 8).toByte(); ps[11] = seg.size.toByte()
    seg.copyInto(ps, 12)
    return inetCsum(ps)
}

internal fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
internal fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
internal fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
internal fun put32(b: ByteArray, i: Int, v: Long) { put16(b, i, (v ushr 16).toInt()); put16(b, i + 2, v.toInt()) }
internal fun ipStr(b: ByteArray) = b.joinToString(".") { (it.toInt() and 0xff).toString() }

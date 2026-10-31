package app.mapstotom.link

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/*
 * In-car link: the phone plays the TomTom's Bluetooth "phone" (DUN modem), so its Live services work in the car.
 *
 *   TomTom --Bluetooth RFCOMM (DUN)--> AtModem --CONNECT--> PppLink (HDLC, LCP, IPCP, IPv4, ICMP, UDP DNS, TCP)
 *       TCP to 192.168.77.1:<port> -> LinkHost.local(port)   (8080 = LiveServer, the in-app Live services)
 *       TCP to anything else       -> LinkHost.connect(...)  (plain internet through the phone)
 *
 * Specs and reference code: go950-navcore-9541/docs/android_bridge.md, docs/inapp_services.md,
 * server/fakedun.py, server/userppp.py, server/mockserver.py (verified on a real ONE v4, 2026-10-30).
 */

/** A TCP connection accepted for the phone's own address (192.168.77.1). Runs on its own thread; return closes it. */
fun interface LocalService {
    fun serve(input: InputStream, output: OutputStream)
}

/** What the PPP side needs from the platform. */
interface LinkHost {
    /** Service for a TCP port on 192.168.77.1, or null to refuse the connection (RST). */
    fun local(port: Int): LocalService?

    /** Connected socket to the internet, or null on failure (RST). [ip] is the 4-byte destination. */
    fun connect(ip: ByteArray, port: Int): Socket?

    /** IPv4 addresses (4 bytes each) for a DNS name; empty = NXDOMAIN. */
    fun resolve(name: String): List<ByteArray>

    /** One line for the link log (shown in the app, kept short). */
    fun log(line: String) {}
}

/** Addresses used on the PPP link (must match the card's servicediscovery.txt / herculesdiscovery.txt). */
object LinkAddr {
    val PHONE = byteArrayOf(192.toByte(), 168.toByte(), 77, 1)
    val TOMTOM = byteArrayOf(192.toByte(), 168.toByte(), 77, 2)
    const val LIVE_PORT = 8080
}
